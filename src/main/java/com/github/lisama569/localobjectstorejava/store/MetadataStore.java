package com.github.lisama569.localobjectstorejava.store;

import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 内存元数据 + 磁盘快照持久化。
 * 所有修改在锁内执行并原子落盘，保证并发一致与重启可恢复。
 */
@Component
public class MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(MetadataStore.class);

    /** 持久化快照：对象元数据 + 上传会话。 */
    record Snapshot(Map<String, StoredObject> objects,
                    Map<String, MultipartUpload> uploads) implements Serializable {
    }

    private final Path snapshotFile;
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final Map<String, MultipartUpload> uploads = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public MetadataStore(StorageConfig config) {
        this.snapshotFile = Path.of(config.getRootDir(), "metadata.ser");
        load();
    }

    private static String id(String owner, String key) {
        return owner + "\n" + key;
    }

    public Optional<StoredObject> findObject(String owner, String key) {
        return Optional.ofNullable(objects.get(id(owner, key)));
    }

    public List<StoredObject> listObjects(String owner) {
        List<StoredObject> result = new ArrayList<>();
        for (StoredObject o : objects.values()) {
            if (o.owner().equals(owner)) {
                result.add(o);
            }
        }
        return result;
    }

    public void saveObject(StoredObject object) {
        lock.lock();
        try {
            objects.put(id(object.owner(), object.key()), object);
            persist();
        } finally {
            lock.unlock();
        }
    }

    /** 删除整个对象（所有版本均已物理回收后调用）。 */
    public void removeObject(String owner, String key) {
        lock.lock();
        try {
            objects.remove(id(owner, key));
            persist();
        } finally {
            lock.unlock();
        }
    }

    public Optional<MultipartUpload> findUpload(String uploadId) {
        return Optional.ofNullable(uploads.get(uploadId));
    }

    /** 列出某调用方的全部上传会话（用于配额统计与运维查询）。 */
    public List<MultipartUpload> uploadsOf(String owner) {
        List<MultipartUpload> result = new ArrayList<>();
        for (MultipartUpload u : uploads.values()) {
            if (u.owner().equals(owner)) {
                result.add(u);
            }
        }
        return result;
    }

    public void saveUpload(MultipartUpload upload) {
        lock.lock();
        try {
            uploads.put(upload.uploadId(), upload);
            persist();
        } finally {
            lock.unlock();
        }
    }

    public void removeUpload(String uploadId) {
        lock.lock();
        try {
            uploads.remove(uploadId);
            persist();
        } finally {
            lock.unlock();
        }
    }

    /** 在元数据锁内执行，保证复合操作原子性。 */
    public <T> T inLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void load() {
        if (!Files.exists(snapshotFile)) {
            return;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(snapshotFile))) {
            Snapshot snap = (Snapshot) in.readObject();
            objects.putAll(snap.objects());
            uploads.putAll(snap.uploads());
            log.info("loaded metadata: {} objects, {} uploads", objects.size(), uploads.size());
        } catch (IOException | ClassNotFoundException e) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE, "加载元数据快照失败", e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(snapshotFile.getParent());
            Path tmp = Files.createTempFile(snapshotFile.getParent(), "meta-", ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(new Snapshot(new HashMap<>(objects), new HashMap<>(uploads)));
            }
            try {
                Files.move(tmp, snapshotFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE, "持久化元数据失败", e);
        }
    }
}
