package chartalandlords.doudizhu.game.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 农民配合装饰策略：套在任意策略外面，实现两条「队友优先」的规矩。
 *
 * <ol>
 *   <li><b>不压队友</b>：上一手是队友打的，除非自己这一手能直接把牌走完，否则不出。
 *       农民之间互相压牌只会把出牌权交回地主，是最常见的自伤。</li>
 *   <li><b>地主快走完就必须压</b>：地主只剩 ≤ 2 张时，被委托的策略如果选择不出，
 *       这里就挑一手最便宜的能压的牌强行压（封杀优先于省牌）。</li>
 * </ol>
 *
 * <p>纯逻辑，零 Minecraft 依赖；由 {@link StrongAi} 统一套在搜索/内置策略外面，
 * 所以离线基准与游戏内行为走的是同一套配合规则。</p>
 */
public final class CooperativePolicy implements AgentPolicy {

    /** 地主剩这么多张时进入「必须封杀」模式。 */
    public static final int BLOCK_AT = 2;

    private final AgentPolicy delegate;

    public CooperativePolicy(AgentPolicy delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return "coop(" + delegate.name() + ")";
    }

    @Override
    public int[] choose(int[] hand, Combo previous, boolean landlord, int seat, Info info) {
        int[] play = delegate.choose(hand, previous, landlord, seat, info);

        // 1) 不压队友（能一把走完除外）
        boolean teammateLed = !landlord && info.lastPlaySeat() >= 0 && info.sameSide(seat, info.lastPlaySeat());
        if (teammateLed && (play == null || play.length != hand.length)) {
            return null;
        }

        // 2) 地主快走完 → 宁可花牌也要压住
        if (!landlord && previous != null && play == null && landlordLeft(info) <= BLOCK_AT) {
            int[] cheapest = cheapestBeat(hand, previous, info.rule());
            if (cheapest != null) {
                return cheapest;
            }
        }
        return play;
    }

    private static int landlordLeft(Info info) {
        int landlord = info.landlord();
        if (landlord < 0 || info.handSizes() == null || landlord >= info.handSizes().length) {
            return Integer.MAX_VALUE;
        }
        return info.handSizes()[landlord];
    }

    /** 最小的能压过 previous 的一手（按「张数少 + 关键点数小」挑）。 */
    private static int[] cheapestBeat(int[] hand, Combo previous, RuleOptions rule) {
        List<int[]> beats = new ArrayList<>(RuleEngine.findBeats(hand, previous, rule));
        int[] best = null;
        int bestSize = Integer.MAX_VALUE;
        int bestKey = Integer.MAX_VALUE;
        for (int[] candidate : beats) {
            Combo combo = RuleEngine.classify(candidate, rule);
            if (combo == null) {
                continue;
            }
            if (combo.size() < bestSize || (combo.size() == bestSize && combo.key() < bestKey)) {
                best = candidate;
                bestSize = combo.size();
                bestKey = combo.key();
            }
        }
        return best;
    }
}