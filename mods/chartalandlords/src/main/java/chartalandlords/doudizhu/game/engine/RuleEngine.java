package chartalandlords.doudizhu.game.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 斗地主牌型引擎（纯逻辑，零 Minecraft / Charta 依赖）。
 *
 * <p>全部输入输出都是「点数数组」：3..10 → 3..10，J=11，Q=12，K=13，A=14，2=15，小王=16，大王=17。
 * 数组里每个元素代表一张牌，顺序无所谓（内部按点数计数处理）。这样牌型判定与候选生成可以在
 * 完全没有游戏环境的情况下被普通 {@code javac}/{@code java} 程序直接调用。</p>
 *
 * <p>比较规则：同牌型同长度比关键点数；炸弹压任意非炸弹；炸弹之间张数多者大，张数相同比点数；
 * 王炸最大（王炸之间不能互压）。四带二只能与四带二（同子类型）比较。</p>
 */
public final class RuleEngine {

    private RuleEngine() {}

    // ------------------------------------------------------------------ 点数体系（全项目唯一真源）

    /** 牌点下界（3）。 */
    public static final int MIN_VALUE = 3;
    /** 牌点上界（大王）。 */
    public static final int MAX_VALUE = 17;
    /** 顺子/连对/飞机允许的最大点数（A）。 */
    public static final int CHAIN_MAX_VALUE = 14;
    public static final int A_VALUE = 14;
    public static final int TWO_VALUE = 15;
    public static final int SMALL_JOKER_VALUE = 16;
    public static final int BIG_JOKER_VALUE = 17;

    /** 顺子最小长度（张）。 */
    public static final int MIN_STRAIGHT = 5;
    /** 连对最小对数。 */
    public static final int MIN_PAIR_STRAIGHT = 3;
    /** 飞机最小三张数。 */
    public static final int MIN_AIRPLANE = 2;

    /** 计数数组长度：下标 0..17。 */
    public static final int RANK_SLOTS = 18;
    private static final int FOUR_GROUP = 4;
    /** 单手牌型最多张数（4 人局地主 33 张，取整到 64 足够）。 */
    private static final int MAX_CANDIDATE_CARDS = 64;

    // ------------------------------------------------------------------ 计数工具

    /** 点数数组 → 计数数组（下标 = 点数，值 = 张数）。 */
    public static int[] counts(int[] values) {
        int[] counts = new int[RANK_SLOTS];
        if (values != null) {
            for (int value : values) {
                if (value >= MIN_VALUE && value <= MAX_VALUE) {
                    counts[value]++;
                }
            }
        }
        return counts;
    }

    /** 手牌点数数组去掉一手牌之后的剩余点数数组（升序）。 */
    public static int[] minus(int[] values, int[] move) {
        int[] counts = counts(values);
        if (move != null) {
            for (int value : move) {
                if (value >= MIN_VALUE && value <= MAX_VALUE && counts[value] > 0) {
                    counts[value]--;
                }
            }
        }
        return toValues(counts);
    }

    /** 计数数组 → 升序点数数组。 */
    public static int[] toValues(int[] counts) {
        int size = 0;
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            size += counts[value];
        }
        int[] result = new int[size];
        int index = 0;
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            for (int i = 0; i < counts[value]; i++) {
                result[index++] = value;
            }
        }
        return result;
    }

    /**
     * 把点数的「张数向量」压进一个 long：每个点数 4 bit（0..15），共 15 个点数 = 60 bit。
     * 用于候选去重与 {@link HandShape} 的 minMoves 记忆化。
     */
    public static long packCounts(int[] counts) {
        long packed = 0L;
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            packed |= ((long) (counts[value] & 0xF)) << ((value - MIN_VALUE) * 4);
        }
        return packed;
    }

    // ------------------------------------------------------------------ 判定

    /** @return 合法牌型描述；非法返回 null。{@code values} 为空或 null 时返回 null。 */
    public static Combo classify(int[] values, RuleOptions options) {
        if (values == null || values.length == 0) {
            return null;
        }
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        int[] counts = counts(values);
        int cards = 0;
        int groups = 0;
        int minValue = -1;
        int maxValue = -1;
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            if (counts[value] > 0) {
                cards += counts[value];
                groups++;
                if (minValue < 0) {
                    minValue = value;
                }
                maxValue = value;
            }
        }
        if (cards == 0) {
            return null;
        }

        // 1) 王炸
        if (rule.fourPlayer()) {
            if (cards == 4 && counts[SMALL_JOKER_VALUE] == 2 && counts[BIG_JOKER_VALUE] == 2) {
                return new Combo(ComboType.ROCKET, BIG_JOKER_VALUE, 1, cards, true);
            }
        } else if (cards == 2 && counts[SMALL_JOKER_VALUE] == 1 && counts[BIG_JOKER_VALUE] == 1) {
            return new Combo(ComboType.ROCKET, BIG_JOKER_VALUE, 1, cards, true);
        }

        // 2) 单点牌：单 / 对 / 三 / 炸弹
        if (groups == 1) {
            int count = counts[maxValue];
            return switch (count) {
                case 1 -> new Combo(ComboType.SINGLE, maxValue, 1, 1, false);
                case 2 -> new Combo(ComboType.PAIR, maxValue, 1, 2, false);
                case 3 -> new Combo(ComboType.TRIPLE, maxValue, 1, 3, false);
                default -> new Combo(ComboType.BOMB, maxValue, 1, count, true);
            };
        }

        // 3) 三带一 / 三带二
        if (rule.threeWithTwo()) {
            if (cards == 4 && groups == 2) {
                int triple = valueOfCount(counts, 3);
                if (triple >= 0) {
                    return new Combo(ComboType.TRIPLE_SINGLE, triple, 1, 4, false);
                }
            }
            if (cards == 5 && groups == 2) {
                int triple = valueOfCount(counts, 3);
                if (triple >= 0 && valueOfCount(counts, 2) >= 0) {
                    return new Combo(ComboType.TRIPLE_PAIR, triple, 1, 5, false);
                }
            }
        }

        // 4) 顺子 / 连对 / 飞机（不含 2 与大小王）
        if (cards >= MIN_STRAIGHT && cards <= rule.maxStraight() && maxValue <= CHAIN_MAX_VALUE
                && isChain(counts, 1, groups, minValue, maxValue)) {
            return new Combo(ComboType.STRAIGHT, maxValue, cards, cards, false);
        }
        if (cards >= MIN_PAIR_STRAIGHT * 2 && cards % 2 == 0 && cards / 2 <= rule.maxPairStraight()
                && cards / 2 == groups && maxValue <= CHAIN_MAX_VALUE
                && isChain(counts, 2, groups, minValue, maxValue)) {
            return new Combo(ComboType.DOUBLE_STRAIGHT, maxValue, cards / 2, cards, false);
        }
        if (cards >= MIN_AIRPLANE * 3 && cards % 3 == 0 && cards / 3 <= rule.maxAirplane()
                && cards / 3 == groups && maxValue <= CHAIN_MAX_VALUE
                && isChain(counts, 3, groups, minValue, maxValue)) {
            return new Combo(ComboType.AIRPLANE, maxValue, cards / 3, cards, false);
        }

        // 5) 飞机带单 / 飞机带对
        Combo airplaneWings = classifyAirplaneWithWings(counts, cards, rule);
        if (airplaneWings != null) {
            return airplaneWings;
        }

        // 6) 四带二
        return rule.fourWithTwo() ? classifyFourWithWings(counts, cards, rule) : null;
    }

    /** 判断 candidate 能否压过 previous（previous 为 null 表示由本家首出）。 */
    public static boolean beats(Combo candidate, Combo previous) {
        if (candidate == null) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        if (candidate.type() == ComboType.ROCKET) {
            // 王炸最大，但王炸之间不能互压
            return previous.type() != ComboType.ROCKET;
        }
        if (previous.type() == ComboType.ROCKET) {
            return false;
        }
        if (candidate.bomb() && !previous.bomb()) {
            // 炸弹（含 4 人局 5..8 张同点）压一切非炸弹，包括四带二
            return true;
        }
        if (previous.bomb() && !candidate.bomb()) {
            return false;
        }
        if (candidate.bomb()) {
            if (candidate.size() != previous.size()) {
                return candidate.size() > previous.size();
            }
            return candidate.key() > previous.key();
        }
        if (candidate.type() != previous.type()) {
            // 四带二只能和同子类型的四带二比较
            return false;
        }
        if (candidate.size() != previous.size() || candidate.length() != previous.length()) {
            return false;
        }
        return candidate.key() > previous.key();
    }

    // ------------------------------------------------------------------ 候选生成

    /** 首出所有合法组合（允许拆炸弹，拆炸弹的候选排在最后）。 */
    public static List<int[]> findLeads(int[] values, RuleOptions options) {
        return findLeads(values, options, true);
    }

    /** 首出所有合法组合。{@code allowBreakBomb=false} 时完全不拆炸弹/不拆大炸弹。 */
    public static List<int[]> findLeads(int[] values, RuleOptions options, boolean allowBreakBomb) {
        return generate(values, null, options, allowBreakBomb);
    }

    /** 能压过 previous 的所有组合（previous 为 null 时等价于 {@link #findLeads}）。 */
    public static List<int[]> findBeats(int[] values, Combo previous, RuleOptions options) {
        return findBeats(values, previous, options, true);
    }

    /** 能压过 previous 的所有组合。{@code allowBreakBomb=false} 时完全不拆炸弹。 */
    public static List<int[]> findBeats(int[] values, Combo previous, RuleOptions options, boolean allowBreakBomb) {
        return generate(values, previous, options, allowBreakBomb);
    }

    /** @return 最小可压的一手（按关键点数从小到大，王炸/炸弹永远排最后）；没有返回 null。 */
    public static int[] hint(int[] values, Combo previous, RuleOptions options) {
        List<int[]> all = hints(values, previous, options);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 所有可压的牌型，按「提示」的廉价度从小到大排序。
     *
     * <p>第一项与 {@link #hint} 完全一致（已由离线 harness 断言），因此界面可以让玩家反复按「提示」
     * 在候选之间轮换：廉价 → 昂贵，最后才是炸弹与王炸。同分时按点数向量做稳定排序，保证结果可复现。</p>
     *
     * @return 去重后的候选（同一组牌只出现一次）；没有返回空列表
     */
    public static List<int[]> hints(int[] values, Combo previous, RuleOptions options) {
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        List<int[]> candidates = previous == null
                ? findLeads(values, rule, true)
                : findBeats(values, previous, rule, true);
        List<int[]> distinct = new ArrayList<>(candidates.size());
        Set<Long> seen = new HashSet<>();
        for (int[] candidate : candidates) {
            if (seen.add(packCounts(counts(candidate)))) {
                distinct.add(candidate);
            }
        }
        distinct.sort((first, second) -> {
            int score = Integer.compare(hintScore(first, rule), hintScore(second, rule));
            return score != 0 ? score : compareValues(first, second);
        });
        return distinct;
    }

    /** 提示用的廉价度：炸弹/王炸永远最后，其次关键点数小、张数少、牌型简单。 */
    private static int hintScore(int[] candidate, RuleOptions rule) {
        Combo combo = classify(candidate, rule);
        if (combo == null) {
            return Integer.MAX_VALUE;
        }
        return (combo.isNuke() ? 100_000 : 0)
                + combo.key() * 64 + combo.size() * 4 + combo.type().ordinal();
    }

    /** 稳定比较：先比升序点数向量，再比长度，保证同分候选的排序可复现。 */
    private static int compareValues(int[] first, int[] second) {
        int[] a = first.clone();
        int[] b = second.clone();
        Arrays.sort(a);
        Arrays.sort(b);
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    // ------------------------------------------------------------------ 生成实现

    private static List<int[]> generate(int[] values, Combo previous, RuleOptions options, boolean allowBreakBomb) {
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        int[] hand = counts(values);
        Gen gen = new Gen(hand, rule, allowBreakBomb, previous);

        // 单 / 对 / 三：同点取 1..3 张都算合法；从 4 张以上同点的组里取牌视为「拆炸弹」，排在最后
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            int count = hand[value];
            if (count == 0) {
                continue;
            }
            for (int take = 1; take <= Math.min(3, count); take++) {
                gen.add(repeat(value, take));
            }
        }

        // 顺子
        for (int length = MIN_STRAIGHT; length <= rule.maxStraight(); length++) {
            addChains(gen, hand, length, 1, keyFloor(previous));
        }
        // 连对
        for (int length = MIN_PAIR_STRAIGHT; length <= rule.maxPairStraight(); length++) {
            addChains(gen, hand, length, 2, keyFloor(previous));
        }
        // 飞机（不带牌）
        for (int length = MIN_AIRPLANE; length <= rule.maxAirplane(); length++) {
            addChains(gen, hand, length, 3, keyFloor(previous));
        }
        // 飞机带单 / 带对
        for (int length = MIN_AIRPLANE; length <= rule.maxAirplaneSingle(); length++) {
            addAirplaneWithWings(gen, hand, length, 1, keyFloor(previous));
        }
        for (int length = MIN_AIRPLANE; length <= rule.maxAirplanePair(); length++) {
            addAirplaneWithWings(gen, hand, length, 2, keyFloor(previous));
        }
        // 三带一 / 三带二
        if (rule.threeWithTwo()) {
            for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
                if (hand[value] < 3 || value <= keyFloor(previous)) {
                    continue;
                }
                boolean[] body = new boolean[RANK_SLOTS];
                body[value] = true;
                int[] wings = pickWings(hand, body, 1, 1);
                if (wings != null) {
                    gen.add(concat(repeat(value, 3), wings));
                }
                int[] pair = pickWings(hand, body, 1, 2);
                if (pair != null) {
                    gen.add(concat(repeat(value, 3), pair));
                }
            }
        }
        // 四带二
        if (rule.fourWithTwo()) {
            for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
                if (hand[value] < FOUR_GROUP || value <= keyFloor(previous)) {
                    continue;
                }
                boolean[] body = new boolean[RANK_SLOTS];
                body[value] = true;
                int[] singles = pickWings(hand, body, 2, 1);
                if (singles != null) {
                    gen.add(concat(repeat(value, FOUR_GROUP), singles));
                }
                if (rule.fourTwoSinglesMayBePair()) {
                    int[] pairAsSingles = pickPairAsTwoSingles(hand, body);
                    if (pairAsSingles != null) {
                        gen.add(concat(repeat(value, FOUR_GROUP), pairAsSingles));
                    }
                }
                int[] pairs = pickWings(hand, body, 2, 2);
                if (pairs != null) {
                    gen.add(concat(repeat(value, FOUR_GROUP), pairs));
                }
            }
        }

        // 炸弹（整组）与王炸
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            int count = hand[value];
            if (count < FOUR_GROUP) {
                continue;
            }
            boolean usable;
            if (previous == null) {
                usable = true;
            } else if (previous.type() == ComboType.ROCKET) {
                usable = false;
            } else if (previous.bomb()) {
                usable = count > previous.size() || (count == previous.size() && value > previous.key());
            } else {
                usable = true;
            }
            if (usable) {
                gen.add(repeat(value, count));
            }
        }
        if (previous == null || previous.type() != ComboType.ROCKET) {
            int[] rocket = rocket(hand, rule);
            if (rocket != null) {
                gen.add(rocket);
            }
        }

        List<int[]> result = new ArrayList<>(gen.normal.size() + gen.lastResort.size());
        result.addAll(gen.normal);
        result.addAll(gen.lastResort);
        return result;
    }

    private static int keyFloor(Combo previous) {
        return previous == null ? -1 : previous.key();
    }

    private static int[] rocket(int[] hand, RuleOptions rule) {
        if (rule.fourPlayer()) {
            if (hand[SMALL_JOKER_VALUE] >= 2 && hand[BIG_JOKER_VALUE] >= 2) {
                return new int[]{SMALL_JOKER_VALUE, SMALL_JOKER_VALUE, BIG_JOKER_VALUE, BIG_JOKER_VALUE};
            }
            return null;
        }
        if (hand[SMALL_JOKER_VALUE] >= 1 && hand[BIG_JOKER_VALUE] >= 1) {
            return new int[]{SMALL_JOKER_VALUE, BIG_JOKER_VALUE};
        }
        return null;
    }

    private static void addChains(Gen gen, int[] hand, int length, int perValue, int minKey) {
        if (length < MIN_AIRPLANE) {
            return;
        }
        for (int start = MIN_VALUE; start + length - 1 <= CHAIN_MAX_VALUE; start++) {
            int end = start + length - 1;
            if (end <= minKey) {
                continue;
            }
            boolean usable = true;
            for (int value = start; value <= end; value++) {
                if (hand[value] < perValue) {
                    usable = false;
                    break;
                }
            }
            if (!usable) {
                continue;
            }
            int[] cards = new int[length * perValue];
            int index = 0;
            for (int value = start; value <= end; value++) {
                for (int i = 0; i < perValue; i++) {
                    cards[index++] = value;
                }
            }
            gen.add(cards);
        }
    }

    /** @param wingSize 1 = 带单（可以全是单张，也可以全是「当单张用」的对子），2 = 带对 */
    private static void addAirplaneWithWings(Gen gen, int[] hand, int length, int wingSize, int minKey) {
        for (int start = MIN_VALUE; start + length - 1 <= CHAIN_MAX_VALUE; start++) {
            int end = start + length - 1;
            if (end <= minKey) {
                continue;
            }
            boolean usable = true;
            boolean[] body = new boolean[RANK_SLOTS];
            for (int value = start; value <= end; value++) {
                if (hand[value] < 3) {
                    usable = false;
                    break;
                }
                body[value] = true;
            }
            if (!usable) {
                continue;
            }
            int[] head = new int[length * 3];
            int index = 0;
            for (int value = start; value <= end; value++) {
                for (int i = 0; i < 3; i++) {
                    head[index++] = value;
                }
            }
            if (wingSize == 1) {
                // 全部单张
                int[] singles = pickWings(hand, body, length, 1);
                if (singles != null) {
                    gen.add(concat(head, singles));
                }
                // 全部对子当单张用（宽松行为：不能和单张混用）
                if (length % 2 == 0) {
                    int[] pairs = pickWings(hand, body, length / 2, 2);
                    if (pairs != null) {
                        gen.add(concat(head, pairs));
                    }
                }
            } else {
                int[] pairs = pickWings(hand, body, length, 2);
                if (pairs != null) {
                    gen.add(concat(head, pairs));
                }
            }
        }
    }

    /**
     * 挑选最便宜的一组带牌。
     *
     * @param needGroups 需要几组
     * @param size       每组几张（1 = 单，2 = 对）
     * @return 点数数组；凑不齐返回 null
     */
    static int[] pickWings(int[] hand, boolean[] body, int needGroups, int size) {
        if (needGroups <= 0) {
            return new int[0];
        }
        boolean[] used = new boolean[RANK_SLOTS];
        int[] chosen = new int[needGroups];
        int count = 0;
        for (int pick = 0; pick < needGroups; pick++) {
            int best = -1;
            int bestPenalty = Integer.MAX_VALUE;
            for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
                if (body[value] || used[value] || hand[value] < size) {
                    continue;
                }
                int penalty = wingPenalty(hand[value], size);
                if (penalty < 0) {
                    continue;
                }
                if (penalty < bestPenalty) {
                    bestPenalty = penalty;
                    best = value;
                }
            }
            if (best < 0) {
                return null;
            }
            used[best] = true;
            chosen[count++] = best;
        }
        int[] result = new int[count * size];
        int index = 0;
        for (int group = 0; group < count; group++) {
            for (int card = 0; card < size; card++) {
                result[index++] = chosen[group];
            }
        }
        return result;
    }

    /** 四带二单的「二」可以是一对：从同一个点数的对子里抽两张。 */
    static int[] pickPairAsTwoSingles(int[] hand, boolean[] body) {
        int best = -1;
        int bestPenalty = Integer.MAX_VALUE;
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            if (body[value] || hand[value] < 2) {
                continue;
            }
            int penalty = wingPenalty(hand[value], 2);
            if (penalty < 0) {
                continue;
            }
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = value;
            }
        }
        return best < 0 ? null : new int[]{best, best};
    }

    /** 带牌惩罚：正好用掉一组最便宜，拆对/拆三其次，拆炸弹最贵（禁止时返回 -1）。 */
    private static int wingPenalty(int groupSize, int wanted) {
        if (groupSize == wanted) {
            return 0;
        }
        if (groupSize >= FOUR_GROUP) {
            return 100;
        }
        return 10;
    }

    // ------------------------------------------------------------------ 判定内部工具

    private static boolean isChain(int[] counts, int perValue, int groups, int minValue, int maxValue) {
        if (groups == 0 || maxValue - minValue + 1 != groups) {
            return false;
        }
        for (int value = minValue; value <= maxValue; value++) {
            if (counts[value] != perValue) {
                return false;
            }
        }
        return true;
    }

    private static int valueOfCount(int[] counts, int wanted) {
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            if (counts[value] == wanted) {
                return value;
            }
        }
        return -1;
    }

    private static Combo classifyAirplaneWithWings(int[] counts, int cards, RuleOptions rule) {
        // 先按带单解释（与原实现一致），再按带对解释
        if (cards % 4 == 0) {
            int length = cards / 4;
            if (length >= MIN_AIRPLANE && length <= rule.maxAirplaneSingle()) {
                Combo combo = findAirplaneWings(counts, cards, length, 1, rule);
                if (combo != null) {
                    return combo;
                }
            }
        }
        if (cards % 5 == 0) {
            int length = cards / 5;
            if (length >= MIN_AIRPLANE && length <= rule.maxAirplanePair()) {
                Combo combo = findAirplaneWings(counts, cards, length, 2, rule);
                if (combo != null) {
                    return combo;
                }
            }
        }
        return null;
    }

    /** @param wingSize 1 = 带单，2 = 带对 */
    private static Combo findAirplaneWings(int[] counts, int cards, int length, int wingSize, RuleOptions rule) {
        for (int start = CHAIN_MAX_VALUE - length + 1; start >= MIN_VALUE; start--) {
            int end = start + length - 1;
            boolean usable = true;
            for (int value = start; value <= end; value++) {
                if (counts[value] != 3) {
                    usable = false;
                    break;
                }
            }
            if (!usable) {
                continue;
            }
            int wingCards = 0;
            int wingGroupSize = -1;
            boolean valid = true;
            int smallJokerWings = 0;
            int bigJokerWings = 0;
            for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
                if (value >= start && value <= end) {
                    continue;
                }
                int used = counts[value];
                if (used == 0) {
                    continue;
                }
                if (wingSize == 1) {
                    // 翅膀只能全是单张或全是对子当单张用，同一手飞机里不能混用
                    if (used > 2 || (wingGroupSize >= 0 && wingGroupSize != used)) {
                        valid = false;
                        break;
                    }
                    wingGroupSize = used;
                } else if (used != 2) {
                    valid = false;
                    break;
                }
                if (value == SMALL_JOKER_VALUE) {
                    smallJokerWings = used;
                } else if (value == BIG_JOKER_VALUE) {
                    bigJokerWings = used;
                }
                wingCards += used;
            }
            if (!valid || wingCards != length * wingSize) {
                continue;
            }
            if (!rule.jokersAsWingPair() && smallJokerWings > 0 && bigJokerWings > 0) {
                continue;
            }
            ComboType type = wingSize == 1 ? ComboType.AIRPLANE_SINGLE : ComboType.AIRPLANE_PAIR;
            return new Combo(type, end, length, cards, false);
        }
        return null;
    }

    private static Combo classifyFourWithWings(int[] counts, int cards, RuleOptions rule) {
        if (cards != 6 && cards != 8) {
            return null;
        }
        int four = -1;
        int fours = 0;
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            if (counts[value] == FOUR_GROUP) {
                fours++;
                four = value;
            }
        }
        if (fours != 1) {
            return null;
        }
        int restCards = 0;
        int restGroups = 0;
        boolean allPairs = true;
        boolean anyTooBig = false;
        boolean bothJokers = counts[SMALL_JOKER_VALUE] > 0 && counts[BIG_JOKER_VALUE] > 0;
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            if (value == four || counts[value] == 0) {
                continue;
            }
            restCards += counts[value];
            restGroups++;
            allPairs &= counts[value] == 2;
            anyTooBig |= counts[value] > 2;
        }
        if (!rule.jokersAsWingPair() && bothJokers) {
            // 大小王不能同时当一副带牌
            return null;
        }
        if (cards == 6 && restCards == 2 && !anyTooBig) {
            if (!rule.fourTwoSinglesMayBePair() && restGroups == 1) {
                // 四带二的「二」不允许是一对
                return null;
            }
            return new Combo(ComboType.FOUR_TWO_SINGLE, four, 1, 6, false);
        }
        if (cards == 8 && restCards == 4 && restGroups == 2 && allPairs) {
            return new Combo(ComboType.FOUR_TWO_PAIR, four, 1, 8, false);
        }
        return null;
    }

    // ------------------------------------------------------------------ 小工具

    private static int[] repeat(int value, int count) {
        int[] result = new int[count];
        Arrays.fill(result, value);
        return result;
    }

    private static int[] concat(int[] first, int[] second) {
        if (second == null || second.length == 0) {
            return first;
        }
        int[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** 候选收集器：普通候选在前，拆炸弹候选在后，并按点数组去重。 */
    private static final class Gen {

        private final int[] hand;
        private final RuleOptions options;
        private final boolean allowBreakBomb;
        private final Combo previous;
        private final List<int[]> normal = new ArrayList<>();
        private final List<int[]> lastResort = new ArrayList<>();
        private final Set<Long> seen = new HashSet<>();

        private Gen(int[] hand, RuleOptions options, boolean allowBreakBomb, Combo previous) {
            this.hand = hand;
            this.options = options;
            this.allowBreakBomb = allowBreakBomb;
            this.previous = previous;
        }

        private void add(int[] ranks) {
            if (ranks.length > MAX_CANDIDATE_CARDS) {
                return;
            }
            boolean splitsBomb = splitsBomb(hand, ranks);
            if (splitsBomb && !allowBreakBomb) {
                return;
            }
            Combo combo = classify(ranks, options);
            if (combo == null) {
                return;
            }
            if (previous != null && !beats(combo, previous)) {
                return;
            }
            if (!seen.add(packCounts(counts(ranks)))) {
                return;
            }
            (splitsBomb ? lastResort : normal).add(ranks);
        }
    }

    /** 这一手是否从某个 4 张以上的同点组里只取走了一部分（即拆炸弹）。 */
    private static boolean splitsBomb(int[] hand, int[] ranks) {
        int[] used = counts(ranks);
        for (int value = MIN_VALUE; value <= MAX_VALUE; value++) {
            if (used[value] > 0 && hand[value] >= FOUR_GROUP && used[value] < hand[value]) {
                return true;
            }
        }
        return false;
    }
}
