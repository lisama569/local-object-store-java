package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配额：单分片大小上限、全仓容量上限、超限后无残留分片/临时文件/容量占用，
 * 以及去重内容不重复计费。
 */
class QuotaTest extends StoreTestSupport {

    @BeforeEach
    void setUp() {
        // 总容量 1KB，单分片上限 256B
        buildStore(1024L, 256L, 100);
    }

    @Test
    @DisplayName("单分片超过上限：QUOTA_EXCEEDED（part size 原因），无残留且可重试")
    void partSizeLimit() {
        var caller = tenant("q");
        var upload = service.initiate(caller, "k", 3, null, null);
        byte[] oversized = repeated((byte) 1, 300);
        log.info("[case-input] partSize={} partLimit=256", oversized.length);

        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.uploadPart(caller, upload.uploadId(), 1,
                        stream(oversized), null));
        assertEquals(ErrorType.QUOTA_EXCEEDED, ex.getType());
        assertTrue(ex.getMessage().contains("part size exceeded"),
                "原因必须明确指向分片大小：" + ex.getMessage());
        log.info("[judge] {}", ex.getMessage());

        assertEquals(0, service.capacityUsed());
        assertEquals(0, service.getUpload(caller, upload.uploadId()).parts().size());
        // 上传目录中不得残留 incoming-*.tmp
        long tmpLeft = blobStore.listPartFiles(upload.uploadId())
                .filter(p -> p.getFileName().toString().startsWith("incoming-")).count();
        assertEquals(0, tmpLeft);

        // 用合法大小重试成功
        byte[] ok = repeated((byte) 1, 200);
        assertDoesNotThrow(() -> service.uploadPart(caller, upload.uploadId(), 1,
                stream(ok), sha256(ok)));
    }

    @Test
    @DisplayName("在途分片累积超出总容量：QUOTA_EXCEEDED（capacity 原因），已传分片不受影响")
    void totalCapacityExceededDuringParts() {
        var caller = tenant("q");
        var upload = service.initiate(caller, "big", 8, null, null);
        byte[] chunk = repeated((byte) 2, 250);
        // 4 片 = 1000B，第 5 片将超过 1024
        for (int i = 1; i <= 4; i++) {
            service.uploadPart(caller, upload.uploadId(), i, stream(chunk), sha256(chunk));
        }
        log.info("[case-input] used={} capacity=1024 nextPart=250", service.capacityUsed());
        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.uploadPart(caller, upload.uploadId(), 5,
                        stream(chunk), sha256(chunk)));
        assertEquals(ErrorType.QUOTA_EXCEEDED, ex.getType());
        assertTrue(ex.getMessage().contains("capacity exceeded"));
        log.info("[judge] {}", ex.getMessage());

        // 已接收的 4 片保持完好，占用仍是 1000B，无泄漏无多计
        assertEquals(1000L, service.capacityUsed());
        assertEquals(4, service.getUpload(caller, upload.uploadId()).parts().size());
    }

    @Test
    @DisplayName("simple put 超过总容量：拒绝且零残留零占用")
    void simplePutOverCapacity() {
        var caller = tenant("q");
        byte[] data = repeated((byte) 3, 2000);
        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.put(caller, "huge", stream(data), null));
        assertEquals(ErrorType.QUOTA_EXCEEDED, ex.getType());
        assertEquals(0, service.capacityUsed());
        log.info("[judge] oversized simple put clean: {}", ex.getMessage());

        // tmp 目录下不得残留
        try (Stream<java.nio.file.Path> files =
                     java.nio.file.Files.list(blobStore.root().resolve("tmp"))) {
            assertEquals(0, files.count());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("分片完成时与已有对象争夺容量：失败路径回收在途占用后容量一致")
    void abortReleasesCapacityForOthers() {
        var caller = tenant("q");
        byte[] data = repeated((byte) 4, 700);
        ObjectVersion v = service.put(caller, "occupier", stream(data), sha256(data));
        assertEquals(700, service.capacityUsed());

        var upload = service.initiate(caller, "filler", 2, null, null);
        byte[] chunk = repeated((byte) 5, 200);
        service.uploadPart(caller, upload.uploadId(), 1, stream(chunk), sha256(chunk));
        // 700+200=900，再来 200 -> 在途就超限，完成更不可能；中止后容量回到 700
        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.uploadPart(caller, upload.uploadId(), 2,
                        stream(chunk), sha256(chunk)));
        assertEquals(ErrorType.QUOTA_EXCEEDED, ex.getType());
        service.abortUpload(caller, upload.uploadId());
        assertEquals(700, service.capacityUsed());

        // 释放占据者后，容量全部可用
        service.deleteAllVersions(caller, "occupier");
        assertEquals(0, service.capacityUsed());
        assertNotNull(v);
        log.info("[judge] capacity restored after abort+delete, used=0");
    }

    @Test
    @DisplayName("已存在内容的写入（纯去重命中）不受剩余容量影响，不新增占用")
    void dedupWritesDoNotConsumeCapacity() {
        var caller = tenant("q");
        byte[] data = repeated((byte) 6, 900);
        service.put(caller, "k1", stream(data), sha256(data));
        assertEquals(900, service.capacityUsed());
        // 只剩 124B，但写入相同 900B 内容必须成功（零新增占用）
        ObjectVersion dup = service.put(caller, "k2", stream(data), sha256(data));
        assertEquals(900, service.capacityUsed());
        assertEquals(2, metadata.getBlob(dup.blobKey()).refCount());
        log.info("[judge] dedup write with only 124B free succeeded, used remains=900");
    }
}
