package org.example.feedablackhole.sync.handler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.example.feedablackhole.battle.content.GoldenTrait;
import org.example.feedablackhole.battle.rules.KindSetup;
import org.example.feedablackhole.battle.rules.RunSetup;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.SettlementPayload;
import org.example.feedablackhole.sync.log.payload.SettlementPayload.KillItem;

/**
 * 결산 로그의 값들이 서로 일관적인지 확인한다(계획서 6.2의 S1~S9).
 * 상태를 갖지 않고 진행도 저장소도 모른다. 판 설정과 열린 판, 지금 Gold만 받아 첫 위반 하나를 돌려준다.
 *
 * <p>검사 순서는 계약 3.6의 코드 순서(INVALID_KILLS, EXP, GOLD, TIME, RESULT)이고, 한 코드 안에서는 S 번호 순서다.
 */
public final class SettlementValidator {

    // S8 허용 오차: 클라이언트의 제한 시간은 float을 여러 번 더해 만들어져 정확한 합과 조금 다를 수 있다.
    // 판은 제한 시간을 넘겨 진행되지 않으므로(LimitStep) 프레임 단위의 초과는 허용하지 않는다.
    static final double TIME_TOLERANCE_SECONDS = 0.01;
    static final double TIME_TOLERANCE_RATIO = 1e-4;

    /** 검사에 걸린 이유. */
    public record Violation(RejectCode code, String message) {
    }

    /**
     * S1: 열린 판이 있고 runId가 같다. 이것이 통과해야 열린 판의 성장도로 판 설정을 만들 수 있으므로,
     * 나머지 검사({@link #validate})와 나눠 먼저 부른다.
     *
     * @return 위반이 없으면 비어 있다
     */
    public Optional<Violation> checkRunOpen(SettlementPayload payload, SyncContext context) {
        Optional<OpenRun> openRun = context.openRun();
        if (openRun.isEmpty()) {
            return violation(RejectCode.RUN_NOT_OPEN, "열린 판이 없다.");
        }
        if (!openRun.get().runId().equals(payload.runId())) {
            return violation(RejectCode.RUN_NOT_OPEN, "열린 판의 runId와 다르다: 열린 판 " + openRun.get().runId() + ", 받은 값 " + payload.runId());
        }
        return Optional.empty();
    }

    /**
     * S2~S9. S1({@link #checkRunOpen})을 통과한 결산만 받는다.
     *
     * @param payload     클라이언트가 보낸 결산(형식 검사를 마친 것)
     * @param setup       열린 판의 시작 성장도와 지금 노드로 계산한 판 설정
     * @param currentGold 지금 서버 진행도의 Gold(이정표 보상 계산에 쓴다)
     * @return 위반이 없으면 비어 있다
     */
    public Optional<Violation> validate(SettlementPayload payload, RunSetup setup, long currentGold) {
        Optional<Violation> found;

        found = checkKills(payload, setup);
        if (found.isPresent()) {
            return found;
        }

        // 여기부터 장부의 모든 칸이 이번 판에서 가능한 조합이다
        found = checkExpAndLevel(payload, setup);
        if (found.isPresent()) {
            return found;
        }

        found = checkGold(payload, setup);
        if (found.isPresent()) {
            return found;
        }

        found = checkTime(payload, setup);
        if (found.isPresent()) {
            return found;
        }

        return checkResult(payload, setup, currentGold);
    }

    // S2: 각 칸이 이번 판에서 나올 수 있는 조합(종류, 색 등급, 크기, 황금)이다
    private static Optional<Violation> checkKills(SettlementPayload payload, RunSetup setup) {
        for (KillItem kill : payload.kills()) {
            Optional<KindSetup> found = setup.findKind(kill.enemyId());
            if (found.isEmpty()) {
                return violation(RejectCode.SETTLEMENT_INVALID_KILLS, "없는 종류다: " + kill.enemyId());
            }

            KindSetup kind = found.get();
            if (kind.pickup() && (kill.tier() != 0 || kill.size() != 1 || kill.golden())) {
                return violation(RejectCode.SETTLEMENT_INVALID_KILLS, "수거형은 tier 0, size 1, 황금 아님만 가능하다: " + describe(kill));
            }
            if (kill.size() > kind.maxSize()) {
                return violation(RejectCode.SETTLEMENT_INVALID_KILLS, "이번 판의 최대 크기(" + kind.maxSize() + ")를 넘는다: " + describe(kill));
            }
            if (!tierPossible(kind, kill.tier())) {
                return violation(RejectCode.SETTLEMENT_INVALID_KILLS, "이번 판에서 나올 수 없는 색 등급이다: " + describe(kill));
            }
            if (kill.golden() && !kind.goldenPossible()) {
                return violation(RejectCode.SETTLEMENT_INVALID_KILLS, "이번 판에서 황금이 나올 수 없다: " + describe(kill));
            }
        }
        return Optional.empty();
    }

    // 없는 tier는 KindSetup이 IllegalArgumentException을 던지므로 불가능한 색 등급으로 본다
    private static boolean tierPossible(KindSetup kind, int tier) {
        try {
            return kind.tierPossible(tier);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // S3, S4: exp는 시작 EXP + 칸별 합이고, Level과 이정표는 그 EXP에서 정해진다
    private static Optional<Violation> checkExpAndLevel(SettlementPayload payload, RunSetup setup) {
        long expected;
        try {
            expected = setup.startExp();
            for (KillItem kill : payload.kills()) {
                long perKill = setup.kind(kill.enemyId()).expOf(kill.tier(), kill.size());
                expected = Math.addExact(expected, Math.multiplyExact(perKill, (long) kill.count()));
            }
        } catch (ArithmeticException e) {
            return violation(RejectCode.SETTLEMENT_EXP_MISMATCH, "EXP 합이 long 범위를 넘는다.");
        }

        if (payload.exp() != expected) {
            return violation(RejectCode.SETTLEMENT_EXP_MISMATCH, "EXP가 장부와 다르다: 장부 " + expected + ", 받은 값 " + payload.exp());
        }

        int level = setup.levelForExp(expected);
        if (payload.reachedLevel() != level) {
            return violation(RejectCode.SETTLEMENT_EXP_MISMATCH, "Level이 EXP와 다르다: 계산 " + level + ", 받은 값 " + payload.reachedLevel());
        }

        boolean milestone = reachedMilestone(setup, level);
        if (payload.reachedMilestone() != milestone) {
            return violation(RejectCode.SETTLEMENT_EXP_MISMATCH, "이정표 도달 여부가 다르다: 계산 " + milestone + ", 받은 값 " + payload.reachedMilestone());
        }
        return Optional.empty();
    }

    // 목표 Level이 있고 거기에 닿았으면 이정표다
    private static boolean reachedMilestone(RunSetup setup, int level) {
        return setup.goalLevel() != 0 && level >= setup.goalLevel();
    }

    // S5, S6: 치명 횟수와 보너스가 가능한 범위 안이고, earnedGold는 칸별 Gold 합 + 치명 보너스다
    private static Optional<Violation> checkGold(SettlementPayload payload, RunSetup setup) {
        Optional<Violation> crit = checkGoldenCrit(payload, setup);
        if (crit.isPresent()) {
            return crit;
        }

        long expected;
        try {
            expected = payload.goldenCritBonusGold();
            for (KillItem kill : payload.kills()) {
                long perKill = setup.kind(kill.enemyId()).goldOf(kill.tier(), kill.size(), kill.golden());
                expected = Math.addExact(expected, Math.multiplyExact(perKill, (long) kill.count()));
            }
        } catch (ArithmeticException e) {
            return violation(RejectCode.SETTLEMENT_GOLD_MISMATCH, "Gold 합이 long 범위를 넘는다.");
        }

        if (payload.earnedGold() != expected) {
            return violation(RejectCode.SETTLEMENT_GOLD_MISMATCH, "earnedGold가 장부와 다르다: 장부 " + expected + ", 받은 값 " + payload.earnedGold());
        }
        return Optional.empty();
    }

    // S5: 치명은 치명 확률이 0보다 큰 종류의 황금 처치에서만 날 수 있고, 치명마다 그 처치의 보너스를 준다.
    // 치명 c번의 보너스 합은 "가능한 처치 중 c개를 고른 합" 범위(가장 작은 c개 ~ 가장 큰 c개) 안이어야 한다.
    private static Optional<Violation> checkGoldenCrit(SettlementPayload payload, RunSetup setup) {
        int crits = payload.goldenCritCount();
        long bonus = payload.goldenCritBonusGold();

        if (crits == 0) {
            if (bonus != 0) {
                return violation(RejectCode.SETTLEMENT_GOLD_MISMATCH, "치명이 0번인데 보너스가 있다: " + bonus);
            }
            return Optional.empty();
        }

        // 치명이 날 수 있는 황금 처치: (보너스 한 번의 Gold, 처치 수)
        List<long[]> candidates = new ArrayList<>();
        long eligibleKills = 0;
        for (KillItem kill : payload.kills()) {
            if (!kill.golden()) {
                continue;
            }
            KindSetup kind = setup.kind(kill.enemyId());
            Optional<GoldenTrait> golden = kind.golden();
            if (golden.isEmpty() || golden.get().critChance() <= 0) {
                continue;
            }
            candidates.add(new long[] { kind.goldenCritBonusOf(kill.tier(), kill.size()), kill.count() });
            eligibleKills += kill.count();
        }

        if (crits > eligibleKills) {
            return violation(RejectCode.SETTLEMENT_GOLD_MISMATCH, "치명 횟수가 치명이 가능한 황금 처치 수를 넘는다: 치명 " + crits + ", 가능한 처치 " + eligibleKills);
        }

        long min;
        long max;
        try {
            candidates.sort(Comparator.comparingLong(candidate -> candidate[0]));
            min = sumOfFirst(candidates, crits);
            candidates.sort(Comparator.comparingLong((long[] candidate) -> candidate[0]).reversed());
            max = sumOfFirst(candidates, crits);
        } catch (ArithmeticException e) {
            return violation(RejectCode.SETTLEMENT_GOLD_MISMATCH, "치명 보너스 범위가 long 범위를 넘는다.");
        }

        if (bonus < min || bonus > max) {
            return violation(RejectCode.SETTLEMENT_GOLD_MISMATCH, "치명 보너스가 가능한 범위(" + min + " ~ " + max + ")를 벗어난다: " + bonus);
        }
        return Optional.empty();
    }

    // 정렬된 후보에서 앞쪽부터 count개를 골라 보너스를 더한다
    private static long sumOfFirst(List<long[]> sorted, int count) {
        long sum = 0;
        int left = count;
        for (long[] candidate : sorted) {
            long take = Math.min(left, candidate[1]);
            sum = Math.addExact(sum, Math.multiplyExact(candidate[0], take));
            left -= (int) take;
            if (left == 0) {
                break;
            }
        }
        return sum;
    }

    // S7, S8: 시간 추가 횟수와 경과 시간이 가능한 범위 안이다
    private static Optional<Violation> checkTime(SettlementPayload payload, RunSetup setup) {
        // S7: 시간 추가는 수거형이 아니고 시간 추가 확률이 0보다 큰 종류의 처치에서만 난다
        long eligibleKills = 0;
        for (KillItem kill : payload.kills()) {
            KindSetup kind = setup.kind(kill.enemyId());
            if (!kind.pickup() && kind.timeChance() > 0) {
                eligibleKills += kill.count();
            }
        }
        if (payload.timeBonusCount() > eligibleKills) {
            return violation(RejectCode.SETTLEMENT_TIME_EXCEEDED, "시간 추가 횟수가 가능한 처치 수를 넘는다: 횟수 " + payload.timeBonusCount() + ", 가능한 처치 " + eligibleKills);
        }

        // S8: 플레이한 시간은 시작 제한 시간 + Level업마다의 성장 시간 + 시간 추가 시간을 넘을 수 없다
        double bound = (double) setup.timeLimitSeconds()
                + (double) (payload.reachedLevel() - setup.startLevel()) * setup.growthTimeSeconds()
                + (double) payload.timeBonusCount() * setup.killTimeBonusSeconds();
        double allowed = bound + TIME_TOLERANCE_SECONDS + bound * TIME_TOLERANCE_RATIO;

        if (payload.playedSeconds() > allowed) {
            return violation(RejectCode.SETTLEMENT_TIME_EXCEEDED, "플레이 시간이 가능한 범위를 넘는다: 상한 " + bound + "초, 받은 값 " + payload.playedSeconds() + "초");
        }
        return Optional.empty();
    }

    // S9: 결산 금액과 결산 후 성장도는 서버가 다시 계산해 일치를 확인한다
    private static Optional<Violation> checkResult(SettlementPayload payload, RunSetup setup, long currentGold) {
        long settledGold = expectedSettledGold(payload, setup, currentGold);
        if (payload.settledGold() != settledGold) {
            return violation(RejectCode.SETTLEMENT_RESULT_MISMATCH, "결산 금액이 다르다: 서버 계산 " + settledGold + ", 받은 값 " + payload.settledGold());
        }

        int nextStage = expectedNextGrowthStage(payload, setup);
        if (payload.nextGrowthStage() != nextStage) {
            return violation(RejectCode.SETTLEMENT_RESULT_MISMATCH, "결산 후 성장도가 다르다: 서버 계산 " + nextStage + ", 받은 값 " + payload.nextGrowthStage());
        }
        return Optional.empty();
    }

    /** 서버가 계산한 결산 금액: 이정표에 닿았으면 max(0, 목표 Gold − 지금 Gold), 아니면 earnedGold. 검사를 통과한 결산에만 쓴다. */
    static long expectedSettledGold(SettlementPayload payload, RunSetup setup, long currentGold) {
        if (payload.reachedMilestone()) {
            return setup.nextMilestone().orElseThrow().rewardFor(currentGold);
        }
        return payload.earnedGold();
    }

    /** 서버가 계산한 결산 후 성장도: 이정표에 닿았으면 +1. 검사를 통과한 결산에만 쓴다. */
    static int expectedNextGrowthStage(SettlementPayload payload, RunSetup setup) {
        return payload.reachedMilestone() ? setup.growthStage() + 1 : setup.growthStage();
    }

    private static String describe(KillItem kill) {
        return kill.enemyId() + " tier " + kill.tier() + " size " + kill.size() + (kill.golden() ? " 황금" : "");
    }

    private static Optional<Violation> violation(RejectCode code, String message) {
        return Optional.of(new Violation(code, message));
    }

}
