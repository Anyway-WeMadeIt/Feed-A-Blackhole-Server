package org.example.feedablackhole.battlesummary;

// 전투 요약을 받은 결과. 컨트롤러가 HTTP 상태로 바꾼다(CREATED → 201, ALREADY_RECEIVED → 200).
public enum ReceiveResult {
    CREATED,
    ALREADY_RECEIVED
}
