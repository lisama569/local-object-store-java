package com.github.lisama569.localobjectstorejava.store.storage;

import com.github.lisama569.localobjectstorejava.store.digest.Checksums;
import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 磁盘块存储。
 *
 * <pre>
 * root/
 *   blobs/{hh}/{hh}/{完整sha256}   内容寻址数据块（不可变，天然去重）
 *   uploads/{uploadId}/part-N      已接收分片
 *   uploads/{uploadId}/object.tmp  完成时拼接的临时对象
 * </pre>
 *
 * 所有落盘都采用“临时文件 + 同文件系统原子 rename”，读方永远不会看到半写文件；
 * IO 类故障统一归一化为 STORAGE_FAULT，不把底层异常直接透出。
 */
public class BlobStore {

    public record Commit(String key, long size, boolean alreadyExisted) {
    }

    private final Path rootDir;
    private final Path blobsDir;
    private final Path uploadsDir;
    private final Path tmpDir;

    public BlobStore(Path rootDir) {
        this.rootDir = rootDir;
        this.blobsDir = rootDir.resolve("blobs");
        this.uploadsDir = rootDir.resolve("uploads");
        this.tmpDir = rootDir.resolve("tmp");
    }

    public void init() {
        try {
            Files.createDirectories(blobsDir);
            Files.createDirectories(uploadsDir);
            Files.createDirectories(rootDir.resolve("tmp"));
        } catch (IOException e) {
            throw fault("init storage directories", e);
        }
    }

    public Path root() {
        return rootDir;
    }

    public Path blobFile(String key) {
        if (key == null || key.length() < 5) {
            throw new ObjectStoreException(ErrorType.INVALID_ARGUMENT, "invalid blob key");
        }
        return blobsDir.resolve(key.substring(0, 2))
                .resolve(key.substring(2, 4))
                .resolve(key);
    }

    public boolean blobExists(String key) {
        return Files.exists(blobFile(key));
    }

    public long blobSize(String key) {
        try {
            return Files.size(blobFile(key));
        } catch (IOException e) {
            throw fault("read blob size", e);
        }
    }

    public InputStream openBlob(String key) {
        try {
            return Files.newInputStream(blobFile(key));
        } catch (IOException e) {
            throw fault("open blob content", e);
        }
    }

    /**
     * 把已写好的源文件以 expectedSha256 为键提交：
     * 先复核摘要（防半写/脏数据），再原子 rename 到内容寻址位置。
     * 目标已存在视为去重命中（内容键相同即内容相同），源文件被回收。
     */
    public Commit commitBlob(Path source, String expectedSha256) {
        String actual = Checksums.sha256Hex(source);
        if (expectedSha256 != null && !expectedSha256.equals(actual)) {
            throw new ObjectStoreException(ErrorType.CHECKSUM_MISMATCH,
                    "object checksum mismatch: expected=" + expectedSha256 + " actual=" + actual);
        }
        long size;
        try {
            size = Files.size(source);
        } catch (IOException e) {
            throw fault("read temp object size", e);
        }
        Path target = blobFile(actual);
        try {
            Files.createDirectories(target.getParent());
            try {
                moveAtomically(source, target);
            } catch (BlobAlreadyExistsSignal duplicate) {
                // 并发提交相同内容：已有完整 blob，去重命中
                try {
                    Files.deleteIfExists(source);
                } catch (IOException ignored) {
                    // 源文件残留不影响正确性，会在下次启动对账时清理
                }
                return new Commit(actual, size, true);
            }
        } catch (IOException e) {
            throw fault("commit blob", e);
        }
        return new Commit(actual, size, false);
    }

    public void deleteBlob(String key) {
        try {
            Files.deleteIfExists(blobFile(key));
        } catch (IOException e) {
            throw fault("delete blob", e);
        }
    }

    public Path uploadDir(String uploadId) {
        return uploadsDir.resolve(uploadId);
    }

    public Path uploadsRoot() {
        return uploadsDir;
    }

    public Path partFile(String uploadId, int partNumber) {
        return uploadDir(uploadId).resolve("part-" + partNumber);
    }

    public Path createTempPartFile(String uploadId) {
        try {
            Path dir = uploadDir(uploadId);
            Files.createDirectories(dir);
            return Files.createTempFile(dir, "incoming-", ".tmp");
        } catch (IOException e) {
            throw fault("create temp part file", e);
        }
    }

    /** 一次性写入（simple put）使用的临时位置，与 blobs 同文件系统，保证原子 rename。 */
    public Path createTempBlobFile() {
        try {
            Files.createDirectories(tmpDir);
            return Files.createTempFile(tmpDir, "incoming-", ".tmp");
        } catch (IOException e) {
            throw fault("create temp blob file", e);
        }
    }

    public Path createTempObjectFile(String uploadId) {
        try {
            Path dir = uploadDir(uploadId);
            Files.createDirectories(dir);
            return Files.createTempFile(dir, "object-", ".tmp");
        } catch (IOException e) {
            throw fault("create temp object file", e);
        }
    }

    /** 同根目录下的原子 rename；目标已存在时抛出以区分“去重命中”与“新建”。 */
    public void moveAtomically(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 极端环境退化：显式检查目标是否已存在后再覆盖移动
            if (Files.exists(target)) {
                throw new BlobAlreadyExistsSignal();
            }
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException io) {
                throw fault("move file (fallback)", io);
            }
        } catch (FileAlreadyExistsException e) {
            throw new BlobAlreadyExistsSignal();
        } catch (IOException e) {
            throw fault("move file atomically", e);
        }
    }

    public Stream<Path> listPartFiles(String uploadId) {
        Path dir = uploadDir(uploadId);
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try {
            return Files.list(dir);
        } catch (IOException e) {
            throw fault("list part files", e);
        }
    }

    /** 中止会话/完成清理：删除整个上传目录，释放分片占用的磁盘。 */
    public void deleteUploadDir(String uploadId) {
        Path dir = uploadDir(uploadId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw fault("delete upload directory entry", e);
                }
            });
        } catch (IOException e) {
            throw fault("delete upload directory", e);
        }
    }

    private static ObjectStoreException fault(String action, IOException cause) {
        return new ObjectStoreException(ErrorType.STORAGE_FAULT,
                "storage failure while: " + action, cause);
    }

    /** 内部信号：目标 blob 已存在，仅用于提交时判定去重命中，绝不透出给调用方。 */
    private static final class BlobAlreadyExistsSignal extends RuntimeException {
        BlobAlreadyExistsSignal() {
            super(null, null, false, false);
        }
    }
}
