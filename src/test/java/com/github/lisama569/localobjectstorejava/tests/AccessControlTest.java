package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 访问隔离：越权读/删/列一律 NOT_FOUND，不泄露存在性；元数据与下载判定一致。 */
class AccessControlTest {

    private static final Logger log = LoggerFactory.getLogger(AccessControlTest.class);

    @TempDir
    Path dir;

    @Test
    void crossOwnerReadDeleteListAllDeniedAsNotFound() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject("alice", "secret", TestSupport.bytes("s-", 32), null);
        log.info("input: bob attempts read/delete/list on alice's object");

        StoreException readErr = assertThrows(StoreException.class,
                () -> svc.getObject("bob", "secret"));
        StoreException metaErr = assertThrows(StoreException.class,
                () -> svc.listVersions("bob", "secret"));
        StoreException delErr = assertThrows(StoreException.class,
                () -> svc.deleteObject("bob", "secret", null));
        log.info("decision: read={} metadata={} delete={}",
                readErr.code(), metaErr.code(), delErr.code());

        assertEquals(StoreException.Code.NOT_FOUND, readErr.code());
        assertEquals(StoreException.Code.NOT_FOUND, metaErr.code(),
                "元数据查询与内容下载的权限判定必须一致");
        assertEquals(StoreException.Code.NOT_FOUND, delErr.code());
        assertTrue(svc.listKeys("bob").isEmpty(), "越权列举必须为空");
        // 与“真不存在”返回相同错误码，不泄露存在性
        StoreException missingErr = assertThrows(StoreException.class,
                () -> svc.getObject("bob", "no-such-key"));
        assertEquals(readErr.code(), missingErr.code(),
                "越权与不存在的响应必须不可区分");
    }

    @Test
    void crossOwnerUploadSessionDenied() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload("alice", "k");
        log.info("input: bob attempts to use alice's uploadId={}", up.uploadId());

        StoreException e1 = assertThrows(StoreException.class,
                () -> svc.getUpload("bob", up.uploadId()));
        StoreException e2 = assertThrows(StoreException.class,
                () -> svc.uploadPart("bob", up.uploadId(), 1, TestSupport.bytes("x-", 8), null));
        StoreException e3 = assertThrows(StoreException.class,
                () -> svc.completeUpload("bob", up.uploadId(), null));
        log.info("decision: get={} part={} complete={}", e1.code(), e2.code(), e3.code());

        assertEquals(StoreException.Code.NOT_FOUND, e1.code());
        assertEquals(StoreException.Code.NOT_FOUND, e2.code());
        assertEquals(StoreException.Code.NOT_FOUND, e3.code());
    }

    @Test
    void ownerSeesOnlyOwnKeys() {
        ObjectStoreService svc = TestSupport.newService(dir);
        svc.putObject("alice", "a1", TestSupport.bytes("1-", 8), null);
        svc.putObject("bob", "b1", TestSupport.bytes("2-", 8), null);

        assertEquals(java.util.List.of("a1"), svc.listKeys("alice"));
        assertEquals(java.util.List.of("b1"), svc.listKeys("bob"));
    }
}
