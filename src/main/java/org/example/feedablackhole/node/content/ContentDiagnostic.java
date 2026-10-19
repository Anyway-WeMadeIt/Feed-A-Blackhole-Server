package org.example.feedablackhole.node.content;

/**
 * 콘텐츠를 읽다가 찾은 문제 하나. at은 문제의 위치다(시트 칸 "NodeCost!C12", 노드 "Nodes[timer-01]" 등).
 */
public record ContentDiagnostic(String at, String message) {

    @Override
    public String toString() {
        return at == null || at.isEmpty() ? message : at + ": " + message;
    }

}
