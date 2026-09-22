package com.github.lisama569.localobjectstorejava.tests;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;
import com.github.lisama569.localobjectstorejava.store.model.MultipartUpload;
import com.github.lisama569.localobjectstorejava.store.model.ObjectVersion;
import com.github.lisama569.localobjectstorejava.store.model.PartReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分片上传：乱序提交、重复提交（幂等/冲突）、跨会话（重启）续传、完成重试。
 */
class MultipartResumeTest extends StoreTestSupport {

    private byte[][] parts;
    private byte[] fullContent;
    private String fullSha;

    @BeforeEach
    void setUp() {
        buildStore();
        parts = new byte[][]{
                bytes("hello-"),
                bytes("object-"),
                bytes("store!!"),
        };
        fullContent = concat(parts);
        fullSha = sha256(fullContent);
        log.info("[case-input] parts={} partSizes={},{},{} fullSize={} fullSha={}",
                parts.length, parts[0].length, parts[1].length, parts[2].length,
                fullContent.length, fullSha);
    }

    @Test
    @DisplayName("分片乱序提交 + 重启后续传 + 完成，内容与摘要一致")
    void outOfOrderThenRestartThenComplete() {
        var caller = tenant("team-a");
        MultipartUpload upload = service.initiate(caller, "logs/day1", 3, fullSha, null);
        String uploadId = upload.uploadId();
        log.info("[step] initiated uploadId={}", uploadId);

        // 乱序：先传 3 号，再传 1 号，此时“中断”（模拟进程崩溃后重启）
        PartReceipt r3 = service.uploadPart(caller, uploadId, 3, stream(parts[2]), sha256(parts[2]));
        PartReceipt r1 = service.uploadPart(caller, uploadId, 1, stream(parts[0]), sha256(parts[0]));
        log.info("[judge] before restart parts={} uploadedBytes={} quotaUsed={}",
                service.getUpload(caller, uploadId).parts().keySet(),
                service.getUpload(caller, uploadId).uploadedBytes(), service.capacityUsed());

        restart();

        // 跨会话续传：已传分片仍在、容量未丢；重复提交 1 号（相同内容）必须幂等且不重复计费
        MultipartUpload resumed = service.getUpload(caller, uploadId);
        assertEquals(2, resumed.parts().size(), "重启后已接收分片不得丢失");
        assertEquals(parts[0].length + parts[2].length, resumed.uploadedBytes());
        long usedAfterRestart = service.capacityUsed();

        PartReceipt r1Again = service.uploadPart(caller, uploadId, 1,
                stream(parts[0]), sha256(parts[0]));
        assertEquals(r1.etag(), r1Again.etag());
        assertEquals(usedAfterRestart, service.capacityUsed(),
                "重复分片（相同内容）不得重复计入容量");
        log.info("[judge] duplicate part idempotent: used unchanged={}", service.capacityUsed());

        // 补齐最后一片
        PartReceipt r2 = service.uploadPart(caller, uploadId, 2, stream(parts[1]), sha256(parts[1]));
        assertNotNull(r2);

        ObjectVersion version = service.completeUpload(caller, uploadId, fullSha);
        log.info("[judge] completed version={} blob={} size={}",
                version.versionId(), version.blobKey(), version.size());

        // 完成后重复 complete：必须幂等返回同一版本，不产生新版本/重复计费
        ObjectVersion replay = service.completeUpload(caller, uploadId, fullSha);
        assertEquals(version.versionId(), replay.versionId());

        byte[] actual = download(caller, "logs/day1", null);
        assertArrayEquals(fullContent, actual);
        assertEquals(fullSha, service.get(caller, "logs/day1", null).contentSha256());

        // 完成后在途容量被置换为对象容量（去重前 = 一份完整内容）
        assertEquals(fullContent.length, service.capacityUsed());
        log.info("[judge] content matches, final used={}", service.capacityUsed());
    }

    @Test
    @DisplayName("同号分片提交不同内容：拒绝且判定为 CONFLICT，原内容不被覆盖")
    void samePartNumberDifferentContentIsConflict() {
        var caller = tenant("team-a");
        MultipartUpload upload = service.initiate(caller, "k", 2, null, null);
        String uploadId = upload.uploadId();

        service.uploadPart(caller, uploadId, 1, stream(parts[0]), sha256(parts[0]));
        byte[] evil = bytes("EVIL!!!");
        long usedBefore = service.capacityUsed();
        log.info("[case-input] conflicting part1 expectedSha={} submittedSha={}",
                sha256(parts[0]), sha256(evil));

        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.uploadPart(caller, uploadId, 1, stream(evil), sha256(evil)));
        assertEquals(ErrorType.CONFLICT, ex.getType());
        log.info("[judge] rejected as {} : {}", ex.getType(), ex.getMessage());

        // 冲突内容未落账：容量不变，原分片仍可完成
        assertEquals(usedBefore, service.capacityUsed());
        service.uploadPart(caller, uploadId, 2, stream(parts[1]), sha256(parts[1]));
        ObjectVersion version = service.completeUpload(caller, uploadId, null);
        assertEquals(sha256(concat(parts[0], parts[1])), version.contentSha256());
    }

    @Test
    @DisplayName("缺少分片时完成：PARTS_INCOMPLETE 且原因可区分，会话仍可续传")
    void completeWithMissingPartsIsRejectableAndResumable() {
        var caller = tenant("team-a");
        MultipartUpload upload = service.initiate(caller, "k", 3, null, null);
        service.uploadPart(caller, upload.uploadId(), 1, stream(parts[0]), sha256(parts[0]));

        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.completeUpload(caller, upload.uploadId(), null));
        assertEquals(ErrorType.PARTS_INCOMPLETE, ex.getType());
        assertTrue(ex.getMessage().contains("missing parts"));
        log.info("[judge] incomplete rejected: {}", ex.getMessage());

        // 未完成不是终态：补齐后可以正常完成，不留半成品
        service.uploadPart(caller, upload.uploadId(), 2, stream(parts[1]), sha256(parts[1]));
        service.uploadPart(caller, upload.uploadId(), 3, stream(parts[2]), sha256(parts[2]));
        ObjectVersion version = service.completeUpload(caller, upload.uploadId(), fullSha);
        assertEquals(fullSha, version.contentSha256());
    }

    @Test
    @DisplayName("abort 释放全部在途分片与容量，之后无法再提交分片")
    void abortReleasesPartsAndIsIdempotent() {
        var caller = tenant("team-a");
        MultipartUpload upload = service.initiate(caller, "k", 3, null, null);
        String id = upload.uploadId();
        service.uploadPart(caller, id, 1, stream(parts[0]), sha256(parts[0]));
        service.uploadPart(caller, id, 2, stream(parts[1]), sha256(parts[1]));
        assertTrue(service.capacityUsed() > 0);

        service.abortUpload(caller, id);
        assertEquals(0, service.capacityUsed(), "中止后在途容量必须释放");
        log.info("[judge] aborted, used={}", service.capacityUsed());

        // 重复 abort 幂等；再传分片必须被拒绝
        assertDoesNotThrow(() -> service.abortUpload(caller, id));
        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> service.uploadPart(caller, id, 3, stream(parts[2]), sha256(parts[2])));
        assertEquals(ErrorType.CONFLICT, ex.getType());
    }

    @Test
    @DisplayName("重启后完成会话：整对象摘要校验通过，元数据不错乱")
    void completeAfterRestart() {
        var caller = tenant("team-a");
        List<String> etags = new ArrayList<>();
        MultipartUpload upload = service.initiate(caller, "restart/obj", 3, fullSha, null);
        for (int i = 1; i <= 3; i++) {
            etags.add(service.uploadPart(caller, upload.uploadId(), i,
                    stream(parts[i - 1]), sha256(parts[i - 1])).etag());
        }
        restart();
        MultipartUpload resumed = service.getUpload(caller, upload.uploadId());
        for (int i = 1; i <= 3; i++) {
            assertEquals(etags.get(i - 1), resumed.parts().get(i).etag());
        }
        ObjectVersion version = service.completeUpload(caller, upload.uploadId(), fullSha);
        assertArrayEquals(fullContent, download(caller, "restart/obj", version.versionId()));
    }
}
