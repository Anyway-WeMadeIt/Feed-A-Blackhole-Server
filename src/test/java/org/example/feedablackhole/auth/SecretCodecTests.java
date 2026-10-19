package org.example.feedablackhole.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.example.feedablackhole.auth.service.SecretCodec;
import org.junit.jupiter.api.Test;

class SecretCodecTests {

    private final SecretCodec codec = new SecretCodec();

    @Test
    void newSecretIsUrlSafeAndFortyThreeCharsLong() {
        String secret = codec.newSecret();

        // 32바이트를 패딩 없이 base64url로 인코딩하면 43자
        assertThat(secret).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void newSecretIsDifferentEveryTime() {
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            secrets.add(codec.newSecret());
        }

        assertThat(secrets).hasSize(1000);
    }

    @Test
    void hashIsSha256Hex() {
        // SHA-256("abc")의 알려진 값
        assertThat(codec.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void matchesOnlyTheSameSecret() {
        String secret = codec.newSecret();
        String storedHash = codec.hash(secret);

        assertThat(codec.matches(secret, storedHash)).isTrue();
        assertThat(codec.matches(codec.newSecret(), storedHash)).isFalse();
        assertThat(codec.matches(secret, "0".repeat(64))).isFalse();
    }

}
