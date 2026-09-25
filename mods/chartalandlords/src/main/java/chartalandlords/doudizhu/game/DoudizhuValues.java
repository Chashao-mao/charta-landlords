package chartalandlords.doudizhu.game;

import chartalandlords.doudizhu.game.engine.RuleEngine;
import chartalandlords.doudizhu.registry.JokerRanks;
import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.card.Rank;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 斗地主自己的点数体系：3..10 → 3..10，J/Q/K → 11/12/13，A → 14，2 → 15，小王 → 16，大王 → 17。
 */
public final class DoudizhuValues {

    private DoudizhuValues() {}

    /** 选择区 / 上一手牌 / 底牌：每个 GUI 行最多展示的牌数。 */
    public static final int ROW_CAPACITY = 10;
    /** 手牌显示为两行。 */
    public static final int HAND_ROWS = 2;
    /** 手牌每行左右两段。 */
    public static final int HAND_SEGMENTS_PER_ROW = 2;
    /** 每段最多 9 张：两行两段共 36 张，足够 4 人局地主 33 张，且单张可见宽度约 14px。 */
    public static final int SEGMENT_CAPACITY = 9;
    /** 出牌选择区 GUI 行数（最长牌型 连对十二连 = 24 张 → 3 行 × 10 张）。 */
    public static final int SELECT_ROWS = 3;
    /** 上一手牌展示行数。 */
    public static final int PLAY_ROWS = 2;

    public static final int MIN_PLAYERS = 3;
    public static final int MAX_PLAYERS = 4;

    public static final int MAX_BID = 3;

    /** 牌点下界（3）。常量真源在 {@link RuleEngine}，这里只是为了向后兼容。 */
    public static final int MIN_VALUE = RuleEngine.MIN_VALUE;
    /** 牌点上界（大王）。 */
    public static final int MAX_VALUE = RuleEngine.MAX_VALUE;
    /** 顺子/连对/飞机允许的最大点数（A）。 */
    public static final int CHAIN_MAX_VALUE = RuleEngine.CHAIN_MAX_VALUE;

    public static final int A_VALUE = RuleEngine.A_VALUE;
    public static final int TWO_VALUE = RuleEngine.TWO_VALUE;
    public static final int SMALL_JOKER_VALUE = RuleEngine.SMALL_JOKER_VALUE;
    public static final int BIG_JOKER_VALUE = RuleEngine.BIG_JOKER_VALUE;

    /** 叫分的人类超时（tick，600 = 30 秒）；出牌不再有超时。 */
    public static final int BID_TIMEOUT_TICKS = 600;

    public static int valueOf(Card card) {
        Rank rank = card.rank();
        if (rank == JokerRanks.BIG_JOKER.get()) {
            return BIG_JOKER_VALUE;
        }
        if (rank == JokerRanks.SMALL_JOKER.get()) {
            return SMALL_JOKER_VALUE;
        }
        if (rank == Ranks.TWO) {
            return TWO_VALUE;
        }
        if (rank == Ranks.ACE) {
            return A_VALUE;
        }
        return rank.ordinal();
    }

    public static boolean isJoker(Card card) {
        return valueOf(card) >= SMALL_JOKER_VALUE;
    }

    public static boolean isJokerRank(Rank rank) {
        return rank == JokerRanks.SMALL_JOKER.get() || rank == JokerRanks.BIG_JOKER.get();
    }

    /** 手牌 → 点数数组，保持原顺序。 */
    public static int[] valuesOf(List<Card> cards) {
        int[] values = new int[cards.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = valueOf(cards.get(i));
        }
        return values;
    }

    /** 手牌 → 升序点数数组。 */
    public static int[] sortedValues(List<Card> cards) {
        int[] values = valuesOf(cards);
        Arrays.sort(values);
        return values;
    }

    public static int suitId(Card card) {
        return Suits.getRegistry().getId(card.suit());
    }

    /** 手牌展示顺序：点数升序，同点数按花色。 */
    public static Comparator<Card> order() {
        return Comparator.comparingInt(DoudizhuValues::valueOf).thenComparingInt(DoudizhuValues::suitId);
    }

    /** 牌堆张数 → 人数（54 张 3 人，108 张 4 人）。 */
    public static int seatCount(Deck deck) {
        return deck.getCards().size() >= 108 ? 4 : 3;
    }

    public static int bottomCount(int seatCount) {
        return seatCount >= 4 ? 8 : 3;
    }

    public static int handSize(int totalCards, int seatCount) {
        return (totalCards - bottomCount(seatCount)) / seatCount;
    }
}
