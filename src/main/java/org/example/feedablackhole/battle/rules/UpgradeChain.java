package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.battle.content.EnemyKindDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 변환 사슬에서 앞 종류가 먼저 오는 순서(클라이언트 GameSessionFactory.ChainOrder).
 * 소행성 -> 행성 -> 항성 -> 은하처럼 이어진 변환에서, 앞 종류의 변환 수를 먼저 옮겨야 뒤 종류가
 * "앞에서 바뀌어 온 수"까지 셀 수 있어서 이 순서가 필요하다. 종류 순서(battle.json의 enemies 순서)를 지키는 위상 정렬이다.
 * 사슬이 도는 콘텐츠는 로더(BattleContentValidator의 규칙 3)가 이미 막으므로 여기서는 검사하지 않는다.
 */
public final class UpgradeChain {

    private UpgradeChain() {
    }

    /**
     * 변환 사슬 순서로 종류 이름(enemyId)을 정렬한다.
     *
     * @param enemies 종류 정의(battle.json 순서). 같은 조건이면 이 순서가 유지된다
     * @return 앞 종류가 먼저 오는 enemyId 목록
     */
    static List<String> orderOf(List<EnemyKindDefinition> enemies) {
        // incoming: 종류 이름 -> 그 종류로 바뀌어 오는 종류의 수. byId: 종류 이름 -> 종류 정의
        Map<String, Integer> incoming = new HashMap<>();
        Map<String, EnemyKindDefinition> byId = new HashMap<>();

        // 모든 종류를 0으로 등록한다. 바뀌어 오지 않는 종류도 맵에 있어야 아래에서 0인 것을 찾을 수 있다
        for (EnemyKindDefinition enemy : enemies) {
            incoming.put(enemy.enemyId(), 0);
            byId.put(enemy.enemyId(), enemy);
        }

        // 바뀌어 가는 대상의 값을 1 올린다. 변환 대상이 없는 종류(은하, 혜성)는 건너뛴다.
        // 0으로 채우는 반복문과 따로 도는 이유: 대상이 목록에서 뒤에 있으면 아직 맵에 없을 수 있다
        for (EnemyKindDefinition enemy : enemies) {
            if (enemy.upgradesTo() != null) {
                incoming.merge(enemy.upgradesTo(), 1, Integer::sum);
            }
        }

        // 바뀌어 오는 종류가 없는 종류(사슬의 맨 앞)를 큐에 넣는다.
        // 맵(HashMap)이 아니라 enemies를 돌아야 battle.json의 종류 순서가 지켜진다
        Queue<String> ready = new ArrayDeque<>();
        for (EnemyKindDefinition enemy : enemies) {
            if (incoming.get(enemy.enemyId()) == 0) {
                ready.add(enemy.enemyId());
            }
        }

        // 큐에서 하나씩 꺼내 결과에 넣고, 그 종류가 바뀌어 가는 대상의 값을 줄인다
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            order.add(id);

            String target = byId.get(id).upgradesTo();
            if (target != null) {
                // merge는 합친 새 값을 돌려준다. 줄인 뒤 0이면 그 대상으로 바뀌어 오는 종류가 모두 처리된 것이라 큐에 넣는다
                if (incoming.merge(target, -1, Integer::sum) == 0) {
                    ready.add(target);
                }
            }
        }

        return order;
    }
}
