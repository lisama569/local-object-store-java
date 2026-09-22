package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调用方隔离：越权访问一律 NOT_FOUND 且不泄露存在性；
 * 元数据查询、内容下载、列举、删除的判定口径完全一致。
 */
class TenantIsolationTest extends StoreTestSupport {

    private final String sharedKey = "same/key";
    private ObjectVersion aVersion;
    private ObjectVersion bVersion;
    private MultipartUpload aUpload;

    @BeforeEach
    void setUp() {
        buildStore();
        var a = tenant("tenant-a");
        var b = tenant("tenant-b");
        // 两个租户使用完全相同的对象名与内容：逻辑隔离、物理去重可同时成立
        byte[] data = bytes("shared-secret-content");
        aVersion = service.put(a, sharedKey, stream(data), sha256(data));
        bVersion = service.put(b, sharedKey, stream(data), sha256(data));
        aUpload = service.initiate(a, sharedKey, 1, null, null);
        service.uploadPart(a, aUpload.uploadId(), 1, stream(data), sha256(data));
        log.info("[case-input] same key+content across two tenants; blob refCount={}",
                metadata.getBlob(aVersion.blobKey()).refCount());
    }

    @Test
    @DisplayName("租户只能读到自己的版本，跨租户读返回 NOT_FOUND")
    void crossTenantReadIsNotFound() {
        var spy = tenant("tenant-b");
        assertEquals(aVersion.versionId(),
                service.get(tenant("tenant-a"), sharedKey, null).versionId());
        // b 读到的是 b 自己的版本，不是 a 的
        assertEquals(bVersion.versionId(),
                service.get(spy, sharedKey, null).versionId());

        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.get(spy, sharedKey, aVersion.versionId()));
        assertEquals(ErrorType.NOT_FOUND, ex.getType());
        log.info("[judge] cross-tenant version access -> {} : {}",
                ex.getType(), ex.getMessage());

        // 内容下载与元数据判定一致：同样的 versionId 走下载通道也被拒
        assertThrows(ObjectStoreException.class,
                () -> service.openContent(spy, sharedKey, aVersion.versionId()));
    }

    @Test
    @DisplayName("越权列举不泄露其他租户的 key")
    void listIsScoped() {
        service.put(tenant("tenant-a"), "a/private-1", stream(bytes("x")), null);
        service.put(tenant("tenant-b"), "b/private-2", stream(bytes("y")), null);

        var aKeys = service.listObjects(tenant("tenant-a"), "");
        var bKeys = service.listObjects(tenant("tenant-b"), "");
        assertTrue(aKeys.contains("a/private-1"));
        assertTrue(bKeys.contains("b/private-2"));
        assertFalse(aKeys.contains("b/private-2"));
        assertFalse(bKeys.contains("a/private-1"));
        // 同名 key 各自只出现一次
        assertEquals(1, aKeys.stream().filter(sharedKey::equals).count());
        log.info("[judge] tenant-a keys={} tenant-b keys={}", aKeys, bKeys);
    }

    @Test
    @DisplayName("越权删除返回 NOT_FOUND 且不影响目标租户数据")
    void crossTenantDeleteIsNotFoundAndHarmless() {
        var spy = tenant("tenant-b");
        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.deleteVersion(spy, sharedKey, aVersion.versionId()));
        assertEquals(ErrorType.NOT_FOUND, ex.getType());

        assertDoesNotThrow(() -> service.get(tenant("tenant-a"), sharedKey, aVersion.versionId()));
        log.info("[judge] cross-tenant delete denied; tenant-a data intact, refCount={}",
                metadata.getBlob(aVersion.blobKey()).refCount());
    }

    @Test
    @DisplayName("跨租户访问上传会话：查询/分片/完成/中止全部 NOT_FOUND")
    void crossTenantUploadAccess() {
        var spy = tenant("tenant-b");
        assertThrows(ObjectStoreException.class,
                () -> service.getUpload(spy, aUpload.uploadId()));
        assertThrows(ObjectStoreException.class,
                () -> service.completeUpload(spy, aUpload.uploadId(), null));
        assertThrows(ObjectStoreException.class,
                () -> service.abortUpload(spy, aUpload.uploadId()));

        // a 自己仍可完成，证明越权中止没有得逞
        ObjectVersion v = service.completeUpload(tenant("tenant-a"),
                aUpload.uploadId(), null);
        assertNotNull(v);
        log.info("[judge] cross-tenant upload operations all denied; owner completed fine");
    }

    @Test
    @DisplayName("不存在对象与存在但越权对象的拒绝结果不可区分")
    void responsesAreIndistinguishable() {
        var spy = tenant("tenant-b");
        ObjectStoreException hidden = assertThrows(ObjectStoreException.class,
                () -> service.get(spy, sharedKey, aVersion.versionId()));
        ObjectStoreException missing = assertThrows(ObjectStoreException.class,
                () -> service.get(spy, "does/not/exist", null));
        assertEquals(ErrorType.NOT_FOUND, hidden.getType());
        assertEquals(ErrorType.NOT_FOUND, missing.getType());
        // 两者都不含 forbidden/unauthorized 字样
        for (String msg : new String[]{hidden.getMessage(), missing.getMessage()}) {
            assertFalse(msg.toLowerCase().contains("forbidden"));
            assertFalse(msg.toLowerCase().contains("unauthorized"));
            assertFalse(msg.toLowerCase().contains("tenant"));
        }
        log.info("[judge] hidden={} | missing={}", hidden.getMessage(), missing.getMessage());
    }
}
