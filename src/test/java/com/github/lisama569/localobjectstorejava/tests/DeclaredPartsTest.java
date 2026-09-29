package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.ContentStore;
import com.github.lisama569.localobjectstorejava.store.ObjectStoreService;
import com.github.lisama569.localobjectstorejava.store.StoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 声明分片总数：缺片拒绝、缺口报告、与校验失败区分、老流程兼容。 */
class DeclaredPartsTest {

    private static final Logger log = LoggerFactory.getLogger(DeclaredPartsTest.class);
    private static final String OWNER = "alice";

    @TempDir
    Path dir;

    @Test
    void declaredAllPartsCompleteSucceeds() {
        ObjectStoreService svc = TestSupport.newService(dir);
        byte[] p1 = TestSupport.bytes("d-p1-", 40);
        byte[] p2 = TestSupport.bytes("d-p2-", 40);
        byte[] p3 = TestSupport.bytes("d-p3-", 40);
        log.info("input: declaredTotalParts=3, parts 1,2,3 all present");

        MultipartUpload up = svc.createUpload(OWNER, "dk1", 3);
        assertEquals(3, up.expectedTotalParts());
        svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        svc.uploadPart(OWNER, up.uploadId(), 2, p2, null);
        svc.uploadPart(OWNER, up.uploadId(), 3, p3, null);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(p1);
        expected.writeBytes(p2);
        expected.writeBytes(p3);
        ObjectVersion v = svc.completeUpload(OWNER, up.uploadId(),
                ContentStore.sha256Hex(expected.toByteArray()));
        log.info("decision: complete accepted, versionId={} size={}", v.versionId(), v.size());

        assertArrayEquals(expected.toByteArray(), svc.getObject(OWNER, "dk1"));
    }

    @Test
    void declaredWithGapRejectedAndReportsMissingNumbers() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "dk2", 4);
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("a-", 10), null);
        svc.uploadPart(OWNER, up.uploadId(), 3, TestSupport.bytes("c-", 10), null);
        svc.uploadPart(OWNER, up.uploadId(), 4, TestSupport.bytes("d-", 10), null);
        log.info("input: declaredTotalParts=4, present=[1,3,4], expecting gap [2]");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up.uploadId(), null));
        log.info("decision: code={} message={}", e.code(), e.getMessage());

        assertEquals(StoreException.Code.MISSING_PARTS, e.code(),
                "缺片必须用专门的 MISSING_PARTS，区别于校验失败/状态非法");
        assertEquals(2, extractSingleMissing(e), "必须明确告知缺的是 2 号分片");
        assertEquals(MultipartUpload.State.ACTIVE, svc.getUpload(OWNER, up.uploadId()).state(),
                "缺片拒绝不应关闭会话，补齐后可重试");
    }

    @Test
    void declaredMissingTailReportedAsRange() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "dk3", 3);
        svc.uploadPart(OWNER, up.uploadId(), 1, TestSupport.bytes("a-", 10), null);
        log.info("input: declaredTotalParts=3, present=[1], expecting gap [2,3]");

        StoreException e = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up.uploadId(), null));
        log.info("decision: code={} message={}", e.code(), e.getMessage());

        assertEquals(StoreException.Code.MISSING_PARTS, e.code());
        assertEquals("[2, 3]", extractMissingList(e));
    }

    @Test
    void missingPartsDistinctFromChecksumMismatch() {
        ObjectStoreService svc = TestSupport.newService(dir);
        // 场景 A：分片到齐但整体哈希不符 → CHECKSUM_MISMATCH
        MultipartUpload up1 = svc.createUpload(OWNER, "dk4a", 2);
        svc.uploadPart(OWNER, up1.uploadId(), 1, TestSupport.bytes("a-", 10), null);
        svc.uploadPart(OWNER, up1.uploadId(), 2, TestSupport.bytes("b-", 10), null);
        String wrongSha = ContentStore.sha256Hex(TestSupport.bytes("zz-", 20));
        StoreException checksum = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up1.uploadId(), wrongSha));
        log.info("input: all present but wrong sha; decision code={}", checksum.code());
        assertEquals(StoreException.Code.CHECKSUM_MISMATCH, checksum.code());

        // 场景 B：有缺口 → MISSING_PARTS，即便同时带了错误的期望哈希，缺口判定也优先
        MultipartUpload up2 = svc.createUpload(OWNER, "dk4b", 2);
        svc.uploadPart(OWNER, up2.uploadId(), 1, TestSupport.bytes("a-", 10), null);
        StoreException missing = assertThrows(StoreException.class, () ->
                svc.completeUpload(OWNER, up2.uploadId(), wrongSha));
        log.info("input: gap + wrong sha; decision code={}", missing.code());
        assertEquals(StoreException.Code.MISSING_PARTS, missing.code());
    }

    @Test
    void partBeyondDeclaredTotalRejected() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "dk5", 2);
        log.info("input: declaredTotalParts=2, client tries partNumber=3");
        StoreException e = assertThrows(StoreException.class, () ->
                svc.uploadPart(OWNER, up.uploadId(), 3, TestSupport.bytes("x-", 5), null));
        log.info("decision: code={} message={}", e.code(), e.getMessage());
        assertEquals(StoreException.Code.BAD_REQUEST, e.code());
        assertEquals(0, svc.incompleteUploadBytesOf(OWNER), "被拒分片不得计入占用");
    }

    @Test
    void undeclaredUploadKeepsLegacyBehavior() {
        ObjectStoreService svc = TestSupport.newService(dir);
        MultipartUpload up = svc.createUpload(OWNER, "dk6"); // 老入口，无声明
        log.info("input: legacy createUpload without declaration");
        assertNull(up.expectedTotalParts(), "未声明时 expectedTotalParts 必须为 null");

        // 老行为：只传到 3 号中的 1、3（带缺口）也允许完成，按实际到齐分片拼接
        byte[] p1 = TestSupport.bytes("legacy-1-", 20);
        byte[] p3 = TestSupport.bytes("legacy-3-", 20);
        svc.uploadPart(OWNER, up.uploadId(), 1, p1, null);
        svc.uploadPart(OWNER, up.uploadId(), 3, p3, null);
        ObjectVersion v = svc.completeUpload(OWNER, up.uploadId(), null);
        log.info("decision: legacy complete with gap still works, versionId={}", v.versionId());

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(p1);
        expected.writeBytes(p3);
        assertArrayEquals(expected.toByteArray(), svc.getObject(OWNER, "dk6"));
    }

    @Test
    void invalidDeclaredTotalRejectedAtCreate() {
        ObjectStoreService svc = TestSupport.newService(dir);
        log.info("input: declaredTotalParts=0 (out of range)");
        StoreException e = assertThrows(StoreException.class, () ->
                svc.createUpload(OWNER, "dk7", 0));
        assertEquals(StoreException.Code.BAD_REQUEST, e.code());
        log.info("decision: code={} message={}", e.code(), e.getMessage());
    }

    private static int extractSingleMissing(StoreException e) {
        String list = extractMissingList(e);
        list = list.substring(1, list.length() - 1).trim();
        return Integer.parseInt(list);
    }

    private static String extractMissingList(StoreException e) {
        int idx = e.getMessage().indexOf("缺少分片号=");
        return e.getMessage().substring(idx + "缺少分片号=".length()).trim();
    }
}
