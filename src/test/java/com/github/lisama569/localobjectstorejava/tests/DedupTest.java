package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内容去重：相同内容多版本/多对象共享 blob，独立可读可删；
 * 引用计数准确、容量只计一份、最后一个引用消失才回收。
 */
class DedupTest extends StoreTestSupport {

    private byte[] content;

    @BeforeEach
    void setUp() {
        buildStore();
        content = repeated((byte) 42, 4096);
    }

    @Test
    @DisplayName("相同内容三次写入：共享 blob，容量只计一份，各版本/对象独立可读")
    void sameContentSharesBlobAndReadsIndependently() {
        var a = tenant("tenant-a");
        log.info("[case-input] contentSize={} sha={}", content.length, sha256(content));

        ObjectVersion v1 = service.put(a, "key-one", stream(content), sha256(content));
        ObjectVersion v2 = service.put(a, "key-one", stream(content), sha256(content));
        ObjectVersion v3 = service.put(a, "key-two", stream(content), sha256(content));

        assertEquals(v1.blobKey(), v2.blobKey());
        assertEquals(v1.blobKey(), v3.blobKey());
        assertEquals(3, metadata.getBlob(v1.blobKey()).refCount(),
                "引用计数必须等于引用该 blob 的版本数");
        assertEquals(content.length, service.capacityUsed(),
                "去重后物理容量只计一份");
        log.info("[judge] 3 writes -> blob={} refCount=3 used={}", v1.blobKey(),
                service.capacityUsed());

        // 三个版本/两个对象都独立可读
        assertArrayEquals(content, download(a, "key-one", v1.versionId()));
        assertArrayEquals(content, download(a, "key-one", v2.versionId()));
        assertArrayEquals(content, download(a, "key-two", null));

        // 删除一个版本不影响其他版本
        service.deleteVersion(a, "key-one", v1.versionId());
        assertEquals(2, metadata.getBlob(v1.blobKey()).refCount());
        assertArrayEquals(content, download(a, "key-one", v2.versionId()));
        assertArrayEquals(content, download(a, "key-two", null));
        assertEquals(content.length, service.capacityUsed(), "仍有引用时容量不释放");
        log.info("[judge] after one delete refCount=2, content still readable");

        // 删除 key-two（单版本对象）
        service.deleteAllVersions(a, "key-two");
        assertEquals(1, metadata.getBlob(v1.blobKey()).refCount());
        assertArrayEquals(content, download(a, "key-one", v2.versionId()));

        // 最后引用消失：blob 被回收，容量归零，元数据一并清除
        service.deleteVersion(a, "key-one", v2.versionId());
        assertNull(metadata.getBlob(v1.blobKey()));
        assertFalse(blobStore.blobExists(v1.blobKey()));
        assertEquals(0, service.capacityUsed());
        log.info("[judge] last ref removed -> blob gc'd, used=0");
    }

    @Test
    @DisplayName("分片上传与一次性写入相同内容：共享 blob，删除任一方后另一方可读")
    void multipartAndSimplePutDeduplicate() {
        var a = tenant("tenant-a");
        byte[] p1 = bytes("the quick ");
        byte[] p2 = bytes("brown fox");
        byte[] full = concat(p1, p2);

        var upload = service.initiate(a, "multi/obj", 2, sha256(full), null);
        service.uploadPart(a, upload.uploadId(), 1, stream(p1), sha256(p1));
        service.uploadPart(a, upload.uploadId(), 2, stream(p2), sha256(p2));
        ObjectVersion mv = service.completeUpload(a, upload.uploadId(), sha256(full));

        ObjectVersion sv = service.put(a, "simple/obj", stream(full), sha256(full));
        assertEquals(mv.blobKey(), sv.blobKey());
        assertEquals(full.length, service.capacityUsed());
        assertEquals(2, metadata.getBlob(mv.blobKey()).refCount());
        log.info("[judge] multipart+simple dedup: refCount=2 used={}", service.capacityUsed());

        service.deleteAllVersions(a, "simple/obj");
        assertArrayEquals(full, download(a, "multi/obj", null));
        assertEquals(1, metadata.getBlob(mv.blobKey()).refCount());
    }

    @Test
    @DisplayName("删除某 key 的全部版本只回收该 key 自身的引用，不误伤共享 blob 的其他对象")
    void deleteAllVersionsScopedToOneKey() {
        var a = tenant("tenant-a");
        ObjectVersion k1 = service.put(a, "shared/1", stream(content), sha256(content));
        service.put(a, "shared/2", stream(content), sha256(content));
        service.put(a, "shared/2", stream(content), sha256(content));

        int removed = service.deleteAllVersions(a, "shared/2");
        assertEquals(2, removed);
        assertEquals(1, metadata.getBlob(k1.blobKey()).refCount(),
                "shared/2 的两个版本被删，但 shared/1 的引用必须保留");
        assertArrayEquals(content, download(a, "shared/1", null));
        log.info("[judge] scoped delete keeps other-key reference intact");
    }
}
