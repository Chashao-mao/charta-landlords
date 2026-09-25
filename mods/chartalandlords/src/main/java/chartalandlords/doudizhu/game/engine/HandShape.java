package chartalandlords.doudizhu.game.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手牌形状启发式（纯逻辑，零 Minecraft / Charta 依赖）。
 *
 * <p>移植自 esrrhs 的 {@code HandShape}/{@code BidEvaluator} 思路：控场分、死单数、拆牌代价、
 * 最少手数分解。全部输入都是点数数组（见 {@link RuleEngine}）。</p>
 *
 * <p>线程安全：{@link #minMoves} 的记忆化缓存是 {@link ConcurrentHashMap}，读多写少且值只由
 * 状态决定，因此同一个手牌在任何线程、任何时刻都得到同一个结果；缓存满了会整体清空，只影响速度
 * 不影响结果。</p>
 */
public final class HandShape {

    private HandShape() {}

    private static final int BIG_JOKER_BONUS = 4;
    private static final int SMALL_JOKER_BONUS = 3;
    private static final int ROCKET_BONUS = 8;
    private static final int BOMB_BONUS = 6;
    /** 死单惩罚的上界点数（3..9）。 */
    private static final int WEAK_SINGLE_MAX = 9;

    /** minMoves 记忆化上限：超过就整体清空（结果不受影响）。 */
    private static final int MAX_CACHE = 8192;
    /**
     * 单次 minMoves 最多展开多少个状态；用完后退化为贪心分解。
     * 这个数字直接决定一次出牌决策的 tick 开销：4 人局 33 张手牌最多评估 32 手，
     * 预算 600 时最坏约 10ms 量级（本地实测），远低于 50ms 的 tick 预算。
     */
    private static final int EXPANSION_BUDGET = 600;
    /**
     * 超过这么多张手牌（只有 4 人两副牌会到 25..33 张）就直接用贪心分解：
     * 状态空间太大，精确搜索会明显超过一次 tick 的预算，而贪心对「首出该不该拆结构」的判断已经够用。
     */
    private static final int EXACT_CARD_LIMIT = 20;

    private static final ConcurrentHashMap<Long, Integer> MIN_MOVES_CACHE = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 形状指标

    /** 死单：点数 < 2（即 3..A）且手里只有一张的牌。 */
    public static int countWeakSingles(int[] values) {
        int[] counts = RuleEngine.counts(values);
        int weak = 0;
        for (int value = RuleEngine.MIN_VALUE; value < RuleEngine.TWO_VALUE; value++) {
            if (counts[value] == 1) {
                weak++;
            }
        }
        return weak;
    }

    /** 是否有控场牌（2 / 小王 / 大王）。 */
    public static boolean hasControl(int[] values) {
        int[] counts = RuleEngine.counts(values);
        for (int value = RuleEngine.TWO_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (counts[value] > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 控场分（越大越值得叫地主）：
     * 王炸 +8，否则大王 +4 / 小王 +3；2 的张数 1/2/3/4 → +2/+5/+8/+12；
     * 每个炸弹 +6；3..9 每张死单 −1。
     */
    public static int controlScore(int[] values) {
        int[] counts = RuleEngine.counts(values);
        int score = 0;
        boolean smallJoker = counts[RuleEngine.SMALL_JOKER_VALUE] > 0;
        boolean bigJoker = counts[RuleEngine.BIG_JOKER_VALUE] > 0;
        if (smallJoker && bigJoker) {
            score += ROCKET_BONUS;
        } else {
            if (bigJoker) {
                score += BIG_JOKER_BONUS;
            }
            if (smallJoker) {
                score += SMALL_JOKER_BONUS;
            }
        }
        score += switch (counts[RuleEngine.TWO_VALUE]) {
            case 0 -> 0;
            case 1 -> 2;
            case 2 -> 5;
            case 3 -> 8;
            default -> 12;
        };
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (counts[value] >= 4) {
                score += BOMB_BONUS;
            }
        }
        for (int value = RuleEngine.MIN_VALUE; value <= WEAK_SINGLE_MAX; value++) {
            if (counts[value] == 1) {
                score--;
            }
        }
        return score;
    }

    /** 这一手是否拆了对子/三张/炸弹（同点只取走一部分）。 */
    public static boolean breaksSet(int[] hand, int[] move) {
        int[] handCounts = RuleEngine.counts(hand);
        int[] used = RuleEngine.counts(move);
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (used[value] > 0 && handCounts[value] >= 2 && used[value] < handCounts[value]) {
                return true;
            }
        }
        return false;
    }

    /** 这一手是否整组用掉了某个 4 张以上的同点组（炸弹本身、四带二、5 张以上的炸弹）。 */
    public static boolean spendsBomb(int[] move) {
        int[] used = RuleEngine.counts(move);
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (used[value] >= 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否把 2/王 当成带牌烧掉了：主体是点数 &lt; 2 的三张/四张结构，而 2 或王只出了一两张。
     *
     * <p>{@code hand} 只在需要区分「2 本身是不是主体」时才用得到，这里直接按 move 的形状判断，
     * 保留参数以匹配 API。</p>
     */
    public static boolean burnsControlAsAccessory(int[] hand, int[] move) {
        int[] used = RuleEngine.counts(move);
        boolean hasSmallBody = false;
        for (int value = RuleEngine.MIN_VALUE; value < RuleEngine.TWO_VALUE; value++) {
            if (used[value] >= 3) {
                hasSmallBody = true;
                break;
            }
        }
        if (!hasSmallBody) {
            return false;
        }
        for (int value = RuleEngine.TWO_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (used[value] > 0 && used[value] < 3) {
                return true;
            }
        }
        return false;
    }

    /** 关键点数是否比基准高太多（≥2 时差 ≥2，其余差 ≥3 视为严重超压）。 */
    public static boolean isSevereOvershoot(int key, int reference) {
        int threshold = key >= RuleEngine.TWO_VALUE ? 2 : 3;
        return key - reference >= threshold;
    }

    /**
     * 未露面的牌里有没有能压过这一手的（O(18)，不做全牌型搜索）：
     * 王炸永远无解；炸弹看更大的炸弹；非炸弹只看「外面还有没有炸弹 / 王炸」，
     * 单/对/三再额外看有没有更大的同点数散牌。长度类牌型（顺子/连对/飞机）只做炸弹检查。
     *
     * @param unseenCounts 下标 = 点数，值 = 还没露面的张数；null 表示未统计 → 一律返回 false
     */
    public static boolean isUnbeatableByUnseen(Combo combo, int[] unseenCounts, RuleOptions options) {
        if (combo == null || unseenCounts == null) {
            return false;
        }
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        if (combo.type() == ComboType.ROCKET) {
            return true;
        }
        int neededJokers = rule.fourPlayer() ? 2 : 1;
        if (unseenCounts.length > RuleEngine.BIG_JOKER_VALUE
                && unseenCounts[RuleEngine.SMALL_JOKER_VALUE] >= neededJokers
                && unseenCounts[RuleEngine.BIG_JOKER_VALUE] >= neededJokers) {
            return false;
        }
        if (combo.bomb()) {
            for (int value = combo.key() + 1; value <= RuleEngine.MAX_VALUE; value++) {
                if (value < unseenCounts.length && unseenCounts[value] >= combo.size()) {
                    return false;
                }
            }
            return true;
        }
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE && value < unseenCounts.length; value++) {
            if (unseenCounts[value] >= 4) {
                return false;
            }
        }
        int needed = switch (combo.type()) {
            case SINGLE -> 1;
            case PAIR -> 2;
            case TRIPLE -> 3;
            default -> 0;
        };
        for (int value = combo.key() + 1; needed > 0 && value <= RuleEngine.MAX_VALUE; value++) {
            if (value < unseenCounts.length && unseenCounts[value] >= needed) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 最少手数

    /** 最少几手能走完（带记忆化的受限搜索；手牌很大时退化为贪心分解）。 */
    public static int minMoves(int[] values, RuleOptions options) {
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        int[] counts = RuleEngine.counts(values);
        int total = total(counts);
        if (total == 0) {
            return 0;
        }
        if (total > EXACT_CARD_LIMIT) {
            // 大牌型：贪心分解（同样走缓存，结果只取决于牌型）
            long key = RuleEngine.packCounts(counts);
            Integer cached = MIN_MOVES_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            int moves = greedy(counts, rule);
            if (MIN_MOVES_CACHE.size() > MAX_CACHE) {
                MIN_MOVES_CACHE.clear();
            }
            MIN_MOVES_CACHE.put(key, moves);
            return moves;
        }
        int[] budget = {EXPANSION_BUDGET};
        return decompose(counts, rule, budget);
    }

    private static int decompose(int[] counts, RuleOptions rule, int[] budget) {
        long key = RuleEngine.packCounts(counts);
        Integer cached = MIN_MOVES_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (budget[0] <= 0) {
            return greedy(counts, rule);
        }
        budget[0]--;
        // 最优分解里，最小点数的那张牌一定属于某一手；只尝试「包含最小点数」的走法即可，
        // 这能把分支从几十降到个位数，同时不损失最优性。
        int lowest = lowestRank(counts);
        List<int[]> moves = structuralMoves(counts, rule);
        int best = Integer.MAX_VALUE;
        for (int[] move : moves) {
            if (!containsRank(move, lowest)) {
                continue;
            }
            int value = 1 + decompose(subtract(counts, move), rule, budget);
            if (value < best) {
                best = value;
            }
            if (best <= 1) {
                break;
            }
        }
        if (best == Integer.MAX_VALUE) {
            // 理论上不可能（最小点数自己就是一手），兜底退化成贪心
            best = greedy(counts, rule);
        }
        if (MIN_MOVES_CACHE.size() > MAX_CACHE) {
            MIN_MOVES_CACHE.clear();
        }
        MIN_MOVES_CACHE.put(key, best);
        return best;
    }

    private static int lowestRank(int[] counts) {
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (counts[value] > 0) {
                return value;
            }
        }
        return -1;
    }

    private static boolean containsRank(int[] move, int rank) {
        if (rank < 0) {
            return true;
        }
        for (int value : move) {
            if (value == rank) {
                return true;
            }
        }
        return false;
    }

    /** 预算用尽 / 手牌过大时的确定性贪心分解：两种排序各走一遍，取较少手数。 */
    private static int greedy(int[] counts, RuleOptions rule) {
        return Math.min(greedy(counts, rule, false), greedy(counts, rule, true));
    }

    private static int greedy(int[] counts, RuleOptions rule, boolean chainsFirst) {
        int[] current = counts.clone();
        int moves = 0;
        while (total(current) > 0) {
            int[] best = null;
            long bestScore = Long.MIN_VALUE;
            for (int[] move : structuralMoves(current, rule)) {
                long score = (chainsFirst && chainLike(move) ? 1L << 20 : 0L)
                        + (long) move.length * 4096L
                        - (long) brokenGroups(current, move) * 512L
                        - maxRank(move);
                if (score > bestScore) {
                    bestScore = score;
                    best = move;
                }
            }
            if (best == null) {
                break;
            }
            current = subtract(current, best);
            moves++;
        }
        return moves;
    }

    /** 这一手是不是纯顺子 / 连对 / 飞机（没有带牌）。 */
    private static boolean chainLike(int[] move) {
        int[] used = RuleEngine.counts(move);
        int per = -1;
        int groups = 0;
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (used[value] == 0) {
                continue;
            }
            if (per < 0) {
                per = used[value];
            } else if (used[value] != per) {
                return false;
            }
            groups++;
        }
        if (per < 1 || per > 3 || per * groups != move.length) {
            return false;
        }
        int minLength = per == 1 ? RuleEngine.MIN_STRAIGHT : per == 2 ? RuleEngine.MIN_PAIR_STRAIGHT : RuleEngine.MIN_AIRPLANE;
        return groups >= minLength;
    }

    /** 这一手会拆散几个对子/三张/炸弹。 */
    private static int brokenGroups(int[] counts, int[] move) {
        int[] used = RuleEngine.counts(move);
        int broken = 0;
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (used[value] > 0 && counts[value] >= 2 && used[value] < counts[value]) {
                broken++;
            }
        }
        return broken;
    }

    /**
     * 一个状态下值得尝试的「结构化」走法：整组、最长顺子/连对/飞机、三带、四带二、飞机带牌。
     * 只做启发式，不做全枚举，保证 CPU 可预测。
     */
    private static List<int[]> structuralMoves(int[] counts, RuleOptions rule) {
        List<int[]> moves = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (counts[value] > 0) {
                addUnique(moves, seen, repeat(value, counts[value]));
            }
        }
        addChainMoves(moves, seen, counts, 1, rule.maxStraight());
        addChainMoves(moves, seen, counts, 2, rule.maxPairStraight());
        addChainMoves(moves, seen, counts, 3, rule.maxAirplane());

        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (counts[value] < 3) {
                continue;
            }
            boolean[] body = new boolean[RuleEngine.RANK_SLOTS];
            body[value] = true;
            int[] single = RuleEngine.pickWings(counts, body, 1, 1);
            if (single != null) {
                addUnique(moves, seen, concat(repeat(value, 3), single));
            }
            int[] pair = RuleEngine.pickWings(counts, body, 1, 2);
            if (pair != null) {
                addUnique(moves, seen, concat(repeat(value, 3), pair));
            }
        }
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (counts[value] < 4) {
                continue;
            }
            boolean[] body = new boolean[RuleEngine.RANK_SLOTS];
            body[value] = true;
            int[] singles = RuleEngine.pickWings(counts, body, 2, 1);
            if (singles != null) {
                addUnique(moves, seen, concat(repeat(value, 4), singles));
            }
            int[] pairs = RuleEngine.pickWings(counts, body, 2, 2);
            if (pairs != null) {
                addUnique(moves, seen, concat(repeat(value, 4), pairs));
            }
        }
        // 最长飞机 + 带牌
        for (int start = RuleEngine.MIN_VALUE; start + RuleEngine.MIN_AIRPLANE - 1 <= RuleEngine.CHAIN_MAX_VALUE; start++) {
            if (counts[start] < 3 || (start > RuleEngine.MIN_VALUE && counts[start - 1] >= 3)) {
                continue;
            }
            int run = 0;
            while (start + run <= RuleEngine.CHAIN_MAX_VALUE && counts[start + run] >= 3) {
                run++;
            }
            int use = Math.min(run, rule.maxAirplane());
            while (use >= RuleEngine.MIN_AIRPLANE) {
                boolean[] body = new boolean[RuleEngine.RANK_SLOTS];
                int[] head = new int[use * 3];
                int index = 0;
                for (int value = start; value < start + use; value++) {
                    body[value] = true;
                    for (int card = 0; card < 3; card++) {
                        head[index++] = value;
                    }
                }
                addUnique(moves, seen, head);
                if (use <= rule.maxAirplaneSingle()) {
                    int[] wings = RuleEngine.pickWings(counts, body, use, 1);
                    if (wings != null) {
                        addUnique(moves, seen, concat(head, wings));
                    }
                }
                if (use <= rule.maxAirplanePair()) {
                    int[] wings = RuleEngine.pickWings(counts, body, use, 2);
                    if (wings != null) {
                        addUnique(moves, seen, concat(head, wings));
                    }
                }
                use--;
            }
        }
        return moves;
    }

    private static void addChainMoves(List<int[]> moves, Set<Long> seen, int[] counts, int perValue, int cap) {
        for (int start = RuleEngine.MIN_VALUE; start <= RuleEngine.CHAIN_MAX_VALUE; start++) {
            if (counts[start] < perValue
                    || (start > RuleEngine.MIN_VALUE && counts[start - 1] >= perValue)) {
                continue;
            }
            int run = 0;
            while (start + run <= RuleEngine.CHAIN_MAX_VALUE && counts[start + run] >= perValue) {
                run++;
            }
            int use = Math.min(run, cap);
            int minLength = perValue == 1 ? RuleEngine.MIN_STRAIGHT
                    : perValue == 2 ? RuleEngine.MIN_PAIR_STRAIGHT : RuleEngine.MIN_AIRPLANE;
            while (use >= minLength) {
                int[] chain = new int[use * perValue];
                int index = 0;
                for (int value = start; value < start + use; value++) {
                    for (int card = 0; card < perValue; card++) {
                        chain[index++] = value;
                    }
                }
                addUnique(moves, seen, chain);
                use--;
            }
        }
    }

    private static void addUnique(List<int[]> moves, Set<Long> seen, int[] move) {
        if (move == null || move.length == 0 || move.length > 64) {
            return;
        }
        if (seen.add(RuleEngine.packCounts(RuleEngine.counts(move)))) {
            moves.add(move);
        }
    }

    private static int[] subtract(int[] counts, int[] move) {
        int[] result = counts.clone();
        for (int value : move) {
            if (value >= RuleEngine.MIN_VALUE && value <= RuleEngine.MAX_VALUE) {
                result[value]--;
            }
        }
        return result;
    }

    private static int total(int[] counts) {
        int total = 0;
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            total += counts[value];
        }
        return total;
    }

    private static int maxRank(int[] move) {
        int max = RuleEngine.MIN_VALUE;
        for (int value : move) {
            max = Math.max(max, value);
        }
        return max;
    }

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
}
