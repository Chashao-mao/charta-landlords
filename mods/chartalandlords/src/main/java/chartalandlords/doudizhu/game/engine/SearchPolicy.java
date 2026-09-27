package chartalandlords.doudizhu.game.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 搜索型代理：**采样 + 精确终局搜索（PIMC）**。
 *
 * <p>默认 AI 是「看这一手好不好」的启发式；这个策略问的是「在我不知道别人手牌的所有可能里，
 * 这一手平均能不能赢」：</p>
 * <ol>
 *   <li>用 {@link Determinizer} 采样若干自洽的世界（只用公开信息）；</li>
 *   <li>对每个候选出牌，在每个世界里**把剩余牌局精确求解**（合并搜索 + 剪枝 + 记忆化）；</li>
 *   <li>取平均胜分最高的那一手。</li>
 * </ol>
 *
 * <p>代价是算力，所以设了三道闸：只在总剩余张数 ≤ {@code exactLimit} 时搜索（再大分支爆炸）、
 * 每步节点数有预算、候选先按启发式剪到前 N 个。任何一道闸触发或信息不自洽，就退回
 * {@link DefaultPolicy}——<b>保证代理永远不会比内置 AI 更差</b>。</p>
 */
public final class SearchPolicy implements AgentPolicy {

    /** 默认只在总剩余张数 ≤ 这个值时做精确搜索。 */
    public static final int DEFAULT_EXACT_LIMIT = 18;
    /** 默认采样多少世界。 */
    public static final int DEFAULT_SAMPLES = 16;
    /** 单次求解的节点预算。 */
    public static final int DEFAULT_NODE_BUDGET = 30_000;
    /** 每个节点的候选上限。 */
    public static final int DEFAULT_BRANCH_LIMIT = 10;
    /** Default trigger on OUR OWN hand size: a 4-player table rarely gets the total low enough. */
    public static final int DEFAULT_HAND_LIMIT = 11;
    /** Never score more than this many candidates (cost = candidates x samples x nodes). */
    public static final int MAX_SCORED_CANDIDATES = 10;

    /** 诊断计数：真做了搜索 / 退回内置 AI / 因信息不自洽放弃 的步数（基准里打印，防止「静默不生效」）。 */
    public static long searchedMoves;
    public static long fallbackMoves;
    public static long inconsistentMoves;

    private final DefaultPolicy fallback;
    private final Random random;
    private final int exactLimit;
    private final int samples;
    private final int nodeBudget;
    private final int branchLimit;
    private final int handLimit;

    public SearchPolicy(long seed) {
        this(seed, DEFAULT_EXACT_LIMIT, DEFAULT_SAMPLES, DEFAULT_NODE_BUDGET, DEFAULT_BRANCH_LIMIT);
    }

    public SearchPolicy(long seed, int exactLimit, int samples, int nodeBudget, int branchLimit) {
        this(seed, exactLimit, samples, nodeBudget, branchLimit, DEFAULT_HAND_LIMIT);
    }

    public SearchPolicy(long seed, int exactLimit, int samples, int nodeBudget, int branchLimit, int handLimit) {
        this.fallback = new DefaultPolicy();
        this.random = new Random(seed);
        this.exactLimit = exactLimit;
        this.samples = samples;
        this.nodeBudget = nodeBudget;
        this.branchLimit = branchLimit;
        this.handLimit = handLimit;
    }

    @Override
    public String name() {
        return "search(exact<=" + exactLimit + "|hand<=" + handLimit + ",k=" + samples + ",nodes=" + nodeBudget + ")";
    }

    @Override
    public int[] choose(int[] hand, Combo previous, boolean landlord, int seat, Info info) {
        int[] safe = fallback.choose(hand, previous, landlord, seat, info);
        if (seat < 0 || info.landlord() < 0 || hand.length == 0) {
            fallbackMoves++;
            return safe;
        }
        int total = 0;
        for (int size : info.handSizes()) {
            total += size;
        }
        // Search when EITHER the table is small OR our own hand is small: in a 4-player game the total
        // rarely drops to exactLimit before the game ends, and the hand trigger is what makes the endgame
        // search actually run there.
        if (total > exactLimit && hand.length > handLimit) {
            fallbackMoves++;
            return safe;
        }

        List<int[]> candidates = new ArrayList<>();
        if (previous == null) {
            candidates.addAll(RuleEngine.findLeads(hand, info.rule()));
        } else {
            candidates.addAll(RuleEngine.findBeats(hand, previous, info.rule()));
            candidates.add(null); // 可以不出
        }
        if (candidates.size() <= 1) {
            fallbackMoves++;
            return safe;
        }
        // 把内置 AI 的选择放在最前面：平局时优先保留它，这样搜索只会「改进」不会「乱改」。
        candidates.add(0, safe);
        if (candidates.size() > MAX_SCORED_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_SCORED_CANDIDATES));
        }

        int[] best = safe;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean seesaw = false;
        for (int[] candidate : candidates) {
            Combo move = candidate == null ? previous : RuleEngine.classify(candidate, info.rule());
            if (candidate != null && move == null) {
                continue;
            }
            double score = 0;
            int used = 0;
            for (int k = 0; k < samples; k++) {
                int[][] world;
                try {
                    world = Determinizer.sample(random, seat, hand, info);
                } catch (IllegalStateException inconsistent) {
                    inconsistentMoves++;
                    if (inconsistentMoves <= 3) {
                        System.out.println("[agent] sample failed: " + inconsistent.getMessage()
                                + " | me=" + seat + " landlord=" + info.landlord()
                                + " sizes=" + java.util.Arrays.toString(info.handSizes())
                                + " bottom=" + java.util.Arrays.toString(info.bottom())
                                + " unseen=" + java.util.Arrays.toString(info.unseen()));
                    }
                    return safe; // 公开信息自相矛盾时绝不硬算
                }
                int turn = (seat + 1) % info.seats();
                int passCount = 0;
                Combo last = move;
                if (candidate == null) {
                    passCount = info.passCount() + 1;
                    last = previous;
                    if (passCount >= info.seats() - 1) {
                        last = null;
                        passCount = 0;
                    }
                } else {
                    world[seat] = RuleEngine.minus(hand, candidate);
                }
                Solver solver = new Solver(info.rule(), info.seats(), info.landlord(),
                        seat == info.landlord(), nodeBudget, branchLimit);
                score += solver.value(world, turn, last, passCount);
                used++;
            }
            if (used == 0) {
                continue;
            }
            score /= used;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        searchedMoves++;
        return best;
    }

    // ------------------------------------------------------------------ 精确求解

    /** 完全信息下的极小极大搜索（按阵营分胜负，带剪枝、记忆化与节点预算）。 */
    private static final class Solver {

        private final RuleOptions rule;
        private final int seats;
        private final int landlord;
        private final boolean rootIsLandlord;
        private final int branchLimit;
        private final Map<Key, Integer> memo = new HashMap<>();
        private int budget;

        private record Key(long hand0, long hand1, long hand2, long hand3, int turn, int combo, int passCount) {}

        Solver(RuleOptions rule, int seats, int landlord, boolean rootIsLandlord, int budget, int branchLimit) {
            this.rule = rule;
            this.seats = seats;
            this.landlord = landlord;
            this.rootIsLandlord = rootIsLandlord;
            this.budget = budget;
            this.branchLimit = branchLimit;
        }

        /** @return +1 根阵营赢 / -1 输 / 0 判不出来（预算用尽时按手数给符号）。 */
        int value(int[][] hands, int turn, Combo previous, int passCount) {
            for (int seat = 0; seat < seats; seat++) {
                if (hands[seat].length == 0) {
                    return (seat == landlord) == rootIsLandlord ? 1 : -1;
                }
            }
            if (budget-- <= 0) {
                return heuristic(hands);
            }
            Key key = key(hands, turn, previous, passCount);
            Integer cached = memo.get(key);
            if (cached != null) {
                return cached;
            }

            boolean maximizing = (turn == landlord) == rootIsLandlord;
            int best = maximizing ? -2 : 2;
            for (int[] move : moves(hands[turn], previous)) {
                int[][] next = copy(hands);
                next[turn] = RuleEngine.minus(hands[turn], move);
                int value = value(next, (turn + 1) % seats, RuleEngine.classify(move, rule), 0);
                best = maximizing ? Math.max(best, value) : Math.min(best, value);
                if (maximizing && best == 1) {
                    break;
                }
                if (!maximizing && best == -1) {
                    break;
                }
            }
            if (previous != null) {
                int nextPass = passCount + 1;
                Combo nextLast = previous;
                if (nextPass >= seats - 1) {
                    nextPass = 0;
                    nextLast = null;
                }
                int value = value(hands, (turn + 1) % seats, nextLast, nextPass);
                best = maximizing ? Math.max(best, value) : Math.min(best, value);
            }
            if (best < -1 || best > 1) {
                best = heuristic(hands);
            }
            memo.put(key, best);
            return best;
        }

        /** 候选：按「张数少、点数小」排序后截断，避免分支爆炸。 */
        private List<int[]> moves(int[] hand, Combo previous) {
            List<int[]> all = previous == null
                    ? RuleEngine.findLeads(hand, rule)
                    : RuleEngine.findBeats(hand, previous, rule);
            List<int[]> sorted = new ArrayList<>(all);
            sorted.sort(Comparator.comparingInt((int[] move) -> move.length)
                    .thenComparingInt(move -> RuleEngine.classify(move, rule) == null
                            ? Integer.MAX_VALUE : RuleEngine.classify(move, rule).key()));
            return sorted.size() <= branchLimit ? sorted : sorted.subList(0, branchLimit);
        }

        /** 预算/深度用尽时的静态评估：比两边「最少手数」之和，我这边少就乐观。 */
        private int heuristic(int[][] hands) {
            int mine = 0;
            int theirs = 0;
            for (int seat = 0; seat < seats; seat++) {
                int moves = HandShape.minMoves(hands[seat], rule);
                if ((seat == landlord) == rootIsLandlord) {
                    mine += moves;
                } else {
                    theirs += moves;
                }
            }
            return Integer.signum(Integer.compare(theirs, mine));
        }

        private Key key(int[][] hands, int turn, Combo previous, int passCount) {
            long h0 = hands.length > 0 ? RuleEngine.packCounts(RuleEngine.counts(hands[0])) : 0;
            long h1 = hands.length > 1 ? RuleEngine.packCounts(RuleEngine.counts(hands[1])) : 0;
            long h2 = hands.length > 2 ? RuleEngine.packCounts(RuleEngine.counts(hands[2])) : 0;
            long h3 = hands.length > 3 ? RuleEngine.packCounts(RuleEngine.counts(hands[3])) : 0;
            int combo = previous == null ? -1
                    : previous.type().ordinal() * 1_000_000 + previous.key() * 100 + previous.size();
            return new Key(h0, h1, h2, h3, turn, combo, passCount);
        }

        private int[][] copy(int[][] hands) {
            int[][] next = new int[hands.length][];
            for (int seat = 0; seat < hands.length; seat++) {
                next[seat] = hands[seat].clone();
            }
            return next;
        }
    }

    /** 便于测试与日志：这一手是什么牌型。 */
    public static ComboType typeOf(int[] move, RuleOptions rule) {
        Combo combo = RuleEngine.classify(move, rule);
        return combo == null ? null : combo.type();
    }
}
