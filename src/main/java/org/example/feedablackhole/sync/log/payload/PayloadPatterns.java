package org.example.feedablackhole.sync.log.payload;

/**
 * payload 필드의 글자 형식.
 */
public final class PayloadPatterns {

    /** 소문자 UUID(8-4-4-4-12). */
    public static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private PayloadPatterns() {
    }

}
