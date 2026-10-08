package org.example.feedablackhole.node.content;

import java.util.List;

/**
 * 노드 트리를 불러온 결과. 진단이 하나라도 있으면 tree는 null이다.
 */
public record NodeTreeLoadResult(NodeTree tree, List<ContentDiagnostic> diagnostics) {

    public boolean succeeded() {
        return tree != null;
    }

}
