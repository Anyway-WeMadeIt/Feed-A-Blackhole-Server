package org.example.feedablackhole.node.content;

import java.util.List;

/**
 * 노드 하나의 배치: 격자 칸(x, y), 시작 노드인가, 선으로 이어진 노드의 ID. 선은 방향이 없어 한쪽 노드에만 적어도 된다.
 * 검사 전 값이며(NodeTreeLoader가 규칙을 본다), 노드 도구가 내보낸 layout.json의 한 항목이다.
 */
public record NodePlacement(String id, boolean start, int x, int y, List<String> links) {

    public NodePlacement {
        links = List.copyOf(links);
    }

}
