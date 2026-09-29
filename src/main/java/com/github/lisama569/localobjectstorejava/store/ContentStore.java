package com.github.lisama569.localobjectstorejava.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 内容寻址的块存储：以 SHA-256 为键，引用计数支撑去重。
 * 相同内容只落盘一次；引用归零时物理回收。
 * 引用计数索引持久化到磁盘，重启后保持一致。
 */
@Component
public class ContentStore {

    private static final Logger log = LoggerFactory.getLogger(ContentStore.class);

    /** 每个内容块的引用计数与大小。 */
    record BlobEntry(long refcount, long size) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        BlobEntry retain() { return new BlobEntry(refcount + 1, size); }
        BlobEntry release() { return new BlobEntry(refcount - 1, size); }
    }

    private final Path blobDir;
    private final Path indexFile;
    private final Map<String, BlobEntry> index = new HashMap<>();

    public ContentStore(StorageConfig config) {
        try {
            this.blobDir = Path.of(config.getRootDir(), "blobs");
            this.indexFile = Path.of(config.getRootDir(), "content-index.ser");
            Files.createDirectories(blobDir);
            loadIndex();
        } catch (IOException e) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                    "初始化内容存储失败: " + e.getMessage(), e);
        }
    }

    /** 写入内容并返回其 SHA-256；相同内容去重并增加引用计数。 */
    public synchronized String put(byte[] data) {
        String sha = sha256Hex(data);
        BlobEntry existing = index.get(sha);
        if (existing != null) {
            index.put(sha, existing.retain());
            persistIndex();
            log.info("dedup hit sha={} refcount={}", sha, existing.refcount() + 1);
            return sha;
        }
        Path target = blobDir.resolve(sha);
        writeAtomic(target, data);
        index.put(sha, new BlobEntry(1, data.length));
        persistIndex();
        return sha;
    }

    /** 读取内容；不存在返回 null。 */
    public synchronized byte[] get(String sha256) {
        if (!index.containsKey(sha256)) {
            return null;
        }
        try {
            return Files.readAllBytes(blobDir.resolve(sha256));
        } catch (IOException e) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                    "读取内容块失败 sha=" + sha256, e);
        }
    }

    public synchronized boolean exists(String sha256) {
        return index.containsKey(sha256);
    }

    /** 增加一个引用（去重命中时使用）。 */
    public synchronized void retain(String sha256) {
        BlobEntry e = index.get(sha256);
        if (e == null) {
            throw new StoreException(StoreException.Code.NOT_FOUND, "内容块不存在 sha=" + sha256);
        }
        index.put(sha256, e.retain());
        persistIndex();
    }

    /** 释放一个引用；归零时删除物理数据。 */
    public synchronized void release(String sha256) {
        BlobEntry e = index.get(sha256);
        if (e == null) {
            log.warn("release on unknown blob sha={}", sha256);
            return;
        }
        if (e.refcount() <= 1) {
            index.remove(sha256);
            try {
                Files.deleteIfExists(blobDir.resolve(sha256));
            } catch (IOException ex) {
                throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                        "回收内容块失败 sha=" + sha256, ex);
            }
        } else {
            index.put(sha256, e.release());
        }
        persistIndex();
    }

    /** 当前物理占用的字节数。 */
    public synchronized long usedBytes() {
        return index.values().stream().mapToLong(BlobEntry::size).sum();
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeAtomic(Path target, byte[] data) {
        try {
            Path tmp = Files.createTempFile(blobDir, "tmp-", ".part");
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE,
                    "写入内容块失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void loadIndex() {
        if (!Files.exists(indexFile)) {
            return;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(indexFile))) {
            index.putAll((Map<String, BlobEntry>) in.readObject());
            log.info("loaded content index, {} blobs, {} bytes", index.size(), usedBytes());
        } catch (IOException | ClassNotFoundException e) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE, "加载内容索引失败", e);
        }
    }

    private void persistIndex() {
        try {
            Path tmp = Files.createTempFile(indexFile.getParent(), "idx-", ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(new HashMap<>(index));
            }
            try {
                Files.move(tmp, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StoreException(StoreException.Code.STORAGE_FAILURE, "持久化内容索引失败", e);
        }
    }
}
