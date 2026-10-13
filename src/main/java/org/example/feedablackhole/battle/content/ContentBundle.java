package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.NodeTree;

/**
 * 시작할 때 한 번 읽어 검증한 콘텐츠 묶음. 불러온 뒤에는 바뀌지 않는다.
 *
 * @param manifest 목차. contentVersion은 파일 바이트로 다시 계산해 확인한 값이다
 * @param nodeTree 노드 트리(수치 정의, 노드, 배치)
 * @param battle   전투 콘텐츠(규칙, 성장, 적 종류, 시작 공급)
 */
public record ContentBundle(ContentManifest manifest, NodeTree nodeTree, BattleContent battle) {
}
