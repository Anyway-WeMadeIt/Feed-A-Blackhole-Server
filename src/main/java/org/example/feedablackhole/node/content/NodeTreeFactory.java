package org.example.feedablackhole.node.content;

import java.util.ArrayList;
import java.util.List;

/**
 * 콘텐츠 파일 → 검증된 NodeTree. 문제는 단계마다 한 번에 모아서 ContentLoadException으로 알린다.
 * <ol>
 * <li>파일 형식(시트 머리칸·글자·숫자, 배치 JSON)
 * <li>콘텐츠 규칙(NodeContentLoader)
 * <li>트리 규칙(NodeTreeLoader: 배치와 콘텐츠의 짝, 선, 연결)
 * </ol>
 */
public final class NodeTreeFactory {

    private NodeTreeFactory() {
    }

    public static NodeTree load(NodeContentFiles files) {
        List<ContentDiagnostic> format = new ArrayList<>();
        NodeContentData data = NodeContentCsvReader.read(
                files.statsCsv(), files.nodesCsv(), files.costCsv(), files.effectsCsv(), format);
        List<NodePlacement> placements = NodeLayoutReader.read(files.layoutJson(), format);
        if (!format.isEmpty()) {
            throw new ContentLoadException("노드 콘텐츠", format);
        }

        NodeContentLoadResult content = NodeContentLoader.load(data);
        if (!content.succeeded()) {
            throw new ContentLoadException("노드 콘텐츠", content.diagnostics());
        }

        NodeTreeLoadResult tree = NodeTreeLoader.load(placements, content.content());
        if (!tree.succeeded()) {
            throw new ContentLoadException("노드 트리", tree.diagnostics());
        }

        return tree.tree();
    }

}
