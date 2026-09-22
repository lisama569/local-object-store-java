package com.github.lisama569.localobjectstorejava.store.digest;

import com.github.lisama569.localobjectstorejava.store.error.ErrorType;
import com.github.lisama569.localobjectstorejava.store.error.ObjectStoreException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容摘要工具（SHA-256）。所有摘要失败都归一化为 STORAGE_FAULT，
 * 唯独字节超限以 {@link SizeLimitExceededException} 显式表达，便于上层区分原因。
 */
public final class Checksums {

    /** 8 KiB 拷贝缓冲，兼顾内存占用与系统调用次数。 */
    public static final int BUFFER_SIZE = 8 * 1024;

    private Checksums() {
    }

    public record HashResult(String sha256, long size) {
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = newDigest();
            md.update(data);
            return toHex(md.digest());
        } catch (RuntimeException e) {
            throw new ObjectStoreException(ErrorType.STORAGE_FAULT, "failed to digest data", e);
        }
    }

    public static String sha256Hex(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return copyHashing(in, OutputStream.nullOutputStream(), Long.MAX_VALUE).sha256();
        } catch (IOException e) {
            throw new ObjectStoreException(ErrorType.STORAGE_FAULT,
                    "failed to digest file: " + file.getFileName(), e);
        }
    }

    /**
     * 边拷贝边计算 SHA-256；实际字节数超过 maxBytes 时抛 {@link SizeLimitExceededException}，
     * 此时已写出的字节即为越界内容，调用方必须丢弃目标文件并回滚已计费容量。
     */
    public static HashResult copyHashing(InputStream in, OutputStream out, long maxBytes) throws IOException {
        MessageDigest md = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new SizeLimitExceededException(maxBytes, total);
            }
            md.update(buffer, 0, read);
            out.write(buffer, 0, read);
        }
        out.flush();
        return new HashResult(toHex(md.digest()), total);
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ObjectStoreException(ErrorType.STORAGE_FAULT, "SHA-256 unavailable", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
