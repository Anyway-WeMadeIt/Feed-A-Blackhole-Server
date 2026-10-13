package org.example.feedablackhole.sync.service;

/**
 * 서버 콘텐츠의 버전. 로그의 contentVersion이 이것과 다르면 CONTENT_VERSION_MISMATCH로 거부한다(docs/server-sync-contracts.md 3.6).
 */
public interface ServerContentVersion {

    String current();

}
