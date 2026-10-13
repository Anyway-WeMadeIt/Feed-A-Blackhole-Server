package org.example.feedablackhole.sync.service;

import tools.jackson.databind.JsonNode;

/**
 * 요청의 로그 하나. seq만 읽었고 나머지는 아직 검사하지 않았다.
 *
 * @param raw 받은 로그 객체 그대로(sync_log.raw_json에 남긴다)
 */
public record IncomingLog(long seq, JsonNode raw) {
}
