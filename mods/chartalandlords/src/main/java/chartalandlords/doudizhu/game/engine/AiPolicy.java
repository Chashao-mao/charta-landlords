package chartalandlords.doudizhu.game.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 斗地主 AI 的策略内核：<b>完全不引用 Minecraft / Charta</b>，只吃点数数组
 * （3..10 = 3..10，J/Q/K/A = 11/12/13/14，2 = 15，小王 = 16，大王 = 17）。
 *
 * <p>把策略和「牌」分开之后，{@code tools/logic-check.ps1} 就能在类路径上完全没有游戏的情况下
 * 穷举校验 AI 的每一个决策分支；{@link DoudizhuAi} 只剩一层 Card ↔ 点数的适配。</p>
 *
 * <p>设计：</p>
 * <ul>
 *   <li><b>叫分</b>：{@link HandShape#controlScore} 控场分映射到 0..3，永远不叫得比当前最高分低。</li>
 *   <li><b>首出</b>：先试「一手走完」；否则在候选里选「走完之后剩的手数最少」的一手，
 *       同分时偏好长牌型（顺子/连对/飞机/三带）&gt; 三张 &gt; 对子 &gt; 单张，并惩罚拆对/拆三/拆炸、
 *       领出 2 与大小王、留下更多死单。</li>
 *   <li><b>跟牌</b>：能一手走完就走完；队友的强牌不压；否则用「最便宜的安全压牌」
 *       （最小点数且不拆对/拆三）；只有在上一手是炸弹、对手只剩 ≤2 张、地主（自己是农民时）
 *       马上要走完、或者这一炸能直接走完时，才动炸弹/王炸，而且绝不炸队友。</li>
 *   <li><b>性能</b>：{@link HandShape#minMoves} 有 {@code long} 打包的记忆化缓存与展开预算，
 *       所有入口都是纯函数式、可重入、无随机数；同样的手牌与上下文一定得到同样的结果。</li>
 *   <li><b>难度档位</b>：所有数值都来自 {@link AiProfile}（叫分门槛 / 炸弹触发线 / 领牌与超压惩罚 /
 *       评估预算），不带档位的重载统一走 {@link AiProfile#balanced()}；换档只换这张表，不换策略代码。</li>
 * </ul>
 */
public final class AiPolicy {

    private AiPolicy() {}

    // 叫分阈值、领牌惩罚、超压惩罚、炸弹触发线等全部来自 AiProfile —— 难度不写死在策略里。

    /** 跟牌时最多同时考察的候选数。 */
    private static final int MAX_FOLLOW_CANDIDATES = 256;
    /** 首出评分里「少一手」值多少分：1 手 = 256 分，明显小于领炸弹/领控场牌的惩罚。 */
    private static final long MOVE_WEIGHT = 256L;
    /** 「手牌很小」的门槛：这么大时领炸弹/控场牌往往是正确的。 */
    private static final int TINY_HAND = 6;
    /**
     * 封杀模式下，出「对手一只手接不住」的牌型（张数比他手里的还多）给多少奖励。
     *
     * <p>这个门槛由 {@link AiProfile#blockAt()} 给出：保守要等对手只剩 1 张才急，
     * 激进剩 3 张就开始封——这是三个难度档位在「跟牌」上最明显的行为差异。</p>
     */
    private static final long BLOCK_SHAPE_BONUS = 400L;
    /** 封杀模式下，只能出单张时按点数给反向权重：点数越大扣得越少，于是会优先出大牌。 */
    private static final long BLOCK_RANK_WEIGHT = 40L;
    /** 进入残局（手牌不超过这么多张）后，跟牌要顺带看「走完之后还剩几手」。 */
    private static final int ENDGAME_HAND = 10;
    /** 残局里最多做多少次「最少手数」评估，避免候选一多就退化成一棵搜索树。 */
    private static final int ENDGAME_EVALUATION_CAP = 12;
    /** 手指针里最多保留多少个「干净」候选做残局评估。 */
    private static final int ENDGAME_CANDIDATE_CAP = 24;

    /** 默认档位；所有不带档位的重载都走它。 */
    private static final AiProfile DEFAULT_PROFILE = AiProfile.balanced();

    // ------------------------------------------------------------------ 手牌强弱

    /**
     * 控场分的基线：同样一手牌，4 人局 25 张的控场分天然比 3 人局 17 张高一截
     * （多出来的 2 / 王 / 炸弹更多，死单也更多）。
     *
     * <p>系数 5/4 是实测出来的：随机发 20 万手牌，3 人局 17 张的平均控场分正好是 2，
     * 4 人局 25 张正好是 12，两点连线就是 {@code 2 + (handSize - 17) * 5 / 4}。
     * 叫分与抢地主的门槛全部相对它来量，两种人数下才有差不多的触发概率。</p>
     */
    public static int strengthBaseline(int handSize) {
        return 2 + Math.max(0, handSize - 17) * 5 / 4;
    }

    /** 手牌强度（控场分，越大越值得叫地主）。 */
    public static int strength(int[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        return HandShape.controlScore(values);
    }

    /** 相对强弱：控场分减去同张数的基线，可正可负。叫分 / 抢地主都用它做门槛。 */
    public static int strengthMargin(int[] values) {
        if (values == null || values.length == 0) {
            return Integer.MIN_VALUE;
        }
        return strength(values) - strengthBaseline(values.length);
    }

    // ------------------------------------------------------------------ 叫分

    /** @return 0 表示不叫，否则 1..3；必须高于当前最高分 */
    public static int chooseBid(int[] values, int currentHighest) {
        return chooseBid(values, currentHighest, DEFAULT_PROFILE);
    }

    /** 带档位的叫分：门槛由 {@link AiProfile} 决定（保守叫得少、激进叫得多）。 */
    public static int chooseBid(int[] values, int currentHighest, AiProfile profile) {
        AiProfile tuned = profile == null ? DEFAULT_PROFILE : profile;
        int margin = strengthMargin(values);
        int want = margin >= tuned.bidThree() ? 3
                : margin >= tuned.bidTwo() ? 2
                : margin >= tuned.bidOne() ? 1 : 0;
        return want > currentHighest ? want : 0;
    }

    // ------------------------------------------------------------------ 抢地主

    /**
     * 抢地主模式下「抢 / 不抢」的纯逻辑判断。
     *
     * <p>和叫分的区别在于<b>代价随人数变化</b>：叫分是「分高者得」，抢地主是「每多一个人抢，
     * 这一局的倍数就翻一倍」。所以同一个门槛不能一路用到底：</p>
     * <ul>
     *   <li>{@code grabsSoFar} 每多 1，门槛抬高 {@link AiProfile#grabStep()}——倍数越大，
     *       用一个中等牌去当地主越不划算；</li>
     *   <li>自己已经是<b>最后一个</b>还没决定的人时门槛降低
     *       {@link AiProfile#grabLastDiscount()}——没人能再抬价了，抢下来就是确定当地主。</li>
     * </ul>
     *
     * <p>门槛全部相对 {@link #strengthBaseline} 量，所以 3 人局与 4 人局的触发概率一致。</p>
     *
     * @param values       手牌点数
     * @param grabsSoFar   已经有多少人抢过
     * @param lastToDecide 自己是不是最后一个还没决定的人
     */
    public static boolean chooseGrab(int[] values, int grabsSoFar, boolean lastToDecide, AiProfile profile) {
        if (values == null || values.length == 0) {
            return false;
        }
        AiProfile tuned = profile == null ? DEFAULT_PROFILE : profile;
        int threshold = tuned.grabBase() + Math.max(0, grabsSoFar) * tuned.grabStep();
        if (lastToDecide) {
            threshold -= tuned.grabLastDiscount();
        }
        return strengthMargin(values) >= threshold;
    }

    /** 兼容旧签名的抢地主判断（均衡档、不考虑抢的次数）。 */
    public static boolean chooseGrab(int[] values) {
        return chooseGrab(values, 0, false, DEFAULT_PROFILE);
    }

    // ------------------------------------------------------------------ 首出

    /**
     * 首出的纯逻辑版本。
     *
     * @param unseenCounts 未露面张数（下标 = 点数）；用于奖励「外面已经压不动」的安全首出，null = 不统计
     * @return 点数数组；手牌为空返回 null
     */
    public static int[] chooseLeadValues(int[] values, RuleOptions options, int[] unseenCounts) {
        return chooseLeadValues(values, options, unseenCounts, DEFAULT_PROFILE);
    }

    /** 带档位的首出：候选评估数量上限、领牌惩罚全部来自 {@link AiProfile}。 */
    public static int[] chooseLeadValues(int[] values, RuleOptions options, int[] unseenCounts, AiProfile profile) {
        return chooseLeadValues(values, options, unseenCounts, -1, profile);
    }

    /**
     * 带档位与对手手牌数的首出。
     *
     * @param minOpponentHandSize 对手里最少还剩几张（-1 = 未知）；快走完时会改走「封杀」评分
     */
    public static int[] chooseLeadValues(int[] values, RuleOptions options, int[] unseenCounts,
                                         int minOpponentHandSize, AiProfile profile) {
        if (values == null || values.length == 0) {
            return null;
        }
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        AiProfile tuned = profile == null ? DEFAULT_PROFILE : profile;

        // 能一手走完就走完
        if (RuleEngine.classify(values, rule) != null) {
            return values.clone();
        }

        List<int[]> leads = RuleEngine.findLeads(values, rule, true);
        List<LeadCandidate> ranked = new ArrayList<>(leads.size());
        for (int[] lead : leads) {
            Combo combo = RuleEngine.classify(lead, rule);
            if (combo == null) {
                continue;
            }
            ranked.add(new LeadCandidate(lead, combo, cheapLeadOrder(combo)));
        }
        ranked.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order)
                : compareSignatures(a.lead, b.lead));

        int[] best = null;
        long bestScore = Long.MAX_VALUE;
        int evaluated = 0;
        for (LeadCandidate candidate : ranked) {
            if (evaluated++ >= tuned.leadEvaluationCap()) {
                break;
            }
            int[] rest = RuleEngine.minus(values, candidate.lead);
            long score = (long) HandShape.minMoves(rest, rule) * MOVE_WEIGHT
                    + leadPenalty(values, rest, candidate.lead, candidate.combo, unseenCounts,
                            minOpponentHandSize, rule, tuned);
            if (score < bestScore || (score == bestScore && best != null && compareSignatures(candidate.lead, best) < 0)) {
                bestScore = score;
                best = candidate.lead;
            }
        }
        if (best == null) {
            // 兜底：手上最小的单张
            best = new int[]{values[0]};
        }
        return best;
    }

    // ------------------------------------------------------------------ 跟牌

    /**
     * 跟牌 / 首出统一入口的纯逻辑版本。
     *
     * @return 点数数组；null 表示不出
     */
    public static int[] choosePlayValues(int[] values, AiContext context, RuleOptions options) {
        return choosePlayValues(values, context, options, DEFAULT_PROFILE);
    }

    /** 带档位的跟牌：炸弹触发线、超压惩罚来自 {@link AiProfile}。 */
    public static int[] choosePlayValues(int[] values, AiContext context, RuleOptions options, AiProfile profile) {
        if (values == null || values.length == 0) {
            return null;
        }
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        AiProfile tuned = profile == null ? DEFAULT_PROFILE : profile;
        AiContext ctx = context == null ? AiContext.lead(false, -1, null) : context;
        if (ctx.previous() == null) {
            return chooseLeadValues(values, rule, ctx.unseenCounts(), ctx.minOpponentHandSize(), tuned);
        }
        List<int[]> beats = RuleEngine.findBeats(values, ctx.previous(), rule, true);
        if (beats.isEmpty()) {
            return null;
        }

        // 1) 能一手走完就走完（先普通牌型，后炸弹，取点数最小的）
        int[] finisher = null;
        int finisherScore = Integer.MAX_VALUE;
        for (int[] candidate : beats) {
            if (candidate.length != values.length) {
                continue;
            }
            Combo combo = RuleEngine.classify(candidate, rule);
            if (combo == null) {
                continue;
            }
            int score = combo.key() * 4 + combo.size() + (combo.isNuke() ? 1000 : 0);
            if (score < finisherScore) {
                finisherScore = score;
                finisher = candidate;
            }
        }
        if (finisher != null) {
            return finisher;
        }

        boolean landlord = ctx.landlord();
        boolean landlordDanger = !landlord && ctx.landlordHandSize() >= 0 && ctx.landlordHandSize() <= tuned.bombAt();
        boolean singleOrPair = ctx.previous().type() == ComboType.SINGLE || ctx.previous().type() == ComboType.PAIR;

        // 2) 农民不压队友的强牌；地主只剩 ≤2 张且队友出的是单/对时，必须顶住
        if (ctx.teammateLed() && !landlord) {
            boolean teammateStrong = ctx.previous().isNuke()
                    || ctx.previous().key() >= RuleEngine.TWO_VALUE
                    || (ctx.teammateHandSize() >= 0 && ctx.teammateHandSize() <= 2);
            if (teammateStrong && !(landlordDanger && singleOrPair)) {
                return null;
            }
        }

        List<int[]> normal = new ArrayList<>();
        List<int[]> bombs = new ArrayList<>();
        for (int[] candidate : beats) {
            Combo combo = RuleEngine.classify(candidate, rule);
            if (combo == null) {
                continue;
            }
            if (combo.isNuke()) {
                // 炸弹候选很少，永远全部保留
                bombs.add(candidate);
            } else if (normal.size() < MAX_FOLLOW_CANDIDATES) {
                normal.add(candidate);
            }
        }

        // 3) 普通牌型：最便宜的安全压牌
        if (!normal.isEmpty()) {
            // 3a) 对手只剩一两张：出小牌等于把这一局送出去，改成「封杀」——优先出他们一只手接不住的牌，
            //     实在只能出单张时出最大的。队友刚出的牌不抢（那会把自己人顶掉）。
            boolean blocking = !ctx.teammateLed()
                    && ctx.minOpponentHandSize() >= 0
                    && ctx.minOpponentHandSize() <= tuned.blockAt();
            if (blocking) {
                int[] block = pickBlockingBeater(normal, ctx.unseenCounts(), rule);
                if (block != null) {
                    return block;
                }
            }
            int[] chosen = findCheapestSafeBeater(values, normal, rule, tuned);
            if (chosen != null) {
                return chosen;
            }
        }

        // 4) 炸弹兜底：绝不炸队友
        if (ctx.teammateLed() && !landlord) {
            return null;
        }
        if (bombs.isEmpty()) {
            return null;
        }
        boolean bombWins = false;
        for (int[] bomb : bombs) {
            if (bomb.length == values.length) {
                bombWins = true;
                break;
            }
        }
        boolean forced = ctx.previous().isNuke()
                || (ctx.minOpponentHandSize() >= 0 && ctx.minOpponentHandSize() <= tuned.bombAt())
                || landlordDanger
                || bombWins;
        if (!forced) {
            return null;
        }
        return pickCheapestBomb(bombs, ctx.unseenCounts(), rule);
    }

    /**
     * 最便宜的安全压牌：优先「不拆对/拆三/拆炸、也不烧 2/王当带牌」里点数最小的那手；
     * 如果全都得拆，就退化成点数最小、其次超压最小（{@link HandShape#isSevereOvershoot}）的一手。
     *
     * @return 点数数组；候选为空返回 null
     */
    public static int[] findCheapestSafeBeater(int[] hand, List<int[]> candidates, RuleOptions options) {
        return findCheapestSafeBeater(hand, candidates, options, DEFAULT_PROFILE);
    }

    /** 带档位的选压：超压惩罚越重越不愿意用大牌去顶小牌。 */
    public static int[] findCheapestSafeBeater(int[] hand, List<int[]> candidates, RuleOptions options,
                                               AiProfile profile) {
        AiProfile tuned = profile == null ? DEFAULT_PROFILE : profile;
        List<int[]> safe = new ArrayList<>(ENDGAME_CANDIDATE_CAP);
        int[] cheapestAny = null;
        int cheapestKey = Integer.MAX_VALUE;
        for (int[] candidate : candidates) {
            Combo combo = RuleEngine.classify(candidate, options);
            if (combo == null) {
                continue;
            }
            if (cheapestAny == null || compareCandidates(combo, candidate, cheapestAny, options) < 0) {
                cheapestAny = candidate;
                cheapestKey = combo.key();
            }
            if (HandShape.breaksSet(hand, candidate) || HandShape.burnsControlAsAccessory(hand, candidate)) {
                continue;
            }
            if (safe.size() < ENDGAME_CANDIDATE_CAP) {
                safe.add(candidate);
            }
        }
        if (!safe.isEmpty()) {
            return pickCheapestSafe(hand, safe, options, tuned);
        }
        if (cheapestAny == null) {
            return null;
        }
        // 只能拆牌：在「拆牌」候选里选超压最小的
        int[] best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int[] candidate : candidates) {
            Combo combo = RuleEngine.classify(candidate, options);
            if (combo == null) {
                continue;
            }
            int score = combo.key() * 4 + combo.size()
                    + (HandShape.isSevereOvershoot(combo.key(), cheapestKey) ? tuned.overshootPenalty() : 0);
            if (score < bestScore || (score == bestScore && best != null && compareSignatures(candidate, best) < 0)) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? cheapestAny : best;
    }

    /**
     * 在「干净」候选里挑一手：默认是点数最小的那手，<b>但残局要顺带看走完之后还剩几手</b>。
     *
     * <p>原来的实现只看「压得便不便宜」，完全不看自己剩下的牌长什么样。典型翻车：
     * 手里是 {@code 7 8 9 10 J} 的顺子加一张 A，跟一张单牌时出了 7，把顺子拆成五张散牌；
     * 其实出 A 只多花一张牌，却能把顺子整整齐齐留到下一手走完。</p>
     *
     * <p>为了不把决策变成搜索树，只在<b>手牌已经不多</b>（{@link #ENDGAME_HAND}）时才做这项评估，
     * 而且最多评估 {@link #ENDGAME_EVALUATION_CAP} 个候选。评估结果只有在「明显更少手」时才会
     * 推翻原来的最便宜选择，否则一律保持原行为。</p>
     */
    private static int[] pickCheapestSafe(int[] hand, List<int[]> safe, RuleOptions options, AiProfile profile) {
        int[] cheapest = null;
        for (int[] candidate : safe) {
            Combo combo = RuleEngine.classify(candidate, options);
            if (combo == null) {
                continue;
            }
            if (cheapest == null || compareCandidates(combo, candidate, cheapest, options) < 0) {
                cheapest = candidate;
            }
        }
        if (cheapest == null || hand.length > ENDGAME_HAND) {
            return cheapest;
        }
        List<int[]> ordered = new ArrayList<>(safe);
        ordered.sort(AiPolicy::compareSignatures);
        int movesOfCheapest = HandShape.minMoves(RuleEngine.minus(hand, cheapest), options);
        int bestMoves = movesOfCheapest;
        int[] better = null;
        int evaluated = 0;
        for (int[] candidate : ordered) {
            if (evaluated++ >= ENDGAME_EVALUATION_CAP) {
                break;
            }
            int moves = HandShape.minMoves(RuleEngine.minus(hand, candidate), options);
            if (moves < bestMoves) {
                bestMoves = moves;
                better = candidate;
            } else if (moves == bestMoves && better != null) {
                Combo combo = RuleEngine.classify(candidate, options);
                if (combo != null && compareCandidates(combo, candidate, better, options) < 0) {
                    better = candidate;
                }
            }
        }
        // 只有「真的少一手」才推翻最便宜的那手；手数一样就保持原行为
        return better != null ? better : cheapest;
    }

    /**
     * 封杀选牌：对手只剩一两张时，出小牌等于把这一局送出去。
     *
     * <p>两条优先：</p>
     * <ol>
     *   <li>先用 {@link HandShape#isUnbeatableByUnseen} 找「外面根本压不动」的候选——那是确定能封死的，
     *       在其中挑最省的一手，不浪费大牌；</li>
     *   <li>封不死就出<b>最大</b>的那手：他手上牌少，越大越可能接不住。</li>
     * </ol>
     */
    private static int[] pickBlockingBeater(List<int[]> candidates, int[] unseenCounts, RuleOptions options) {
        int[] unbeatable = null;
        int unbeatableScore = Integer.MAX_VALUE;
        int[] strongest = null;
        int strongestScore = Integer.MIN_VALUE;
        for (int[] candidate : candidates) {
            Combo combo = RuleEngine.classify(candidate, options);
            if (combo == null) {
                continue;
            }
            if (HandShape.isUnbeatableByUnseen(combo, unseenCounts, options)) {
                int score = combo.key() * 4 + combo.size();
                if (score < unbeatableScore) {
                    unbeatableScore = score;
                    unbeatable = candidate;
                }
            }
            // 张数少、点数大 = 更难被接住
            int strength = combo.key() * 4 - combo.size();
            if (strength > strongestScore || (strength == strongestScore && strongest != null
                    && compareSignatures(candidate, strongest) > 0)) {
                strongestScore = strength;
                strongest = candidate;
            }
        }
        return unbeatable != null ? unbeatable : strongest;
    }

    /** 炸弹里挑最省的：张数少优先，其次点数小；外面压不动的那一炸排前面。 */
    private static int[] pickCheapestBomb(List<int[]> bombs, int[] unseenCounts, RuleOptions options) {
        int[] best = null;
        long bestScore = Long.MAX_VALUE;
        for (int[] candidate : bombs) {
            Combo combo = RuleEngine.classify(candidate, options);
            if (combo == null) {
                continue;
            }
            long score = (long) combo.size() * 1024L + combo.key() * 8L;
            if (HandShape.isUnbeatableByUnseen(combo, unseenCounts, options)) {
                score -= 4096L;
            }
            if (score < bestScore || (score == bestScore && best != null && compareSignatures(candidate, best) < 0)) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int compareCandidates(Combo combo, int[] candidate, int[] reference, RuleOptions options) {
        Combo other = RuleEngine.classify(reference, options);
        if (other == null) {
            return -1;
        }
        if (combo.key() != other.key()) {
            return Integer.compare(combo.key(), other.key());
        }
        if (combo.size() != other.size()) {
            return Integer.compare(combo.size(), other.size());
        }
        return compareSignatures(candidate, reference);
    }

    // ------------------------------------------------------------------ 首出评分

    /** 粗排：长牌型优先、点数小优先；只用于决定哪些候选值得做 minMoves 评估。 */
    private static int cheapLeadOrder(Combo combo) {
        return leadTypePenalty(combo) * 64 + combo.key() * 4 + combo.size();
    }

    private static int leadTypePenalty(Combo combo) {
        return switch (combo.type()) {
            case STRAIGHT, DOUBLE_STRAIGHT, AIRPLANE, AIRPLANE_SINGLE, AIRPLANE_PAIR,
                 TRIPLE_SINGLE, TRIPLE_PAIR, FOUR_TWO_SINGLE, FOUR_TWO_PAIR -> 0;
            case TRIPLE -> 1;
            case PAIR -> 2;
            case SINGLE -> 3;
            case BOMB, ROCKET -> 9;
        };
    }

    /** 首出细评分：留下的死单、是否拆牌、是否领出控场牌、是否烧王当带牌、对手是不是快走完了。 */
    private static long leadPenalty(int[] hand, int[] rest, int[] lead, Combo combo, int[] unseenCounts,
                                    int minOpponentHandSize, RuleOptions options, AiProfile profile) {
        AiProfile tuned = profile == null ? DEFAULT_PROFILE : profile;
        int weakDelta = HandShape.countWeakSingles(rest) - HandShape.countWeakSingles(hand);
        boolean tiny = hand.length <= TINY_HAND;
        long penalty = (long) weakDelta * 40L + combo.key() + leadValuePenalty(combo.type());
        if (HandShape.breaksSet(hand, lead)) {
            penalty += tuned.breakSetPenalty();
        }
        if (combo.isNuke() || HandShape.spendsBomb(lead)) {
            // 领炸弹 / 王炸 / 四带二都等于把炸弹打出去
            penalty += tiny ? 200L : tuned.nukePenalty();
        }
        if (combo.key() >= RuleEngine.TWO_VALUE && !combo.isNuke()) {
            penalty += tiny ? 0L : tuned.controlPenalty();
        }
        if (HandShape.burnsControlAsAccessory(hand, lead)) {
            penalty += tiny ? 0L : 200L;
        }
        if (HandShape.isUnbeatableByUnseen(combo, unseenCounts, options)) {
            penalty -= 60L;
        }
        // 对手只剩一两张：出小牌等于把这一局送出去。
        // 优先出「他一只手接不住」的牌型（张数比他的手牌还多，比如他剩 1 张时出一对/顺子），
        // 如果候选里只有单张可选，就把「尽量出大」反过来当成加分项——这正是平时最忌讳的打法，
        // 但在这种局面下是唯一正确的选择。
        if (minOpponentHandSize >= 0 && minOpponentHandSize <= tuned.blockAt()) {
            if (combo.size() > minOpponentHandSize) {
                penalty -= BLOCK_SHAPE_BONUS;
            } else {
                penalty += (long) (RuleEngine.MAX_VALUE - combo.key()) * BLOCK_RANK_WEIGHT;
            }
        }
        return penalty;
    }

    /** 同分时的牌型偏好：顺子/连对/飞机/三带最优先，其次三张、对子、单张（单张 +200、对子 +100）。 */
    private static int leadValuePenalty(ComboType type) {
        return switch (type) {
            case STRAIGHT, DOUBLE_STRAIGHT, AIRPLANE, AIRPLANE_SINGLE, AIRPLANE_PAIR,
                 TRIPLE_SINGLE, TRIPLE_PAIR, FOUR_TWO_SINGLE, FOUR_TWO_PAIR, BOMB, ROCKET -> 0;
            case TRIPLE -> 50;
            case PAIR -> 100;
            case SINGLE -> 200;
        };
    }

    // ------------------------------------------------------------------ 小工具

    private static final class LeadCandidate {

        private final int[] lead;
        private final Combo combo;
        private final int order;

        private LeadCandidate(int[] lead, Combo combo, int order) {
            this.lead = lead;
            this.combo = combo;
            this.order = order;
        }
    }

    /** 点数组的稳定全序：先比点数向量，再比长度，保证结果确定。 */
    private static int compareSignatures(int[] first, int[] second) {
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
}
