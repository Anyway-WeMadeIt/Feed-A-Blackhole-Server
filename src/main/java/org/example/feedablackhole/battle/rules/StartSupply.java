package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.SupplyEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 판 시작 때의 공급 계산(클라이언트 GameSessionFactory.StartSupplyOf).
 *
 * <ol>
 * <li>콘텐츠의 시작 적을 종류마다 합치고, 노드로 늘어난 시작 수(보너스)를 더한다
 * <li>변환 사슬 앞쪽부터, 종류마다 변환 수만큼 다음 종류로 옮긴다(가진 수보다 많이 옮기지 않는다)
 * <li>은하가 나오는 판이면 은하 1마리를 더한다(변환과 성장 공급에는 들지 않는다)
 * <li>마릿수가 0이 된 종류는 빼고, 종류는 처음 나온 순서로 돌려준다
 * </ol>
 */
public final class StartSupply {

    private StartSupply() {
    }

    /**
     * 시작 공급을 계산한다.
     *
     * @param content 전투 콘텐츠(콘텐츠의 시작 적과 종류 정의)
     * @param kinds   이 판의 종류 구성(종류 이름 -> 구성). 시작 수 보너스와 변환 수를 담고 있다
     * @param withGalaxy 은하가 나오는 판인가(이미 공급에 있으면 더하지 않는다)
     * @return 종류마다 하나, 처음 나온 종류 순서, 0마리 종류는 제외
     */
    static List<SupplyEntry> of(BattleContent content, Map<String, KindComposition> kinds, boolean withGalaxy) {
        // order: 처음 나온 종류의 순서(결과의 순서). counts: 종류 이름 -> 합친 마릿수
        List<String> order = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();

        // 1) 콘텐츠의 시작 적을 종류마다 합친다(같은 종류가 여러 번 있어도 더한다)
        for (SupplyEntry entry : content.startSupply()) {
            add(order, counts, entry.enemyId(), entry.count());
        }

        // 노드로 늘어난 시작 수를 더한다. 콘텐츠에 없던 종류라도 보너스가 있으면 순서의 뒤에 붙는다
        for (Map.Entry<String, KindComposition> kind : kinds.entrySet()) {
            int bonus = kind.getValue().startSupplyBonus();

            if (bonus > 0) {
                add(order, counts, kind.getKey(), bonus);
            }
        }

        // 2) 변환 사슬 앞 종류부터 처리한다. 소행성 -> 행성을 먼저 옮겨야 행성 -> 항성이
        // "소행성에서 바뀐 행성"까지 셀 수 있다
        List<String> chainOrder = UpgradeChain.orderOf(content.enemies());

        for (String enemyId : chainOrder) {
            int have = counts.getOrDefault(enemyId, 0);                      // 지금 가진 수(앞에서 바뀌어 온 것 포함)
            int moved = Math.min(have, kinds.get(enemyId).upgradeCount());   // 가진 수보다 많이 옮기지 않는다

            if (moved > 0) {
                String target = content.enemy(enemyId).upgradesTo(); // 옮겨 갈 종류(변환 수가 있으면 대상이 있음이 보장된다)

                counts.put(enemyId, have - moved); // 원래 종류에서 빼고
                add(order, counts, target, moved); // 대상 종류에 더한다
            }
        }

        // 은하는 변환 뒤에 더한다. 공급에 이미 있으면 그대로 둔다
        if (withGalaxy) {
            for (String enemyId : kinds.keySet()) {
                if (RunComposition.GALAXY_ID.equals(enemyId) && !counts.containsKey(enemyId)) {
                    add(order, counts, enemyId, 1);
                }
            }
        }

        // 3) 마릿수가 0이 된 종류는 빼고, 처음 나온 순서대로 결과를 만든다
        List<SupplyEntry> supply = new ArrayList<>();
        for (String enemyId : order) {
            int count = counts.get(enemyId);
            if (count > 0) {
                supply.add(new SupplyEntry(enemyId, count));
            }
        }

        return supply;
    }

    // 종류의 마릿수를 더한다. 처음 나온 종류면 순서에도 기록한다
    private static void add(List<String> order, Map<String, Integer> counts, String enemyId, int count) {
        // 순서 기록이 더하기보다 먼저여야 한다(put 이후에는 항상 키가 있어서 처음인지 알 수 없다)
        if (!counts.containsKey(enemyId)) {
            order.add(enemyId);
        }

        // addExact: int 범위를 넘으면 조용히 틀어지는 대신 예외를 던진다(클라이언트의 checked)
        counts.put(enemyId, Math.addExact(counts.getOrDefault(enemyId, 0), count));
    }
}
