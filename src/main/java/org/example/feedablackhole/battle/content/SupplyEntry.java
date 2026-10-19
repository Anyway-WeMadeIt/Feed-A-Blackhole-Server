package org.example.feedablackhole.battle.content;

/**
 * 공급 한 건: 어떤 종류를 몇 마리.
 */
public record SupplyEntry(String enemyId, int count) {
}
