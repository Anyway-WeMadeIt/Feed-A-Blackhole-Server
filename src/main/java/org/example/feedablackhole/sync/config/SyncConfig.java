package org.example.feedablackhole.sync.config;

import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.sync.service.ServerContentVersion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 동기화가 쓰는 콘텐츠 버전: 서버가 시작할 때 읽어 검증한 콘텐츠 묶음 목차의 contentVersion(계약 1.3).
 * 클라이언트도 같은 묶음 바이트로 같은 값을 계산해 로그에 싣는다. 다르면 CONTENT_VERSION_MISMATCH다.
 */
@Configuration
public class SyncConfig {

    @Bean
    ServerContentVersion serverContentVersion(ContentManifest manifest) {
        String version = manifest.contentVersion();
        return () -> version;
    }

}
