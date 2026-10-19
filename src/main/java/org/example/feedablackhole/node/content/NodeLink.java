package org.example.feedablackhole.node.content;

/**
 * 노드 사이의 선 하나. 선은 방향이 없고, 배치 순서가 앞선 노드가 a다.
 */
public record NodeLink(String a, String b) {
}
