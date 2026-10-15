package org.example.feedablackhole.sync.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.battle.rules.BattleRules;
import org.example.feedablackhole.battle.rules.ContentBattleRules;
import org.example.feedablackhole.battle.rules.RunSetup;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.RunStartPayload;
import org.example.feedablackhole.sync.log.payload.SettlementPayload;
import org.example.feedablackhole.sync.log.payload.SettlementPayload.KillItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SETTLEMENT 처리기와 SettlementValidator(S1~S9)의 통과·실패 테스트.
 * 샘플 콘텐츠 묶음(src/test/resources/content-sample)과 실제 ContentBattleRules 위에서 돈다(계약 결정 C30).
 * 기대값은 숫자를 직접 적지 않고 같은 판 설정(RunSetup)에서 계산해 만든다.
 */
class SettlementHandlerTests {

    private static final String RUN_ID = "6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c";

    private static BattleRules rules;
    private static SettlementHandler handler;

    @BeforeAll
    static void load() {
        ContentBundle bundle = ContentBundleLoader.load(SettlementHandlerTests::bytes);
        rules = new ContentBattleRules(bundle.nodeTree(), bundle.battle());
        handler = new SettlementHandler(rules);
    }

    private static byte[] bytes(String path) {
        try (InputStream in = SettlementHandlerTests.class.getResourceAsStream("/content-sample/" + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- 통과 ----------

    // 샘플 로그(log-settlement.json)와 같은 결산: 소행성 20마리, Gold 20. 반영되고 판이 닫힌다
    @Test
    void validSettlementIsAppliedAndClosesTheRun() {
        Ctx ctx = new Ctx(0, 5, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));

        HandleResult result = handler.handle(s.build(), ctx);

        assertThat(result).isInstanceOf(HandleResult.Applied.class);
        assertThat(ctx.gold).isEqualTo(25);
        assertThat(ctx.growthStage).isZero();
        assertThat(ctx.settledGold).isEqualTo(20);
        assertThat(ctx.runOpen()).isFalse();
    }

    // 이정표에 닿은 판: Level은 목표에서 멈추고, 결산 금액은 목표 Gold − 지금 Gold, 성장도 +1(번 Gold는 버려진다)
    @Test
    void milestoneSettlementPaysTheRewardAndRaisesTheStage() {
        Ctx ctx = new Ctx(0, 100, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 300_000));

        HandleResult result = handler.handle(s.build(), ctx);

        assertThat(s.reachedLevel).isEqualTo(10);
        assertThat(s.reachedMilestone).isTrue();
        assertThat(result).isInstanceOf(HandleResult.Applied.class);
        assertThat(ctx.settledGold).isEqualTo(690_000 - 100);
        assertThat(ctx.gold).isEqualTo(690_000);
        assertThat(ctx.growthStage).isEqualTo(1);
    }

    // 마지막 이정표 뒤: 목표 Level이 없으므로 이정표에 닿지 않고 earnedGold가 결산 금액이다
    @Test
    void afterTheLastMilestoneThereIsNoGoal() {
        Ctx ctx = new Ctx(3, 0, Map.of());
        RunSetup setup = setup(ctx);
        assertThat(setup.goalLevel()).isZero();

        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        HandleResult result = handler.handle(s.build(), ctx);

        assertThat(s.reachedMilestone).isFalse();
        assertThat(result).isInstanceOf(HandleResult.Applied.class);
        assertThat(ctx.growthStage).isEqualTo(3);
        assertThat(ctx.gold).isEqualTo(20);
    }

    // 계약 3.8의 조작 샘플과 같은 경우: 정상 결산의 Gold만 부풀리면 GOLD_MISMATCH
    @Test
    void inflatedGoldIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.earnedGold = 99_999;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    // ---------- 계약 샘플(3.8) ----------

    // 샘플 로그 파일의 결산 payload는 샘플 콘텐츠에서 실제로 통과한다
    @Test
    void sampleSettlementLogPasses() {
        Ctx ctx = new Ctx(0, 0, Map.of());

        HandleResult result = handler.handle(sample("log-settlement.json", 0), ctx);

        assertThat(result).isInstanceOf(HandleResult.Applied.class);
        assertThat(ctx.gold).isEqualTo(20);
    }

    // 조작 샘플(sync-request-tampered.json)의 seq 2 결산은 GOLD_MISMATCH로 멈춘다
    @Test
    void tamperedSampleSettlementIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());

        HandleResult result = handler.handle(sample("sync-request-tampered.json", 1), ctx);

        assertRejected(result, RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    // ---------- S1 ----------

    @Test
    void noOpenRunIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        ctx.open = null;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.RUN_NOT_OPEN, ctx);
    }

    @Test
    void differentRunIdIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.runId = "00000000-0000-4000-8000-000000000000";

        assertRejected(handler.handle(s.build(), ctx), RejectCode.RUN_NOT_OPEN, ctx);
    }

    // ---------- S2 ----------

    @Test
    void unknownEnemyIsRejected() {
        assertInvalidKills(kill("dragon", 0, 1, false, 1), Map.of());
    }

    @Test
    void tierOutOfRangeIsRejected() {
        assertInvalidKills(kill("asteroid", 99, 1, false, 1), Map.of());
    }

    // 노드 없이는 최대 크기가 1이다
    @Test
    void sizeAboveTheRunMaximumIsRejected() {
        assertInvalidKills(kill("asteroid", 0, 2, false, 1), Map.of());
    }

    @Test
    void goldenWithoutGoldenSpawnIsRejected() {
        assertInvalidKills(kill("asteroid", 0, 1, true, 1), Map.of());
    }

    @Test
    void pickupOnlyAllowsTierZeroSizeOne() {
        assertInvalidKills(kill("comet", 1, 1, false, 1), Map.of());
    }

    // ---------- S3, S4 ----------

    @Test
    void expOffByOneIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.exp += 1;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_EXP_MISMATCH, ctx);
    }

    // Level 1은 누적 EXP 80부터: 79마리는 Level 0, 80마리는 Level 1
    @Test
    void levelFollowsTheExpThreshold() {
        Ctx below = new Ctx(0, 0, Map.of());
        Settlement low = Settlement.of(below, kill("asteroid", 0, 1, false, 79));
        assertThat(low.reachedLevel).isZero();
        assertThat(handler.handle(low.build(), below)).isInstanceOf(HandleResult.Applied.class);

        Ctx atThreshold = new Ctx(0, 0, Map.of());
        Settlement high = Settlement.of(atThreshold, kill("asteroid", 0, 1, false, 80));
        assertThat(high.reachedLevel).isEqualTo(1);
        high.reachedLevel = 0;
        assertRejected(handler.handle(high.build(), atThreshold), RejectCode.SETTLEMENT_EXP_MISMATCH, atThreshold);
    }

    @Test
    void wrongMilestoneFlagIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 300_000));
        s.reachedMilestone = false;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_EXP_MISMATCH, ctx);
    }

    // ---------- S5, S6 ----------

    @Test
    void earnedGoldOffByOneIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.earnedGold -= 1;
        s.settledGold -= 1;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    // 황금 처치 10마리, 치명 3번: 보너스가 그 처치의 보너스 × 3이면 통과
    @Test
    void goldenCritWithinTheRangeIsApplied() {
        Ctx ctx = new Ctx(0, 0, goldenNodes());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, true, 10));
        long bonus = setup(ctx).kind("asteroid").goldenCritBonusOf(0, 1);
        s.goldenCritCount = 3;
        s.goldenCritBonusGold = bonus * 3;
        s.recompute(ctx);

        assertThat(handler.handle(s.build(), ctx)).isInstanceOf(HandleResult.Applied.class);
    }

    @Test
    void bonusWithoutCritIsRejected() {
        Ctx ctx = new Ctx(0, 0, goldenNodes());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, true, 10));
        s.goldenCritBonusGold = 5;
        s.recompute(ctx);

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    @Test
    void moreCritsThanGoldenKillsIsRejected() {
        Ctx ctx = new Ctx(0, 0, goldenNodes());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, true, 2));
        s.goldenCritCount = 3;
        s.goldenCritBonusGold = setup(ctx).kind("asteroid").goldenCritBonusOf(0, 1) * 3;
        s.recompute(ctx);

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    @Test
    void bonusAboveTheMaximumIsRejected() {
        Ctx ctx = new Ctx(0, 0, goldenNodes());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, true, 10));
        s.goldenCritCount = 1;
        s.goldenCritBonusGold = setup(ctx).kind("asteroid").goldenCritBonusOf(0, 1) + 1;
        s.recompute(ctx);

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    // 치명 확률이 0이면(치명 노드 없음) 황금 처치가 있어도 치명은 날 수 없다
    @Test
    void critWithoutCritChanceIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of("goldenAsteroid.spawnChance-01", 1));
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, true, 10));
        s.goldenCritCount = 1;
        s.goldenCritBonusGold = setup(ctx).kind("asteroid").goldenCritBonusOf(0, 1);
        s.recompute(ctx);

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    // 합이 long 범위를 넘는 장부는 500이 아니라 거부다
    @Test
    void goldSumOverflowIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("galaxy", 0, 1, false, 2_000_000_000));

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);
    }

    // ---------- S7, S8 ----------

    @Test
    void timeBonusWithoutTimeChanceIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.timeBonusCount = 1;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_TIME_EXCEEDED, ctx);
    }

    @Test
    void timeBonusUpToTheKillCountIsApplied() {
        Ctx ctx = new Ctx(0, 0, Map.of("asteroid.timeChance-01", 1));
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.timeBonusCount = 20;
        s.playedSeconds = 12f + 20 * 0.3f;

        assertThat(handler.handle(s.build(), ctx)).isInstanceOf(HandleResult.Applied.class);
    }

    @Test
    void timeBonusAboveTheKillCountIsRejected() {
        Ctx ctx = new Ctx(0, 0, Map.of("asteroid.timeChance-01", 1));
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.timeBonusCount = 21;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_TIME_EXCEEDED, ctx);
    }

    // 제한 시간 12초: 12초는 통과, 아주 작은 float 오차(0.005)도 통과, 0.5초 초과는 거부
    @Test
    void playedSecondsAreBoundedByTheTimeLimit() {
        Ctx exact = new Ctx(0, 0, Map.of());
        Settlement atLimit = Settlement.of(exact, kill("asteroid", 0, 1, false, 20));
        atLimit.playedSeconds = 12f;
        assertThat(handler.handle(atLimit.build(), exact)).isInstanceOf(HandleResult.Applied.class);

        Ctx tiny = new Ctx(0, 0, Map.of());
        Settlement withinTolerance = Settlement.of(tiny, kill("asteroid", 0, 1, false, 20));
        withinTolerance.playedSeconds = 12.005f;
        assertThat(handler.handle(withinTolerance.build(), tiny)).isInstanceOf(HandleResult.Applied.class);

        Ctx over = new Ctx(0, 0, Map.of());
        Settlement tooLong = Settlement.of(over, kill("asteroid", 0, 1, false, 20));
        tooLong.playedSeconds = 12.5f;
        assertRejected(handler.handle(tooLong.build(), over), RejectCode.SETTLEMENT_TIME_EXCEEDED, over);
    }

    // Level이 오르면 성장 시간만큼 한도가 늘어난다(growth.time-01은 +3초)
    @Test
    void levelUpsExtendTheTimeBound() {
        Settlement s;

        Ctx without = new Ctx(0, 0, Map.of());
        s = Settlement.of(without, kill("asteroid", 0, 1, false, 80));
        s.playedSeconds = 14f;
        assertRejected(handler.handle(s.build(), without), RejectCode.SETTLEMENT_TIME_EXCEEDED, without);

        Ctx with = new Ctx(0, 0, Map.of("growth.time-01", 1));
        s = Settlement.of(with, kill("asteroid", 0, 1, false, 80));
        s.playedSeconds = 14f;
        assertThat(handler.handle(s.build(), with)).isInstanceOf(HandleResult.Applied.class);
    }

    // ---------- S9 ----------

    @Test
    void settledGoldMismatchIsRejected() {
        Ctx ctx = new Ctx(0, 100, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 300_000));
        s.settledGold = s.earnedGold; // 이정표 보상이 아니라 번 Gold를 결산 금액으로 적었다

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_RESULT_MISMATCH, ctx);
    }

    @Test
    void nextGrowthStageMismatchIsRejected() {
        Ctx ctx = new Ctx(0, 100, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 300_000));
        s.nextGrowthStage = 0;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_RESULT_MISMATCH, ctx);
    }

    // ---------- 검사 순서 ----------

    // 한 로그가 여러 검사를 어기면 계약 3.6의 순서에서 처음 걸린 코드만 알린다(INVALID_KILLS < EXP < GOLD < TIME < RESULT)
    @Test
    void firstFailingCheckWins() {
        Ctx ctx = new Ctx(0, 0, Map.of());
        Settlement s = Settlement.of(ctx, kill("asteroid", 0, 1, false, 20));
        s.exp += 1;
        s.earnedGold += 1;
        s.playedSeconds = 99f;
        s.settledGold += 1;

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_EXP_MISMATCH, ctx);

        s.exp -= 1;
        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_GOLD_MISMATCH, ctx);

        s.earnedGold -= 1;
        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_TIME_EXCEEDED, ctx);

        s.playedSeconds = 1f;
        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_RESULT_MISMATCH, ctx);
    }

    // ---------- 도우미 ----------

    // 샘플 파일의 payload를 읽는다. 로그 파일이면 payload가, 요청 파일이면 logs[index].payload가 대상이다
    private static SettlementPayload sample(String file, int index) {
        try (InputStream in = SettlementHandlerTests.class.getResourceAsStream("/sync-samples/" + file)) {
            JsonMapper mapper = JsonMapper.builder().build();
            JsonNode root = mapper.readTree(in);
            JsonNode log = root.has("logs") ? root.get("logs").get(index) : root;
            return mapper.treeToValue(log.get("payload"), SettlementPayload.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertInvalidKills(KillItem kill, Map<String, Integer> ranks) {
        Ctx ctx = new Ctx(0, 0, ranks);
        Settlement s = new Settlement();
        s.kills = List.of(kill);

        assertRejected(handler.handle(s.build(), ctx), RejectCode.SETTLEMENT_INVALID_KILLS, ctx);
    }

    private static void assertRejected(HandleResult result, RejectCode code, Ctx ctx) {
        assertThat(result).isInstanceOf(HandleResult.Rejected.class);
        assertThat(((HandleResult.Rejected) result).code()).isEqualTo(code);
        assertThat(ctx.mutated).as("거부할 때는 진행도를 바꾸지 않는다").isFalse();
    }

    private static Map<String, Integer> goldenNodes() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("goldenAsteroid.spawnChance-01", 1);
        ranks.put("goldenAsteroid.critChance-01", 1);
        return ranks;
    }

    private static KillItem kill(String enemyId, int tier, int size, boolean golden, int count) {
        return new KillItem(enemyId, tier, size, golden, count);
    }

    private static RunSetup setup(Ctx ctx) {
        return rules.setupFor(ctx.open.startGrowthStage(), rules.upgradesFor(ctx.ranks));
    }

    /** 결산 payload를 만드는 가변 도우미. of()는 판 설정으로 계산한 정상 값으로 채운다. */
    private static final class Settlement {
        String runId = RUN_ID;
        float playedSeconds = 1f;
        List<KillItem> kills = List.of();
        int goldenCritCount;
        long goldenCritBonusGold;
        long earnedGold;
        long exp;
        int reachedLevel;
        boolean reachedMilestone;
        int timeBonusCount;
        long settledGold;
        int nextGrowthStage;

        static Settlement of(Ctx ctx, KillItem... kills) {
            Settlement s = new Settlement();
            s.kills = List.of(kills);
            s.recompute(ctx);
            return s;
        }

        // 장부와 치명 보너스에서 earnedGold, exp, Level, 결산 값을 다시 계산한다
        void recompute(Ctx ctx) {
            RunSetup setup = setup(ctx);
            long gold = goldenCritBonusGold;
            long totalExp = setup.startExp();
            for (KillItem k : kills) {
                if (setup.findKind(k.enemyId()).isEmpty()) {
                    return; // 없는 종류면 검증이 거부할 값이므로 계산하지 않는다
                }
                gold += setup.kind(k.enemyId()).goldOf(k.tier(), k.size(), k.golden()) * k.count();
                totalExp += setup.kind(k.enemyId()).expOf(k.tier(), k.size()) * k.count();
            }
            earnedGold = gold;
            exp = totalExp;
            reachedLevel = setup.levelForExp(totalExp);
            reachedMilestone = setup.goalLevel() != 0 && reachedLevel >= setup.goalLevel();
            settledGold = reachedMilestone ? setup.nextMilestone().orElseThrow().rewardFor(ctx.gold) : earnedGold;
            nextGrowthStage = reachedMilestone ? setup.growthStage() + 1 : setup.growthStage();
        }

        SettlementPayload build() {
            return new SettlementPayload(runId, playedSeconds, kills, goldenCritCount, goldenCritBonusGold, earnedGold,
                    exp, reachedLevel, reachedMilestone, timeBonusCount, settledGold, nextGrowthStage);
        }
    }

    /** 처리기가 보는 진행도의 가짜. 변경 메서드가 불리면 mutated가 true가 된다. */
    private static final class Ctx implements SyncContext {
        long gold;
        int growthStage;
        final Map<String, Integer> ranks;
        OpenRun open;
        boolean mutated;
        long settledGold = -1;

        Ctx(int growthStage, long gold, Map<String, Integer> ranks) {
            this.growthStage = growthStage;
            this.gold = gold;
            this.ranks = ranks;
            this.open = new OpenRun(RUN_ID, 1, growthStage, gold, 1);
        }

        boolean runOpen() {
            return open != null;
        }

        @Override
        public long accountId() {
            return 1;
        }

        @Override
        public long seq() {
            return 2;
        }

        @Override
        public long gold() {
            return gold;
        }

        @Override
        public int growthStage() {
            return growthStage;
        }

        @Override
        public Map<String, Integer> nodeRanks() {
            return ranks;
        }

        @Override
        public Optional<OpenRun> openRun() {
            return Optional.ofNullable(open);
        }

        @Override
        public boolean runIdUsed(String runId) {
            return open != null && open.runId().equals(runId);
        }


        @Override
        public void spendGold(long amount) {
            mutated = true; gold -= amount;
        }

        @Override
        public void earnGold(long amount) {
            mutated = true; gold += amount;
        }

        @Override
        public void setNodeRank(String nodeId, int rank) {
            mutated = true;
        }

        @Override
        public void setGrowthStage(int stage) {
            mutated = true; growthStage = stage;
        }

        @Override
        public void resetProgress() {
            mutated = true;
        }

        @Override
        public void abandonOpenRun() {
            mutated = true; open = null;
        }

        @Override
        public void openRun(RunStartPayload payload, List<SuspicionReason> suspicions) {
            mutated = true;
        }

        @Override
        public void settleOpenRun(long settled, int next) {
            mutated = true; settledGold = settled; open = null;
        }

    }

}
