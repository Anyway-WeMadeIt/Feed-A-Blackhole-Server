package org.example.feedablackhole.battle.rules;

import java.util.List;
import java.util.Optional;
import org.example.feedablackhole.battle.content.HqMilestone;
import org.example.feedablackhole.battle.content.SupplyEntry;

/**
 * 성장도와 산 노드로 정해지는 판 하나의 설정. 판 시작 검증(RUN_START)과 결산 검증(SETTLEMENT)이 쓴다.
 */
public interface RunSetup {

    int growthStage();

    /** 이 판이 시작하는 블랙홀 Level. */
    int startLevel();

    /** 이 판의 목표 Level(다음 이정표의 Level). 0이면 목표가 없다(마지막 이정표 뒤). */
    int goalLevel();

    /** startLevel에 닿는 누적 EXP. 결산의 exp는 이 값부터 센다. */
    long startExp();

    /** 이 판이 노리는 이정표. 마지막 이정표 뒤면 비어 있다. */
    Optional<HqMilestone> nextMilestone();

    /** 노드가 반영된 시작 제한 시간: 기본 + gainOf(timer). */
    float timeLimitSeconds();

    /** Level업 1회마다 제한 시간에 더하는 초: gainOf(growthTime). */
    float growthTimeSeconds();

    /** 시간 추가 1회의 초(노드 영향 없음). */
    float killTimeBonusSeconds();

    /** 이 판의 시작 공급(시작 수 + 보너스 → 변환 반영, 처음 나온 종류 순서, 0마리 종류 제외). */
    List<SupplyEntry> startSupply();

    Optional<KindSetup> findKind(String enemyId);

    /** 없는 종류면 IllegalArgumentException. */
    KindSetup kind(String enemyId);

    /** 이 판에서 누적 EXP가 exp일 때의 Level. startLevel부터 오르고 goalLevel(있으면)과 사다리 끝을 넘지 않는다. */
    int levelForExp(long exp);

}
