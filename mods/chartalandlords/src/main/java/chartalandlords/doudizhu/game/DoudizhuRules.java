package chartalandlords.doudizhu.game;

import chartalandlords.doudizhu.game.engine.RuleEngine;
import chartalandlords.doudizhu.game.engine.RuleOptions;
import chartalandlords.doudizhu.game.engine.Combo;
import dev.lucaargolo.charta.common.game.api.card.Card;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 斗地主牌型的 Card 适配层：所有规则都在纯逻辑的 {@link RuleEngine} 里，
 * 这里只负责 {@code Card -> int -> Card} 的映射，保持对外的旧签名不变。
 *
 * <p>点数体系见 {@link RuleEngine}：3..10 → 3..10，J/Q/K → 11/12/13，A → 14，2 → 15，小王 → 16，大王 → 17。</p>
 */
public final class DoudizhuRules {

    private DoudizhuRules() {}

    /** 按人数取默认规则。 */
    public static RuleOptions optionsFor(boolean fourPlayer) {
        return RuleOptions.of(fourPlayer);
    }

    // ------------------------------------------------------------------ 判定

    /** @return 合法牌型描述；非法返回 null */
    public static Combo classify(List<Card> cards, boolean fourPlayer) {
        return classify(cards, optionsFor(fourPlayer));
    }

    /** @return 合法牌型描述；非法返回 null */
    public static Combo classify(List<Card> cards, RuleOptions options) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        return RuleEngine.classify(DoudizhuValues.valuesOf(cards), options);
    }

    /** 判断 candidate 能否压过 previous（previous 为 null 表示由本家首出）。 */
    public static boolean beats(Combo candidate, Combo previous) {
        return RuleEngine.beats(candidate, previous);
    }

    // ------------------------------------------------------------------ 候选生成

    /** 首出时可用的所有牌型组合。 */
    public static List<List<Card>> findLeads(List<Card> hand, boolean fourPlayer) {
        return findLeads(hand, optionsFor(fourPlayer));
    }

    /** 首出时可用的所有牌型组合。 */
    public static List<List<Card>> findLeads(List<Card> hand, RuleOptions options) {
        return findLeads(hand, options, true);
    }

    /** 首出时可用的所有牌型组合；{@code allowBreakBomb=false} 时完全不拆炸弹。 */
    public static List<List<Card>> findLeads(List<Card> hand, RuleOptions options, boolean allowBreakBomb) {
        if (hand == null || hand.isEmpty()) {
            return new ArrayList<>();
        }
        List<Card> sorted = sortedByRank(hand);
        return mapAll(sorted, RuleEngine.findLeads(DoudizhuValues.sortedValues(hand), options, allowBreakBomb));
    }

    /** 能压过 previous 的所有组合。 */
    public static List<List<Card>> findBeats(List<Card> hand, Combo previous, boolean fourPlayer) {
        return findBeats(hand, previous, optionsFor(fourPlayer));
    }

    /** 能压过 previous 的所有组合。 */
    public static List<List<Card>> findBeats(List<Card> hand, Combo previous, RuleOptions options) {
        return findBeats(hand, previous, options, true);
    }

    /** 能压过 previous 的所有组合；{@code allowBreakBomb=false} 时完全不拆炸弹。 */
    public static List<List<Card>> findBeats(List<Card> hand, Combo previous, RuleOptions options,
                                            boolean allowBreakBomb) {
        if (hand == null || hand.isEmpty()) {
            return new ArrayList<>();
        }
        List<Card> sorted = sortedByRank(hand);
        return mapAll(sorted,
                RuleEngine.findBeats(DoudizhuValues.sortedValues(hand), previous, options, allowBreakBomb));
    }

    /** @return 最小可压的一手；null 表示没有（previous 为 null 时返回最小的首出牌型）。 */
    public static List<Card> hint(List<Card> hand, Combo previous, RuleOptions options) {
        if (hand == null || hand.isEmpty()) {
            return null;
        }
        int[] choice = RuleEngine.hint(DoudizhuValues.sortedValues(hand), previous, options);
        return choice == null ? null : cardsFor(hand, choice);
    }

    // ------------------------------------------------------------------ Card ↔ int 映射

    /** 按点数升序排列手牌（同点数保持原有相对顺序）。 */
    static List<Card> sortedByRank(List<Card> hand) {
        List<Card> sorted = new ArrayList<>(hand);
        sorted.sort(DoudizhuValues.order());
        return sorted;
    }

    private static List<List<Card>> mapAll(List<Card> sortedHand, List<int[]> combos) {
        List<List<Card>> result = new ArrayList<>(combos.size());
        for (int[] combo : combos) {
            List<Card> cards = cardsForSorted(sortedHand, combo);
            if (cards != null) {
                result.add(cards);
            }
        }
        return result;
    }

    /**
     * 把手牌里的真实 Card 对象映射到点数数组上：点数数组会被升序排序后与升序手牌双指针配对，
     * 因此每个点数取到的都是手牌里现成的牌（不会凭空造牌）。
     *
     * @return 对应牌；手牌不够时返回 null
     */
    static List<Card> cardsFor(List<Card> hand, int[] ranks) {
        return cardsForSorted(sortedByRank(hand), ranks);
    }

    private static List<Card> cardsForSorted(List<Card> sortedHand, int[] ranks) {
        int[] needed = ranks.clone();
        Arrays.sort(needed);
        List<Card> cards = new ArrayList<>(needed.length);
        int index = 0;
        for (int rank : needed) {
            while (index < sortedHand.size() && DoudizhuValues.valueOf(sortedHand.get(index)) < rank) {
                index++;
            }
            if (index >= sortedHand.size() || DoudizhuValues.valueOf(sortedHand.get(index)) != rank) {
                return null;
            }
            cards.add(sortedHand.get(index));
            index++;
        }
        return cards;
    }
}
