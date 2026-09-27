package chartalandlords.doudizhu.game.engine;

import java.util.Random;

/**
 * 采样器：在只给「公开信息」的前提下，随机补出别人的手牌，得到一个自洽的<b>世界</b>。
 *
 * <p>这正是搜索策略比纯启发式强的原因：默认 AI 只看一手牌的好坏，而代理可以问
 * 「在我不知道别人手牌的所有可能里，这一手平均能赢多少」。</p>
 *
 * <p>采样规则（都只用公开信息）：</p>
 * <ol>
 *   <li>已知底牌强制放进地主手里（底牌翻开后人人可见，不该被随机分给别人）；</li>
 *   <li>剩下的牌洗牌后，按各家<b>真实剩余张数</b>补齐；</li>
 *   <li>数量对不上（说明调用方给的信息自相矛盾）直接抛异常，不做静默兜底。</li>
 * </ol>
 */
public final class Determinizer {

    private Determinizer() {}

    /**
     * 采样一个世界。
     *
     * @param random 随机源（基准里用固定种子，保证可复现）
     * @param me     自己坐哪
     * @param mine   自己的手牌点数
     * @param info   公开信息
     * @return 每家手牌点数（下标 = 座位），自己的那份与 {@code mine} 完全一致
     */
    public static int[][] sample(Random random, int me, int[] mine, AgentPolicy.Info info) {
        int seats = info.seats();
        int[][] hands = new int[seats][RuleEngine.RANK_SLOTS];
        for (int value : mine) {
            hands[me][value]++;
        }
        // 未知牌就是 info.unseen()（= 总牌堆 − 自己手牌 − 已打出 − 底牌）。
        // 它与各家张数是否自洽由调用方（游戏 / 基准）保证，这里只做「不出现负数、不够分」的检查。
        int[] pool = info.unseen().clone();

        // 1) 已知底牌 → 地主（只有翻开之后 info.bottom 才非空）。
        //    注意：unseen 本来就已经把底牌扣掉了，所以这里只把它放进地主手里，不能再从池里减。
        int landlord = info.landlord();
        if (landlord >= 0 && info.bottom() != null) {
            for (int value : info.bottom()) {
                hands[landlord][value]++;
            }
        }

        // 2) 其余未知牌按张数补齐
        int unknown = 0;
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (pool[value] < 0) {
                throw new IllegalStateException("公开信息自相矛盾：点数 " + value + " 的牌比整副牌还多");
            }
            unknown += pool[value];
        }
        int[] cards = new int[unknown];
        int cursor = 0;
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            for (int i = 0; i < pool[value]; i++) {
                cards[cursor++] = value;
            }
        }
        shuffle(cards, random);

        cursor = 0;
        for (int seat = 0; seat < seats; seat++) {
            if (seat == me) {
                continue;
            }
            int need = info.handSizes()[seat] - size(hands[seat]);
            if (need < 0) {
                throw new IllegalStateException("座位 " + seat + " 的手牌数比公开的张数还多");
            }
            for (int i = 0; i < need; i++) {
                if (cursor >= cards.length) {
                    throw new IllegalStateException("公开信息自相矛盾：未知牌不够补满各家张数");
                }
                hands[seat][cards[cursor++]]++;
            }
        }
        if (cursor != cards.length) {
            throw new IllegalStateException("公开信息自相矛盾：还剩 " + (cards.length - cursor) + " 张牌没去处");
        }

        int[][] values = new int[seats][];
        for (int seat = 0; seat < seats; seat++) {
            values[seat] = RuleEngine.toValues(hands[seat]);
        }
        return values;
    }

    private static int size(int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        return total;
    }

    private static void shuffle(int[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
    }
}
