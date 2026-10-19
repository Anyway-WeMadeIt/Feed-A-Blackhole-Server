package org.example.feedablackhole.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 비밀값(게스트 secret, 이후 리프레시 토큰)을 만들고 해시하고 비교한다.
 * 원본은 클라이언트에만 주고 DB에는 SHA-256 해시(hex 64자)만 저장한다.
 * 비밀값이 256비트 랜덤이라 bcrypt 같은 느린 해시나 salt가 필요 없다.
 */
@Component
public class SecretCodec {

    private static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 추측할 수 없는 새 비밀값(256비트, base64url 43자).
     */
    public String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 받은 비밀값이 저장된 해시와 같은지. 타이밍 공격을 피하려고 상수 시간으로 비교한다.
     */
    public boolean matches(String secret, String storedHash) {
        return MessageDigest.isEqual(
                hash(secret).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

}
