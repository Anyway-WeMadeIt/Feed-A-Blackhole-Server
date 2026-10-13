package org.example.feedablackhole.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 콘텐츠 묶음의 버전. 파일을 파싱하지 않고 **파일 바이트 그대로** 계산한다(docs/server-sync-contracts.md 1.3).
 * 클라이언트(C#)도 같은 방법으로 계산하므로, 같은 파일이면 언어와 관계없이 같은 버전이 나온다.
 *
 * <p>계산: 목차의 순서대로 각 파일의 {@code 경로(UTF-8) + 0x00 + 파일 바이트 + 0x00}을 이어 붙여 SHA-256을 구하고,
 * 소문자 16진수의 앞 16자를 버전으로 쓴다.
 */
public final class ContentVersion {

    private static final int HEX_LENGTH = 16;

    private ContentVersion() {
    }

    /** 묶음 파일 하나: content/ 기준 상대 경로와 그 바이트. */
    public record ContentFile(String path, byte[] bytes) {
    }

    public static String of(List<ContentFile> files) {
        MessageDigest digest = sha256();
        for (ContentFile file : files) {
            digest.update(file.path().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.bytes());
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, HEX_LENGTH);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", e);
        }
    }

}
