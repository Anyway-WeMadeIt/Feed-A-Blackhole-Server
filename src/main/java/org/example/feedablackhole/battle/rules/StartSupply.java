package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.SupplyEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StartSupply {

    private StartSupply() {
    }

    static List<SupplyEntry> of(BattleContent content, Map<String, KindComposition> kinds) {
        List<String> order = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();

        for (SupplyEntry entry : content.startSupply()) {
            add(order, counts, entry.enemyId(), entry.count());
        }

        for (Map.Entry<String, KindComposition> kind : kinds.entrySet()) {
            int bonus = kind.getValue().startSupplyBonus();

            if (bonus > 0) {
                add(order, counts, kind.getKey(), bonus);
            }
        }

        List<String> chainOrder = UpgradeChain.orderOf(content.enemies());

        for (String enemyId : chainOrder) {
            int have = counts.getOrDefault(enemyId, 0);
            int moved = Math.min(have, kinds.get(enemyId).upgradeCount());

            if (moved > 0) {
                String target = content.enemy(enemyId).upgradesTo();

                counts.put(enemyId, have - moved);
                add(order, counts, target, moved);
            }
        }

        List<SupplyEntry> supply = new ArrayList<>();
        for (String enemyId : order) {
            int count = counts.get(enemyId);
            if (count > 0) {
                supply.add(new SupplyEntry(enemyId, count));
            }
        }

        return supply;
    }

    private static void add(List<String> order, Map<String, Integer> counts, String enemyId, int count) {
        if (!counts.containsKey(enemyId)) {
            order.add(enemyId);
        }

        counts.put(enemyId, Math.addExact(counts.getOrDefault(enemyId, 0), count));
    }
}
