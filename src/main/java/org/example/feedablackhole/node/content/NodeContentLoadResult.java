package org.example.feedablackhole.node.content;

import java.util.List;

/**
 * 노드 콘텐츠를 불러온 결과. 진단이 하나라도 있으면 content는 null이다.
 */
public record NodeContentLoadResult(NodeContent content, List<ContentDiagnostic> diagnostics) {

    public boolean succeeded() {
        return content != null;
    }

}
