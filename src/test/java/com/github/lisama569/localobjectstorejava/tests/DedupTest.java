package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.MetadataStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StorageConfig;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内容去重：相同内容多次写入仍可独立读取与删除，引用计数不出错。 */
class DedupTest {

    private static final Logger log = LoggerFactory.getLogger(DedupTest.class);

    @TempDir
    Path dir;

    private ContentStore contentStore;
    private ObjectStoreService svc;

    private void setup() {
        StorageConfig config = new StorageConfig();
        config.setRootDir(dir.toString());
        contentStore = new ContentStore(config);
        svc = new ObjectStoreService(config, contentStore, new MetadataStore(config));
    }

    @Test
    void sameContentStoredOnceButReadIndependently() {
        setup();
        byte[] data = TestSupport.bytes("dedup-me-", 128);
        svc.putObject("alice", "a", data, null);
        svc.putObject("alice", "b", data, null);
        svc.putObject("bob", "c", data, null);
        log.info("input: same {} bytes written to 3 keys; physical used={}",
                data.length, contentStore.usedBytes());

        assertEquals(data.length, contentStore.usedBytes(),
                "相同内容物理上只存一份");
        assertArrayEquals(data, svc.getObject("alice", "a"));
        assertArrayEquals(data, svc.getObject("alice", "b"));
        assertArrayEquals(data, svc.getObject("bob", "c"));
    }

    @Test
    void deletingOneCopyKeepsOthersReadable() {
        setup();
        byte[] data = TestSupport.bytes("shared-", 96);
        ObjectVersion va = svc.putObject("alice", "a", data, null);
        svc.putObject("alice", "b", data, null);
        log.info("input: delete version {} of key 'a', keep key 'b'", va.versionId());

        svc.deleteObject("alice", "a", va.versionId());
        log.info("decision: physical used after delete={}", contentStore.usedBytes());

        assertThrows(StoreException.class, () -> svc.getObject("alice", "a"),
                "已删除版本所在键无其他版本，应不可读");
        assertArrayEquals(data, svc.getObject("alice", "b"),
                "删除一个副本不得影响其他引用");
        assertEquals(data.length, contentStore.usedBytes(),
                "仍有引用时不得提前回收物理数据");
    }

    @Test
    void refcountDropsToZeroAndReclaims() {
        setup();
        byte[] data = TestSupport.bytes("gone-", 64);
        ObjectVersion v1 = svc.putObject("alice", "a", data, null);
        ObjectVersion v2 = svc.putObject("alice", "b", data, null);
        log.info("input: two refs to same content, delete both");

        svc.deleteObject("alice", "a", v1.versionId());
        assertEquals(data.length, contentStore.usedBytes(), "第一个引用释放后仍有一份引用");
        svc.deleteObject("alice", "b", v2.versionId());
        log.info("decision: physical used after both deletes={}", contentStore.usedBytes());

        assertEquals(0, contentStore.usedBytes(), "引用归零后必须物理回收");
        assertTrue(svc.listKeys("alice").isEmpty());
    }

    @Test
    void dedupAcrossMultipartAndSimplePut() {
        setup();
        byte[] data = TestSupport.bytes("cross-", 80);
        svc.putObject("alice", "simple", data, null);

        var up = svc.createUpload("alice", "multi");
        svc.uploadPart("alice", up.uploadId(), 1, data, null);
        svc.completeUpload("alice", up.uploadId(), null);
        log.info("input: same content via putObject and multipart; physical used={}",
                contentStore.usedBytes());

        assertEquals(data.length, contentStore.usedBytes(),
                "分片与整体写入的相同内容必须去重");
        assertArrayEquals(data, svc.getObject("alice", "simple"));
        assertArrayEquals(data, svc.getObject("alice", "multi"));
    }
}
