package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 声明分片总数：完成时必须 1..N 到齐，缺口以可区分的 MISSING_PARTS 拒绝。 */
class DeclaredPartsTest {

    private static final Logger log = LoggerFactory.getLogger(DeclaredPartsTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void completeWithGapIsRejectedAsMissingPartsAndReportsGapNumbers() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k1", 3);
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("a-", 10), null);
        svc.uploadPart(OWNER, up.uploadId(), 3, TestSupport.bytes("c-", 10), null);
        log.info("input: expectedParts=3, present=[1,3], attempt complete");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up.uploadId(), null));
        log.info("decision: code={} details={} message={}", e.code(), e.details(), e.getMessage());

        assertEquals(StoreException.Code.MISSING_PARTS, e.code(), "缺片必须与校验失败/状态错误区分");
        assertEquals(List.of(2), e.details(), "必须明确告知缺失的分片号");
        assertEquals(MultipartUpload.State.ACTIVE, svc.getUpload(OWNER, up.uploadId()).state(),
                "缺片拒绝后会话仍须 ACTIVE，可补传后重试");
        assertThrows(StoreException.class, () -> svc.getObject(OWNER, "k1"),
                "缺片完成不得产生对象");
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeWithMultipleGapsReportsAllMissingInOrder() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k2", 5);
        svc.uploadPart(OWNER, up.uploadId(), 2, TestSupport.bytes("b-", 8), null);
        svc.uploadPart(OWNER, up.uploadId(), 4, TestSupport.bytes("d-", 8), null);
        log.info("input: expectedParts=5, present=[2,4]");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up.uploadId(), null));
        log.info("decision: code={} details={}", e.code(), e.details());

        assertEquals(StoreException.Code.MISSING_PARTS, e.code());
        assertEquals(List.of(1, 3, 5), e.details(), "多个缺口必须按序号全部返回");

        // 补齐后可完成
        byte[] p1 = TestSupport.bytes("a-", 8);
        byte[] p3 = TestSupport.bytes("c-", 8);
        byte[] p5 = TestSupport.bytes("e-", 8);
        svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        svc.uploadPart(OWNER, up.uploadId(), 3, p3, null);
        svc.uploadPart(OWNER, up.uploadId(), 5, p5, null);
        ObjectVersion v = svc.completeUpload(OWNER, up.uploadId(), null);
        log.info("decision: all parts present, completed versionId={} size={}", v.versionId(), v.size());
        assertEquals(40, v.size());
    }

    @Test
    void partBeyondDeclaredCountRejected() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k3", 2);
        log.info("input: expectedParts=2, attempt to upload part 3");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 3, TestSupport.bytes("x-", 5), null));
        log.info("decision: code={}", e.code());
        assertEquals(StoreException.Code.BAD_REQUEST, e.code());
        assertEquals(0, svc.getUpload(OWNER, up.uploadId()).parts().size());
    }

    @Test
    void invalidExpectedPartsRejectedAtCreation() {
        ObjectStoreService svc = TestSupport.newService(dir);
        log.info("input: expectedParts=0 at creation");
        StoreException e = assertThrows(StoreException.class, () -> svc.createUpload(OWNER, "k4", 0));
        assertEquals(StoreException.Code.BAD_REQUEST, e.code());
    }

    @Test
    void undeclaredUploadKeepsLegacyBehavior() {
        ObjectStoreService svc = TestSupport.newService(dir);
        // 老流程：不传 expectedParts，任意非空分片集合即可完成
        MultipartUpload up = svc.createUpload(OWNER, "k5");
        assertNull(up.expectedParts(), "未声明分片数时 expectedParts 必须为 null");
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("legacy-", 12), null);
        svc.uploadPart(OWNER, up.uploadId(), 4, TestSupport.bytes("jump-", 12), null);
        log.info("input: undeclared upload with parts [1,4], complete must still succeed");

        ObjectVersion v = svc.completeUpload(OWNER, up.uploadId(), null);
        byte[] got = svc.getObject(OWNER, "k5");
        log.info("decision: legacy complete succeeded versionId={} size={}", v.versionId(), got.length);
        assertEquals(24, got.length, "未声明分片数必须保持老行为，不强制连续");
    }

    @Test
    void declaredPartsAssembleInNumberOrderLikeLegacy() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "k6", 3);
        byte[] p1 = TestSupport.bytes("one-", 10);
        byte[] p2 = TestSupport.bytes("two-", 10);
        byte[] p3 = TestSupport.bytes("three-", 10);
        svc.uploadPart(OWNER, up.uploadId(), 3, p3, null);
        svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        svc.uploadPart(OWNER, up.uploadId(), 2, p2, null);
        ObjectVersion v = svc.completeUpload(OWNER, up.uploadId(), null);
        byte[] expected = new byte[30];
        System.arraycopy(p1, 0, expected, 0, 10);
        System.arraycopy(p2, 0, expected, 10, 10);
        System.arraycopy(p3, 0, expected, 20, 10);
        assertArrayEquals(expected, svc.getObject(OWNER, "k6"));
        log.info("decision: declared out-of-order assembly matches ordered content, versionId={}",
                v.versionId());
    }
}
