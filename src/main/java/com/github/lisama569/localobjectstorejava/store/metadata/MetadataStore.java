package com.github.lisama569.localobjectstorejava.store.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import com.github.lisama569.localobjectstorejava.store.model.BlobRecord;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 元数据存储：内存索引 + 每实体一个 JSON 文件持久化。
 * 写入采用“临时文件 + 原子 rename”，读方与重启恢复都只会看到完整 JSON。
 *
 * <pre>
 * root/meta/
 *   uploads/{uploadId}.json
 *   objects/{tenant}/{url编码的objectKey}.json   每个文件保存该 key 的全部版本（有序）
 *   blobs/{sha256}.json
 * </pre>
 */
public class MetadataStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path metaDir;
    private final Path uploadsDir;
    private final Path objectsDir;
    private final Path blobsDir;

    private final Map<String, MultipartUpload> uploads = new ConcurrentHashMap<>();
    /** tenant -> (objectKey -> 有序版本，最新在尾)，嵌套结构避免 key 拼接歧义 */
    private final Map<String, Map<String, List<ObjectVersion>>> objects = new ConcurrentHashMap<>();
    private final Map<String, BlobRecord> blobs = new ConcurrentHashMap<>();

    public MetadataStore(Path rootDir) {
        this.metaDir = rootDir.resolve("meta");
        this.uploadsDir = metaDir.resolve("uploads");
        this.objectsDir = metaDir.resolve("objects");
        this.blobsDir = metaDir.resolve("blobs");
    }

    public void init() {
        try {
            Files.createDirectories(uploadsDir);
            Files.createDirectories(objectsDir);
            Files.createDirectories(blobsDir);
            loadAll();
        } catch (IOException e) {
            throw fault("init metadata", e);
        }
    }

    private void loadAll() throws IOException {
        try (Stream<Path> files = Files.list(uploadsDir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(this::loadUpload);
        }
        if (Files.isDirectory(objectsDir)) {
            List<Path> tenantDirs = listDirs(objectsDir);
            tenantDirs.sort(Comparator.naturalOrder());
            for (Path tenantDir : tenantDirs) {
                try (Stream<Path> files = Files.list(tenantDir)) {
                    files.filter(p -> p.toString().endsWith(".json")).sorted()
                            .forEach(this::loadObjectVersions);
                }
            }
        }
        try (Stream<Path> files = Files.list(blobsDir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(this::loadBlob);
        }
    }

    private void loadUpload(Path file) {
        try {
            MultipartUpload upload = MAPPER.readValue(file.toFile(), MultipartUpload.class);
            uploads.put(upload.uploadId(), upload);
        } catch (IOException e) {
            throw fault("load upload metadata: " + file.getFileName(), e);
        }
    }

    private void loadObjectVersions(Path file) {
        try {
            ObjectDoc doc = MAPPER.readValue(file.toFile(), ObjectDoc.class);
            if (doc.versions == null || doc.versions.isEmpty()) {
                return;
            }
            doc.versions.sort(Comparator.comparingLong(ObjectVersion::createdAtMillis));
            String tenant = doc.versions.get(0).tenant();
            objects.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                    .put(doc.objectKey, new ArrayList<>(doc.versions));
        } catch (IOException e) {
            throw fault("load object metadata: " + file.getFileName(), e);
        }
    }

    private void loadBlob(Path file) {
        try {
            BlobRecord blob = MAPPER.readValue(file.toFile(), BlobRecord.class);
            blobs.put(blob.key(), blob);
        } catch (IOException e) {
            throw fault("load blob metadata: " + file.getFileName(), e);
        }
    }

    private static List<Path> listDirs(Path parent) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(parent)) {
            dirs.filter(Files::isDirectory).forEach(result::add);
        }
        return result;
    }

    // ---------------- uploads ----------------

    public MultipartUpload getUpload(String uploadId) {
        return uploads.get(uploadId);
    }

    public List<MultipartUpload> activeUploads() {
        return uploads.values().stream().filter(MultipartUpload::isActive).toList();
    }

    public void putUpload(MultipartUpload upload) {
        uploads.put(upload.uploadId(), upload);
        writeJson(uploadFile(upload.uploadId()), upload);
    }

    public void removeUpload(String uploadId) {
        MultipartUpload removed = uploads.remove(uploadId);
        if (removed != null) {
            try {
                Files.deleteIfExists(uploadFile(uploadId));
            } catch (IOException e) {
                throw fault("delete upload metadata", e);
            }
        }
    }

    private Path uploadFile(String uploadId) {
        return uploadsDir.resolve(encode(uploadId) + ".json");
    }

    // ---------------- object versions ----------------

    public ObjectVersion latestVersion(String tenant, String objectKey) {
        List<ObjectVersion> versions = versionList(tenant, objectKey);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.get(versions.size() - 1);
    }

    public ObjectVersion getVersion(String tenant, String objectKey, String versionId) {
        List<ObjectVersion> versions = versionList(tenant, objectKey);
        if (versions == null) {
            return null;
        }
        return versions.stream()
                .filter(v -> v.versionId().equals(versionId))
                .findFirst().orElse(null);
    }

    public List<ObjectVersion> versions(String tenant, String objectKey) {
        List<ObjectVersion> versions = versionList(tenant, objectKey);
        return versions == null ? List.of() : List.copyOf(versions);
    }

    /** 追加一个新版本：内存列表与 JSON 文件整体原子替换。 */
    public void addVersion(ObjectVersion version) {
        Map<String, List<ObjectVersion>> tenantMap =
                objects.computeIfAbsent(version.tenant(), t -> new ConcurrentHashMap<>());
        synchronized (tenantMap) {
            List<ObjectVersion> updated =
                    new ArrayList<>(tenantMap.getOrDefault(version.objectKey(), List.of()));
            updated.add(version);
            tenantMap.put(version.objectKey(), updated);
            persistObject(version.objectKey(), updated);
        }
    }

    public void removeVersion(String tenant, String objectKey, String versionId) {
        Map<String, List<ObjectVersion>> tenantMap = objects.get(tenant);
        if (tenantMap == null) {
            return;
        }
        synchronized (tenantMap) {
            List<ObjectVersion> current = tenantMap.get(objectKey);
            if (current == null) {
                return;
            }
            List<ObjectVersion> updated = new ArrayList<>(current);
            updated.removeIf(v -> v.versionId().equals(versionId));
            if (updated.isEmpty()) {
                tenantMap.remove(objectKey);
                try {
                    Files.deleteIfExists(objectFile(tenant, objectKey));
                } catch (IOException e) {
                    throw fault("delete object metadata", e);
                }
            } else {
                tenantMap.put(objectKey, updated);
                persistObject(objectKey, updated);
            }
        }
    }

    public List<String> listObjectKeys(String tenant, String prefix) {
        Path tenantDir = objectsDir.resolve(encode(tenant));
        if (!Files.isDirectory(tenantDir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(tenantDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                String key = decode(stripSuffix(file.getFileName().toString()));
                if (prefix == null || prefix.isEmpty() || key.startsWith(prefix)) {
                    result.add(key);
                }
            });
        } catch (IOException e) {
            throw fault("list object keys", e);
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private List<ObjectVersion> versionList(String tenant, String objectKey) {
        return Optional.ofNullable(objects.get(tenant))
                .map(m -> m.get(objectKey)).orElse(null);
    }

    private void persistObject(String objectKey, List<ObjectVersion> versions) {
        String tenant = versions.get(0).tenant();
        ObjectDoc doc = new ObjectDoc();
        doc.objectKey = objectKey;
        doc.versions = new ArrayList<>(versions);
        writeJson(objectFile(tenant, objectKey), doc);
    }

    private Path objectFile(String tenant, String objectKey) {
        Path dir = objectsDir.resolve(encode(tenant));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw fault("create object metadata directory", e);
        }
        return dir.resolve(encode(objectKey) + ".json");
    }

    // ---------------- blobs ----------------

    public List<ObjectVersion> allVersions() {
        List<ObjectVersion> all = new ArrayList<>();
        objects.values().forEach(tenantMap ->
                tenantMap.values().forEach(all::addAll));
        all.sort(Comparator.comparingLong(ObjectVersion::createdAtMillis));
        return all;
    }

    public BlobRecord getBlob(String key) {
        return blobs.get(key);
    }

    public List<BlobRecord> blobs() {
        return List.copyOf(blobs.values());
    }

    public void putBlob(BlobRecord blob) {
        blobs.put(blob.key(), blob);
        writeJson(blobsDir.resolve(encode(blob.key()) + ".json"), blob);
    }

    public void removeBlob(String key) {
        BlobRecord removed = blobs.remove(key);
        if (removed != null) {
            try {
                Files.deleteIfExists(blobsDir.resolve(encode(key) + ".json"));
            } catch (IOException e) {
                throw fault("delete blob metadata", e);
            }
        }
    }

    // ---------------- helpers ----------------

    private void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "meta-", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw fault("persist metadata: " + target.getFileName(), e);
        }
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String encoded) {
        return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private static String stripSuffix(String name) {
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    private static ObjectStoreException fault(String action, IOException cause) {
        return new ObjectStoreException(ErrorType.STORAGE_FAULT,
                "metadata failure while: " + action, cause);
    }

    /** Jackson 文档结构：对象 key + 全版本。 */
    public static class ObjectDoc {
        public String objectKey;
        public List<ObjectVersion> versions;
    }
}
