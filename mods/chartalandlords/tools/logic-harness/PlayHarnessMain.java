package chartalandlords.doudizhu.game.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 玩法层（计分 / 提示循环 / 规则开关）的离线断言程序。
 *
 * <p>和 {@code HarnessMain} / {@code AiHarnessMain} 一样，只吃点数数组、类路径上完全没有 Minecraft，
 * 由 {@code tools/logic-check.ps1} 用裸 {@code javac} + {@code java} 运行。</p>
 *
 * <p>运行：{@code java -cp <classes> chartalandlords.doudizhu.game.PlayHarnessMain}</p>
 */
public final class PlayHarnessMain {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        scoring();
        grabAndDeclareScoring();
        aiDifficultyProfiles();
        hintCycling();
        ruleSwitches();
        equalRanksNeverBeat();        System.out.println("---------------------------------------------");
        System.out.println("PLAY HARNESS " + (failed == 0 ? "PASS" : "FAIL")
                + ": " + passed + " checks, " + failed + " failures");
        if (failed > 0) {
            throw new IllegalStateException(failed + " play checks failed");
        }
    }

    // ------------------------------------------------------------------ 同点数永远不能互压

    /**
     * 玩家反馈「AI 用 10 压 10」——这里把「同牌型 + 同长度 + 同点数永远不能互压」穷举一遍，
     * 覆盖全部牌型与每一个点数（含 2 与大小王），并检查生成器不会给出同点数的候选。
     */
    private static void equalRanksNeverBeat() {
        System.out.println("== 4. equal ranks never beat (player report: 10 over 10) ==");
        RuleOptions std = RuleOptions.standard();
        RuleOptions four = RuleOptions.doubleDeck();
        int compared = 0;
        int selfBeats = 0;
        for (RuleOptions rule : new RuleOptions[]{std, four}) {
            String tag = rule.fourPlayer() ? "4p" : "3p";
            for (int key = RuleEngine.MIN_VALUE; key <= RuleEngine.MAX_VALUE; key++) {
                for (int[] shape : shapesAt(key)) {
                    if (shape.length == 0) {
                        continue;
                    }
                    Combo combo = RuleEngine.classify(shape, rule);
                    if (combo == null) {
                        continue;
                    }
                    compared++;
                    if (RuleEngine.beats(combo, combo)) {
                        selfBeats++;
                        System.out.println("      self-beat: " + tag + " " + combo
                                + " " + Arrays.toString(shape));
                    }
                }
            }
        }
        check(compared >= 60, "every rank and shape was compared, got " + compared);
        check(selfBeats == 0, "no combination may beat an identical one (found " + selfBeats + ")");

        // 点名：单张 / 对子 / 三张 / 炸弹，每个点数单独一条，失败信息最好读
        for (int key = RuleEngine.MIN_VALUE; key <= RuleEngine.TWO_VALUE; key++) {
            Combo single = RuleEngine.classify(new int[]{key}, std);
            Combo pair = RuleEngine.classify(new int[]{key, key}, std);
            Combo triple = RuleEngine.classify(new int[]{key, key, key}, std);
            Combo bomb = RuleEngine.classify(new int[]{key, key, key, key}, std);
            check(single != null && !RuleEngine.beats(single, single),
                    "single " + key + " cannot beat another single " + key);
            check(pair != null && !RuleEngine.beats(pair, pair), "pair " + key + " cannot beat itself");
            check(triple != null && !RuleEngine.beats(triple, triple), "triple " + key + " cannot beat itself");
            check(bomb != null && !RuleEngine.beats(bomb, bomb), "bomb " + key + " cannot beat itself");
        }
        Combo rocket3 = RuleEngine.classify(new int[]{16, 17}, std);
        Combo rocket4 = RuleEngine.classify(new int[]{16, 16, 17, 17}, four);
        check(rocket3 != null && !RuleEngine.beats(rocket3, rocket3), "3p rocket cannot beat itself");
        check(rocket4 != null && !RuleEngine.beats(rocket4, rocket4), "4p rocket cannot beat itself");

        // 生成器：跟一个单张 key 时，候选里绝不能出现同点数的单张
        int offered = 0;
        for (int key = RuleEngine.MIN_VALUE; key < RuleEngine.TWO_VALUE; key++) {
            int[] hand = {key, key, key + 1, key + 2};
            Combo previous = RuleEngine.classify(new int[]{key}, std);
            for (int[] candidate : RuleEngine.findBeats(hand, previous, std, true)) {
                Combo combo = RuleEngine.classify(candidate, std);
                if (combo != null && combo.type() == ComboType.SINGLE && combo.key() == key) {
                    offered++;
                }
            }
        }
        check(offered == 0, "generator never offers an equal single to beat itself (found " + offered + ")");

        // AI 决策层：让 AI 面对「上一手是单张 10」时做决定，结果必须严格压过它
        int illegal = 0;
        for (int trial = 0; trial < 60; trial++) {
            int[] hand = randomHand(new Random(1000 + trial), 8 + (trial % 12));
            // 保证手里至少有一张 10，逼 AI 面对「能不能用 10 压 10」
            hand[0] = 10;
            Combo previous = RuleEngine.classify(new int[]{10}, std);
            int[] chosen = AiPolicy.choosePlayValues(hand,
                    AiContext.following(previous, false, false, -1, -1, 5, null), std);
            if (chosen == null) {
                continue;
            }
            Combo combo = RuleEngine.classify(chosen, std);
            if (combo == null || !RuleEngine.beats(combo, previous)) {
                illegal++;
                System.out.println("      illegal answer " + Arrays.toString(chosen) + " vs single 10");
            }
        }
        check(illegal == 0, "AI never answers a single 10 with something that fails to beat it");
    }

    /** 该点数上所有「同点数可以成型」的牌型形状（用于自压检查）。 */
    private static List<int[]> shapesAt(int key) {
        List<int[]> shapes = new ArrayList<>();
        boolean joker = key >= RuleEngine.SMALL_JOKER_VALUE;
        shapes.add(new int[]{key});
        shapes.add(new int[]{key, key});
        shapes.add(new int[]{key, key, key});
        if (joker) {
            // 大小王不参与顺子/连对/飞机，也不作 4 张炸弹
            return shapes;
        }
        shapes.add(new int[]{key, key, key, key});
        int wing = key == RuleEngine.MIN_VALUE ? key + 1 : key - 1;
        if (key < RuleEngine.TWO_VALUE) {
            shapes.add(new int[]{key, key, key, wing});
            shapes.add(new int[]{key, key, key, wing, wing});
            shapes.add(new int[]{key, key, key, key, wing, wing + 1});
            shapes.add(new int[]{key, key, key, key, wing, wing, wing + 1, wing + 1});
        }
        if (key <= RuleEngine.CHAIN_MAX_VALUE) {
            shapes.add(chain(key, 5, 1));
            shapes.add(chain(key, 3, 2));
            shapes.add(chain(key, 2, 3));
        }
        return shapes;
    }

    /** 以 {@code end} 收尾、长度 {@code length}、每点 {@code perValue} 张的连续牌组。 */
    private static int[] chain(int end, int length, int perValue) {
        if (end - length + 1 < RuleEngine.MIN_VALUE) {
            return new int[0];
        }
        int[] cards = new int[length * perValue];
        int index = 0;
        for (int value = end - length + 1; value <= end; value++) {
            for (int copy = 0; copy < perValue; copy++) {
                cards[index++] = value;
            }
        }
        return cards;
    }

    /** 随机手牌（只用于压力测试，点数必须落在 3..17）。 */
    private static int[] randomHand(Random random, int size) {
        int[] hand = new int[Math.max(1, size)];
        for (int i = 0; i < hand.length; i++) {
            hand[i] = RuleEngine.MIN_VALUE + random.nextInt(RuleEngine.MAX_VALUE - RuleEngine.MIN_VALUE + 1);
        }
        return hand;
    }

    // ------------------------------------------------------------------ 计分

    private static void scoring() {
        System.out.println("== 1. scoring ==");
        RuleOptions std = RuleOptions.standard();

        // 3 人局、叫 3 分、没有炸弹、农民出过牌：地主 +2 单位，农民各 -1 单位
        DoudizhuScore.Settlement plain = DoudizhuScore.settle(3, 3, 0, true, 0, 1, 2);
        check(!plain.spring() && !plain.antiSpring(), "no spring when farmers played");
        check(plain.multiplier() == 1, "no bombs, no spring -> x1, got x" + plain.multiplier());
        check(plain.unit() == 3, "unit = bid x multiplier = 3, got " + plain.unit());
        check(plain.delta(0) == 6, "landlord +2 units = +6, got " + plain.delta(0));
        check(plain.delta(1) == -3 && plain.delta(2) == -3, "farmers -3 each");
        check(plain.total() == 0, "settlement must be zero-sum, got " + plain.total());

        // 春天：地主赢且农民一手没出 -> 再翻一倍
        DoudizhuScore.Settlement spring = DoudizhuScore.settle(1, 3, 2, true, 0, 4, 0);
        check(spring.spring() && !spring.antiSpring(), "spring detected when farmers played nothing");
        check(spring.multiplier() == 2, "spring doubles -> x2, got x" + spring.multiplier());
        check(spring.delta(2) == 4, "landlord +2 units of 2 = +4, got " + spring.delta(2));
        check(spring.total() == 0, "spring settlement is zero-sum");

        // 反春天：农民赢且地主只出过一手 -> 再翻一倍
        DoudizhuScore.Settlement anti = DoudizhuScore.settle(2, 3, 1, false, 0, 1, 3);
        check(anti.antiSpring() && !anti.spring(), "anti-spring detected");
        check(anti.multiplier() == 2, "anti-spring doubles -> x2, got x" + anti.multiplier());
        check(anti.delta(1) == -8, "landlord pays 2 units of 4 = -8, got " + anti.delta(1));
        check(anti.delta(0) == 4, "each farmer gets +4, got " + anti.delta(0));
        check(anti.total() == 0, "anti-spring settlement is zero-sum");

        // 炸弹翻倍 + 4 人局（地主对三家结算，零和）
        DoudizhuScore.Settlement four = DoudizhuScore.settle(1, 4, 3, true, 2, 5, 6);
        check(four.multiplier() == 4, "two bombs -> x4, got x" + four.multiplier());
        check(four.unit() == 4, "unit = 1 x 4 = 4, got " + four.unit());
        check(four.delta(3) == 12, "4p landlord settles against three farmers = +12, got " + four.delta(3));
        check(four.delta(0) == -4 && four.delta(1) == -4 && four.delta(2) == -4, "each 4p farmer pays one unit");
        check(four.total() == 0, "4p settlement is zero-sum");

        // 炸弹 + 春天叠加
        DoudizhuScore.Settlement both = DoudizhuScore.settle(3, 3, 0, true, 3, 6, 0);
        check(both.multiplier() == 16, "three bombs + spring -> x16, got x" + both.multiplier());

        // 非法底分被夹到 1
        check(DoudizhuScore.settle(0, 3, 0, true, 0, 1, 1).baseBid() == 1, "bid below 1 clamps to 1");
        check(DoudizhuScore.settle(-5, 3, 0, true, 0, 1, 1).baseBid() == 1, "negative bid clamps to 1");
        check(DoudizhuScore.nukeMultiplier(4) == 16, "nukeMultiplier(4) = x16");

        // 座位上界安全
        DoudizhuScore.Settlement guard = DoudizhuScore.settle(1, 3, 0, true, 0, 1, 1);
        check(guard.delta(-1) == 0 && guard.delta(99) == 0, "out-of-range seat returns 0");
        check(RuleEngine.classify(new int[]{3}, std) != null, "sanity: rules still work");
    }

    // ------------------------------------------------------------------ 抢地主 / 明牌 / 加倍

    /**
     * 抢地主与明牌 / 加倍的倍数必须「各自只放大自己那一份」，而且整体永远零和。
     *
     * <p>这里刻意用穷举而不是几个样例：抢次数 0..3 × 每个座位「没明牌 / 明牌 / 加倍 / 两者都有」
     * × 地主赢/输，只要有一组越界或丢了零和，立刻就会亮红。</p>
     */
    private static void grabAndDeclareScoring() {
        System.out.println("== 2. grab + reveal/double scoring ==");

        // 只抢地主，没有任何个人倍数：抢 n 次记 n-1 次翻倍
        DoudizhuScore.Settlement noGrab = DoudizhuScore.settle(1, 3, 1, true, 0, 3, 4, 0, factor(3, 0, 0, 0));
        check(noGrab.multiplier() == 1, "0 extra grabs -> x1, got x" + noGrab.multiplier());
        check(noGrab.unit() == 1, "base stays 1 in grab mode, got " + noGrab.unit());
        DoudizhuScore.Settlement oneGrab = DoudizhuScore.settle(1, 3, 1, true, 0, 3, 4, 0, factor(3, 0, 0, 0));
        check(oneGrab.multiplier() == noGrab.multiplier(),
                "the first grab must not double anything (it IS the normal call)");
        DoudizhuScore.Settlement twoGrabs = DoudizhuScore.settle(1, 3, 1, true, 0, 3, 4, 1, factor(3, 0, 0, 0));
        check(twoGrabs.multiplier() == 2, "a second grab doubles -> x2, got x" + twoGrabs.multiplier());
        check(twoGrabs.delta(1) == 4 && twoGrabs.delta(0) == -2 && twoGrabs.delta(2) == -2,
                "x2 with base 1: landlord +4, farmers -2 each");
        DoudizhuScore.Settlement fourGrabs = DoudizhuScore.settle(1, 3, 0, true, 1, 3, 4, 3, factor(3, 0, 0, 0));
        check(fourGrabs.multiplier() == 16, "1 bomb + 3 extra grabs -> x16, got x" + fourGrabs.multiplier());

        // 个人倍数：只放大该玩家与地主之间的那一对
        // 地主（座位 0）加倍、农民 1 明牌、农民 2 什么都不选
        int[] marks = factor(3, 1, 1, 0);
        DoudizhuScore.Settlement mixed = DoudizhuScore.settle(1, 3, 0, true, 0, 3, 4, 0, marks);
        check(mixed.factor(0) == 2 && mixed.factor(1) == 2 && mixed.factor(2) == 1, "personal factors applied");
        check(mixed.pairUnit(1, 0) == 4, "landlord x2 vs farmer x2 = 4x unit, got " + mixed.pairUnit(1, 0));
        check(mixed.pairUnit(2, 0) == 2, "landlord x2 vs farmer x1 = 2x unit, got " + mixed.pairUnit(2, 0));
        check(mixed.delta(1) == -4, "revealing farmer pays 4, got " + mixed.delta(1));
        check(mixed.delta(2) == -2, "silent farmer pays 2, got " + mixed.delta(2));
        check(mixed.delta(0) == 6, "landlord collects 4 + 2 = 6, got " + mixed.delta(0));

        // 明牌 + 加倍同时选：个人倍数 x4
        check(DoudizhuScore.settle(1, 3, 0, true, 0, 3, 4, 0, factor(3, 2, 0, 0)).factor(0) == 4,
                "reveal + double = x4 personal multiplier");
        // 只有游戏内部才会给出的指数（0/1/2）是有意义的；再大也必须夹住而不是移位溢出
        check(DoudizhuScore.settle(1, 3, 0, true, 0, 3, 4, 0, factor(3, 99, 0, 0)).factor(0) == 1 << 24,
                "an absurd personal exponent is clamped instead of overflowing");
        check(DoudizhuScore.settle(1, 3, 0, true, 0, 3, 4, -5, factor(3, -5, 0, 0)).factor(0) == 1,
                "negative exponents are clamped to x1");
        check(DoudizhuScore.settle(1, 3, 0, true, 0, 3, 4, 0, null).total() == 0,
                "a null personal-multiplier array defaults to x1 and stays zero-sum");

        // 穷举：任意座位数 / 地主位置 / 胜负 / 抢次数 / 个人倍数组合都必须零和
        int cases = 0;
        for (int seats = 3; seats <= 4; seats++) {
            for (int landlord = 0; landlord < seats; landlord++) {
                for (int grabs = 0; grabs <= 3; grabs++) {
                    for (int won = 0; won <= 1; won++) {
                        for (int pattern = 0; pattern < 81; pattern++) {
                            int[] exponents = new int[seats];
                            int rest = pattern;
                            for (int seat = 0; seat < seats; seat++) {
                                exponents[seat] = rest % 3;
                                rest /= 3;
                            }
                            DoudizhuScore.Settlement settlement = DoudizhuScore.settle(
                                    2, seats, landlord, won == 1, 1, 2, 3, grabs, exponents);
                            cases++;
                            if (settlement.total() != 0) {
                                check(false, "settlement must stay zero-sum: " + Arrays.toString(exponents)
                                        + " seats=" + seats + " landlord=" + landlord + " grabs=" + grabs
                                        + " won=" + (won == 1) + " total=" + settlement.total());
                                return;
                            }
                            int landlordDelta = settlement.delta(landlord);
                            int expected = 0;
                            for (int seat = 0; seat < seats; seat++) {
                                if (seat == landlord) {
                                    continue;
                                }
                                expected += (won == 1 ? 1 : -1) * settlement.pairUnit(seat, landlord);
                            }
                            if (landlordDelta != expected) {
                                check(false, "landlord must collect the sum of the pair units: expected " + expected
                                        + " but got " + landlordDelta);
                                return;
                            }
                        }
                    }
                }
            }
        }
        check(cases == 4536, "exhaustive zero-sum sweep ran " + cases + " cases (expected 4536)");
        check(RuleEngine.classify(new int[]{3, 3}, RuleOptions.standard()) != null, "sanity: rules still work");
    }

    /** 每个座位的个人倍数指数（明牌 +1、加倍 +1）。 */
    private static int[] factor(int seats, int... exponents) {
        int[] result = new int[seats];
        for (int seat = 0; seat < seats && seat < exponents.length; seat++) {
            result[seat] = exponents[seat];
        }
        return result;
    }

    // ------------------------------------------------------------------ AI 难度档位

    /**
     * 三个难度档位必须真的不一样，而且方向要对：保守叫得比激进少、炸得比激进晚。
     *
     * <p>同时验证「同一手牌 + 同一上下文 = 同一决策」这条纯函数性质在换档之后依然成立——
     * 档位是数据不是随机数，任何一次重复调用都必须给出完全一样的结果。</p>
     */
    private static void aiDifficultyProfiles() {
        System.out.println("== 3. AI difficulty profiles ==");
        AiProfile cautious = AiProfile.of(0);
        AiProfile balanced = AiProfile.of(1);
        AiProfile aggressive = AiProfile.of(2);
        check(cautious.id().equals("conservative"), "option 0 maps to the cautious profile");
        check(balanced.id().equals("balanced"), "option 1 maps to the balanced profile");
        check(aggressive.id().equals("aggressive"), "option 2 maps to the aggressive profile");
        check(AiProfile.of(-1).id().equals("balanced") && AiProfile.of(99).id().equals("balanced"),
                "out-of-range difficulty falls back to balanced");
        check(cautious.ordinal() == 0 && balanced.ordinal() == 1 && aggressive.ordinal() == 2,
                "ordinal() round-trips every profile");
        check(AiProfile.OPTION_COUNT == 3, "exactly three difficulty levels are offered");
        check(cautious.bidOne() > balanced.bidOne() && balanced.bidOne() > aggressive.bidOne(),
                "bid thresholds must decrease from cautious to aggressive");
        check(cautious.bombAt() < balanced.bombAt() && balanced.bombAt() < aggressive.bombAt(),
                "the bomb trigger must come earlier as difficulty rises");
        check(cautious.blockAt() < balanced.blockAt() && balanced.blockAt() < aggressive.blockAt(),
                "the blocking trigger must come earlier as difficulty rises");

        // 叫分：同一手牌，保守要的门槛更高
        int[] mediocre = {3, 4, 5, 6, 7, 8, 9, 10, 11};
        int cautiousBid = AiPolicy.chooseBid(mediocre, 0, cautious);
        int aggressiveBid = AiPolicy.chooseBid(mediocre, 0, aggressive);
        check(cautiousBid <= aggressiveBid,
                "cautious must never bid higher than aggressive: " + cautiousBid + " vs " + aggressiveBid);

        // 叫分永远不能低于当前最高分（否则会非法压价）
        for (AiProfile profile : new AiProfile[]{cautious, balanced, aggressive}) {
            for (int highest = 0; highest <= 3; highest++) {
                int bid = AiPolicy.chooseBid(mediocre, highest, profile);
                check(bid == 0 || bid > highest,
                        "a bid must beat the current highest (" + bid + " vs " + highest + ")");
            }
        }

        // 首出：换档之后决策依然是确定性的（同输入同输出）
        int[] hand = {3, 3, 4, 4, 5, 6, 7, 8, 9, 9, 10, 11, 13, 14, 15, 16, 17};
        for (AiProfile profile : new AiProfile[]{cautious, balanced, aggressive}) {
            int[] first = AiPolicy.chooseLeadValues(hand, RuleOptions.standard(), null, profile);
            int[] second = AiPolicy.chooseLeadValues(hand, RuleOptions.standard(), null, profile);
            check(first != null && Arrays.equals(first, second),
                    "profile " + profile.id() + " must be deterministic");
        }

        // 跟牌：三档都得给出一手合法候选（或明确不出），且同输入同输出
        AiContext following = AiContext.following(
                RuleEngine.classify(new int[]{10}, RuleOptions.standard()), false, false, -1, 5, 5, null);
        for (AiProfile profile : new AiProfile[]{cautious, balanced, aggressive}) {
            int[] answer = AiPolicy.choosePlayValues(hand, following, RuleOptions.standard(), profile);
            int[] again = AiPolicy.choosePlayValues(hand, following, RuleOptions.standard(), profile);
            check(Arrays.equals(answer, again), "profile " + profile.id() + " follow decision is deterministic");
            if (answer != null) {
                boolean legal = RuleEngine.classify(answer, RuleOptions.standard()) != null;
                check(legal, "profile " + profile.id() + " must only answer with a legal combination");
            }
        }

        // 空手牌 / null 输入：任何档位都不能抛异常
        for (AiProfile profile : new AiProfile[]{cautious, balanced, aggressive}) {
            check(AiPolicy.chooseLeadValues(new int[0], RuleOptions.standard(), null, profile) == null,
                    "an empty hand has no lead for " + profile.id());
            check(AiPolicy.choosePlayValues(null, following, RuleOptions.standard(), profile) == null,
                    "a null hand has no play for " + profile.id());
            check(AiPolicy.chooseBid(new int[0], 0, profile) == 0, "an empty hand never bids for " + profile.id());
            check(!AiPolicy.chooseGrab(new int[0], 0, false, profile),
                    "an empty hand never grabs for " + profile.id());
            check(!AiPolicy.chooseGrab(null, 0, false, profile), "a null hand never grabs for " + profile.id());
        }

        strengthBaselineIsCalibrated();
        grabDecisions();
        behaviourDiffersBetweenProfiles();
        blockingAndEndgameDecisions();
    }

    // ------------------------------------------------------------------ 控场分基线

    /**
     * 控场分的绝对值跟手牌张数强相关，叫分 / 抢 / 明牌的门槛全部相对基线来量。
     *
     * <p>这里用随机手牌直接验证两件事：<b>基线本身是准的</b>（随机手牌的平均 margin 接近 0），
     * 以及<b>两种人数下的触发概率一致</b>（否则 4 人局会出现「人人都够叫 3 分」的退化）。</p>
     */
    private static void strengthBaselineIsCalibrated() {
        System.out.println("== 4. strength baseline calibration ==");
        check(AiPolicy.strengthBaseline(17) == 2, "3 player decks average a control score of 2");
        check(AiPolicy.strengthBaseline(25) == 12, "4 player decks average a control score of 12");
        check(AiPolicy.strengthBaseline(20) == 5 && AiPolicy.strengthBaseline(33) == 22,
                "the baseline extrapolates smoothly to other hand sizes");
        check(AiPolicy.strengthBaseline(10) == 2,
                "hand sizes below the calibration point must not produce a negative baseline");

        RuleOptions std = RuleOptions.standard();
        AiProfile balanced = AiProfile.balanced();
        Random random = new Random(910213L);
        for (int handSize : new int[]{17, 25, 33}) {
            long total = 0;
            int bidOne = 0;
            int grab = 0;
            int samples = 4000;
            for (int i = 0; i < samples; i++) {
                int[] hand = deckHand(random, handSize, handSize > 20 ? 2 : 1);
                total += AiPolicy.strengthMargin(hand);
                if (AiPolicy.chooseBid(hand, 0, balanced) > 0) {
                    bidOne++;
                }
                if (AiPolicy.chooseGrab(hand, 0, false, balanced)) {
                    grab++;
                }
            }
            int meanMargin = (int) (total / samples);
            check(Math.abs(meanMargin) <= 2,
                    "hand size " + handSize + ": the baseline must sit near the mean, mean margin = " + meanMargin);
            double bidRate = bidOne / (double) samples;
            check(bidRate >= 0.15 && bidRate <= 0.60,
                    "hand size " + handSize + ": the balanced bid-1 rate must be sane, got "
                            + Math.round(bidRate * 100) + "%");
            double grabRate = grab / (double) samples;
            check(grabRate >= 0.20 && grabRate <= 0.75,
                    "hand size " + handSize + ": the balanced grab rate must be sane, got "
                            + Math.round(grabRate * 100) + "%");
        }
        check(RuleEngine.classify(new int[]{3, 3}, std) != null, "sanity: rules still work");
    }

    /** 从真牌堆里随机抽 handSize 张（按点数排序）；copies = 1 是 54 张单副，2 是 108 张两副。 */
    private static int[] deckHand(Random random, int handSize, int copies) {
        int perRank = copies == 2 ? 8 : 4;
        List<Integer> deck = new java.util.ArrayList<>();
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.TWO_VALUE; value++) {
            for (int copy = 0; copy < perRank; copy++) {
                deck.add(value);
            }
        }
        for (int copy = 0; copy < copies; copy++) {
            deck.add(RuleEngine.SMALL_JOKER_VALUE);
            deck.add(RuleEngine.BIG_JOKER_VALUE);
        }
        java.util.Collections.shuffle(deck, random);
        int[] hand = new int[Math.min(handSize, deck.size())];
        for (int i = 0; i < hand.length; i++) {
            hand[i] = deck.get(i);
        }
        Arrays.sort(hand);
        return hand;
    }

    // ------------------------------------------------------------------ 抢地主决策

    /**
     * 抢地主的门槛必须随「已经有多少人抢过」抬高、随「自己是最后一个决定的人」降低，
     * 而且三个档位的意愿要拉开档次。
     */
    private static void grabDecisions() {
        System.out.println("== 5. grab-the-landlord decisions ==");
        AiProfile cautious = AiProfile.conservative();
        AiProfile balanced = AiProfile.balanced();
        AiProfile aggressive = AiProfile.aggressive();

        check(cautious.grabBase() > balanced.grabBase() && balanced.grabBase() > aggressive.grabBase(),
                "the grab threshold must fall from cautious to aggressive");
        check(cautious.grabStep() >= balanced.grabStep() && balanced.grabStep() >= aggressive.grabStep(),
                "cautious must raise its bar faster as more players grab");
        check(aggressive.grabLastDiscount() >= balanced.grabLastDiscount()
                        && balanced.grabLastDiscount() >= cautious.grabLastDiscount(),
                "aggressive must be the most willing to take an uncontested grab");
        check(cautious.revealMargin() > balanced.revealMargin()
                        && balanced.revealMargin() > aggressive.revealMargin(),
                "the reveal threshold must fall from cautious to aggressive");
        check(cautious.doubleMargin() > balanced.doubleMargin()
                        && balanced.doubleMargin() > aggressive.doubleMargin(),
                "the double threshold must fall from cautious to aggressive");

        // 门槛随已抢次数单调抬高：对<b>任意</b>手牌，序列都必须是「先抢、之后不再抢」（非递增），
        // 一旦在高倍数下放弃就绝不能反悔。用随机手牌扫一遍，比钉一手牌更能说明问题。
        Random monotoneRandom = new Random(31337L);
        int thresholdInsideRange = 0;
        for (AiProfile profile : new AiProfile[]{cautious, balanced, aggressive}) {
            boolean monotone = true;
            for (int sample = 0; sample < 2000 && monotone; sample++) {
                int[] hand = deckHand(monotoneRandom, 17, 1);
                boolean previous = true;
                boolean everGrabbed = false;
                boolean everDeclined = false;
                for (int grabs = 0; grabs <= 5; grabs++) {
                    boolean now = AiPolicy.chooseGrab(hand, grabs, false, profile);
                    if (!previous && now) {
                        monotone = false;
                    }
                    everGrabbed |= now;
                    everDeclined |= !now;
                    previous = now;
                }
                if (everGrabbed && everDeclined) {
                    thresholdInsideRange++;
                }
            }
            check(monotone, "profile " + profile.id()
                    + " must not grab MORE often as more players have grabbed");
        }
        check(thresholdInsideRange > 0,
                "some random hand must show a grab threshold that actually bites within 0..5 grabs");

        int[] veryStrong = {13, 14, 15, 15, 16, 17, 13, 14, 15, 15, 16, 17, 3, 4, 5, 6, 7};
        int[] veryWeak = {3, 4, 5, 6, 7, 8, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 14};
        for (AiProfile profile : new AiProfile[]{cautious, balanced, aggressive}) {
            check(AiPolicy.chooseGrab(veryStrong, 0, false, profile),
                    "every difficulty must grab with a monster hand (" + profile.id() + ")");
            check(!AiPolicy.chooseGrab(veryWeak, 0, false, profile),
                    "no difficulty may grab with a junk hand (" + profile.id() + ")");
        }

        // 同一手牌、同样局面下，档位越高越敢抢（保守 ⊆ 均衡 ⊆ 激进）
        Random orderRandom = new Random(90210L);
        int cautiousOnly = 0;
        int aggressiveOnly = 0;
        for (int sample = 0; sample < 4000; sample++) {
            int[] hand = deckHand(orderRandom, 17, 1);
            boolean c = AiPolicy.chooseGrab(hand, 0, false, cautious);
            boolean b = AiPolicy.chooseGrab(hand, 0, false, balanced);
            boolean a = AiPolicy.chooseGrab(hand, 0, false, aggressive);
            if (c && !b || b && !a) {
                check(false, "grab willingness must not decrease with difficulty: cautious=" + c
                        + " balanced=" + b + " aggressive=" + a + " hand=" + str(hand));
                break;
            }
            if (!c && a) {
                aggressiveOnly++;
            }
            if (c && !a) {
                cautiousOnly++;
            }
        }
        check(cautiousOnly == 0, "the cautious profile must never grab where the aggressive one declines");
        check(aggressiveOnly > 200,
                "the aggressive profile must grab meaningfully more often than the cautious one, extra grabs = "
                        + aggressiveOnly);

        // 最后一个决定的人至少和早决定一样敢抢（门槛只会更低）
        Random random = new Random(4477L);
        int earlyWins = 0;
        int lateWins = 0;
        int samples = 3000;
        for (int i = 0; i < samples; i++) {
            int[] hand = deckHand(random, 17, 1);
            if (AiPolicy.chooseGrab(hand, 0, false, balanced)) {
                earlyWins++;
            }
            if (AiPolicy.chooseGrab(hand, 0, true, balanced)) {
                lateWins++;
            }
        }
        check(lateWins >= earlyWins,
                "being last to decide must not make the AI more hesitant: " + lateWins + " vs " + earlyWins);
        check(lateWins > earlyWins, "being last to decide must actually loosen the threshold");

        // 一整桌的抢概率要落在合理区间：既不能人人都不抢（一直重新发牌），也不能人人抢（倍数爆炸）
        for (int players : new int[]{3, 4}) {
            double[] nobodyRates = new double[3];
            AiProfile[] profiles = {cautious, balanced, aggressive};
            for (int index = 0; index < profiles.length; index++) {
                AiProfile profile = profiles[index];
                int deals = 4000;
                int nobody = 0;
                for (int deal = 0; deal < deals; deal++) {
                    int handSize = players == 3 ? 17 : 25;
                    int[][] hands = dealHands(random, players, handSize, players == 3 ? 1 : 2);
                    int made = 0;
                    int taken = 0;
                    for (int seat = 0; seat < players; seat++) {
                        boolean last = made >= players - 1;
                        if (AiPolicy.chooseGrab(hands[seat], taken, last, profile)) {
                            taken++;
                        }
                        made++;
                    }
                    if (taken == 0) {
                        nobody++;
                    }
                }
                nobodyRates[index] = nobody / (double) deals;
                check(nobodyRates[index] <= 0.30,
                        players + "p " + profile.id() + ": a grab must happen often enough, nobody grabbed in "
                                + Math.round(nobodyRates[index] * 100) + "% of deals");
            }
            // 保守档必须「有时真的不抢」——否则说明门槛低到形同虚设
            check(nobodyRates[0] >= 0.03,
                    players + "p cautious must sometimes decline to grab, nobody grabbed in only "
                            + Math.round(nobodyRates[0] * 100) + "% of deals");
            // 越激进越不会出现「全场没人抢」
            check(nobodyRates[0] >= nobodyRates[1] - 0.02 && nobodyRates[1] >= nobodyRates[2] - 0.02,
                    players + "p: the no-grab rate must not rise with difficulty, got "
                            + Math.round(nobodyRates[0] * 100) + "% / " + Math.round(nobodyRates[1] * 100)
                            + "% / " + Math.round(nobodyRates[2] * 100) + "%");
        }
        check(RuleEngine.classify(new int[]{3, 3, 3}, RuleOptions.standard()) != null, "sanity: rules still work");
    }

    /**
     * 发一手真牌给每个座位。
     *
     * <p>必须<b>先洗牌再分</b>：早先的写法是「排序后按座位切片」，结果 0 号座位永远拿最小的 17 张、
     * 最后一个座位永远拿最大的 17 张，统计出来的抢地主概率完全失真。</p>
     */
    private static int[][] dealHands(Random random, int players, int handSize, int copies) {
        int perRank = copies == 2 ? 8 : 4;
        List<Integer> deck = new java.util.ArrayList<>();
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.TWO_VALUE; value++) {
            for (int copy = 0; copy < perRank; copy++) {
                deck.add(value);
            }
        }
        for (int copy = 0; copy < copies; copy++) {
            deck.add(RuleEngine.SMALL_JOKER_VALUE);
            deck.add(RuleEngine.BIG_JOKER_VALUE);
        }
        java.util.Collections.shuffle(deck, random);
        int[][] hands = new int[players][handSize];
        for (int seat = 0; seat < players; seat++) {
            for (int card = 0; card < handSize; card++) {
                hands[seat][card] = deck.get(seat * handSize + card);
            }
            Arrays.sort(hands[seat]);
        }
        return hands;
    }

    // ------------------------------------------------------------------ 档位的行为差异

    /**
     * 三个档位必须<b>真的打出不一样的牌</b>，而不只是三个不同的数字表。
     *
     * <p>做法：在同一批随机局面上分别问三个档位「怎么出」，统计分歧率。同时校验每一手都是合法牌型
     * （或者明确选择不出），避免为了「有差别」而放出非法决策。</p>
     *
     * <p>分歧来自这几条<b>设计上就该不同</b>的轴：叫分门槛、抢地主意愿、明牌 / 加倍门槛、
     * 炸弹触发线、以及「对手剩几张才算危险」的封杀触发线。反过来说，
     * <b>不紧急</b>的局面下三档该挑同一手「最便宜的安全压牌」——难度只改风格与紧迫感，
     * 不该让低难度打出明显错误的牌，这一点也要断言。</p>
     */
    private static void behaviourDiffersBetweenProfiles() {
        System.out.println("== 6. profiles actually behave differently ==");
        AiProfile cautious = AiProfile.conservative();
        AiProfile balanced = AiProfile.balanced();
        AiProfile aggressive = AiProfile.aggressive();
        RuleOptions std = RuleOptions.standard();
        Random random = new Random(778899L);

        int samples = 4000;
        int leadDiff = 0;
        int leads = 0;
        int urgentDiff = 0;
        int urgent = 0;
        int calmDiff = 0;
        int calm = 0;
        int bidDiff = 0;
        for (int i = 0; i < samples; i++) {
            int[] hand = deckHand(random, 8 + random.nextInt(14), 1);
            // 首出：危险局面（对手只剩 0~3 张）才是封杀逻辑发力的地方
            for (int opponent : new int[]{-1, 0, 1, 2, 3, 6}) {
                int[] cautiousLead = AiPolicy.chooseLeadValues(hand, std, null, opponent, cautious);
                int[] aggressiveLead = AiPolicy.chooseLeadValues(hand, std, null, opponent, aggressive);
                leads++;
                if (!Arrays.equals(cautiousLead, aggressiveLead)) {
                    leadDiff++;
                }
            }

            Combo previous = RuleEngine.classify(previousOrNull(random, std), std);
            if (previous == null) {
                continue;
            }
            for (int opponent : new int[]{-1, 1, 2, 3, 8}) {
                AiContext context = AiContext.following(previous, false, false, -1, 6, opponent, null);
                int[] cautiousPlay = AiPolicy.choosePlayValues(hand, context, std, cautious);
                int[] aggressivePlay = AiPolicy.choosePlayValues(hand, context, std, aggressive);
                boolean danger = opponent >= 0 && opponent <= aggressive.blockAt();
                if (danger) {
                    urgent++;
                    if (!Arrays.equals(cautiousPlay, aggressivePlay)) {
                        urgentDiff++;
                    }
                } else {
                    calm++;
                    if (!Arrays.equals(cautiousPlay, aggressivePlay)) {
                        calmDiff++;
                    }
                }
                for (int[] answer : new int[][]{cautiousPlay, aggressivePlay}) {
                    if (answer != null && RuleEngine.classify(answer, std) == null) {
                        check(false, "a profile answered with an illegal combination: " + str(answer)
                                + " against " + previous);
                    }
                }
            }

            if (AiPolicy.chooseBid(hand, 0, cautious) != AiPolicy.chooseBid(hand, 0, balanced)) {
                bidDiff++;
            }
        }
        check(leads > samples * 4, "the behaviour sweep must have exercised enough leads");
        check(urgent > samples * 2, "the behaviour sweep must have exercised enough urgent follows");
        check(leadDiff * 100 >= leads * 10,
                "cautious and aggressive must disagree on at least 10% of leads, got "
                        + Math.round(100.0 * leadDiff / leads) + "%");
        check(urgentDiff * 100 >= urgent * 10,
                "cautious and aggressive must disagree on at least 10% of urgent follows, got "
                        + Math.round(100.0 * urgentDiff / urgent) + "%");
        check(calmDiff * 100 <= calm * 2,
                "outside danger the profiles must agree on the cheapest safe beater, but disagreed on "
                        + Math.round(100.0 * calmDiff / calm) + "%");
        check(bidDiff > 0, "cautious and balanced must disagree on some bids");

        // 方向性：同一手牌，激进叫得不会比保守低
        int ordered = 0;
        for (int i = 0; i < samples; i++) {
            int[] hand = deckHand(random, 17, 1);
            if (AiPolicy.chooseBid(hand, 0, aggressive) >= AiPolicy.chooseBid(hand, 0, cautious)) {
                ordered++;
            }
        }
        check(ordered == samples, "aggressive must never bid lower than cautious (" + ordered + "/" + samples + ")");
        check(RuleEngine.classify(new int[]{3, 3, 3, 3}, std) != null, "sanity: rules still work");
    }

    // ------------------------------------------------------------------ 封杀与残局

    /**
     * 三条「以前明显打错、现在必须打对」的具体决策，全部用构造牌面钉死。
     *
     * <p>这些是最容易被玩家看出来的失误，只靠「合法 / 零和」这类断言抓不到，必须逐手比对。</p>
     */
    private static void blockingAndEndgameDecisions() {
        System.out.println("== 7. blocking and endgame decisions ==");
        RuleOptions std = RuleOptions.standard();
        AiProfile balanced = AiProfile.balanced();

        // 7a) 对手只剩 1 张、自己手上是 5 / Q / 2 跟一张 4：出 5 等于送他一局，必须出最大的 2
        int[] singles = {5, 12, 15};
        int[] blockChoice = AiPolicy.choosePlayValues(singles,
                followContext(new int[]{4}, 1), std, balanced);
        check(blockChoice != null && blockChoice.length == 1 && blockChoice[0] == 15,
                "with an opponent on one card the AI must block with its biggest single, got " + str(blockChoice));
        int[] calmChoice = AiPolicy.choosePlayValues(singles,
                followContext(new int[]{4}, -1), std, balanced);
        check(calmChoice != null && calmChoice.length == 1 && calmChoice[0] == 5,
                "with no opponent in range the AI must still play the cheapest single, got " + str(calmChoice));

        // 7b) 首出：对手只剩 1 张时不能喂小单张，要出最大的
        int[] allSingles = {3, 7, 9, 11, 13, 15};
        int[] blockingLead = AiPolicy.chooseLeadValues(allSingles, std, null, 1, balanced);
        check(blockingLead != null && blockingLead.length == 1 && blockingLead[0] == 15,
                "leading into an opponent on one card must be the biggest single, got " + str(blockingLead));
        int[] normalLead = AiPolicy.chooseLeadValues(allSingles, std, null, -1, balanced);
        check(normalLead != null && normalLead.length == 1 && normalLead[0] == 3,
                "with no opponent in range the AI must still lead its smallest single, got " + str(normalLead));

        // 7c) 首出：对手只剩 1 张时，一张他接不住的牌型（对子）比任何单张都好
        int[] withPair = {3, 3, 7, 9, 11, 13, 15};
        int[] pairLead = AiPolicy.chooseLeadValues(withPair, std, null, 1, balanced);
        check(pairLead != null && pairLead.length == 2 && covers(withPair, pairLead),
                "leading a pair into an opponent on one card must be preferred, got " + str(pairLead));

        // 7d) 残局跟牌：手里是 7-8-9-10-J 顺子加一张 A，跟单张 6 时不能拆顺子
        int[] straightPlusAce = {7, 8, 9, 10, 11, 14};
        int[] endgame = AiPolicy.choosePlayValues(straightPlusAce,
                followContext(new int[]{6}, -1), std, balanced);
        check(endgame != null && endgame.length == 1 && endgame[0] == 14,
                "in the endgame the AI must keep its straight intact and beat with the ace, got " + str(endgame));
        // 手牌还多的时候不做这项评估，行为与旧版一致（出最小可压）
        int[] longHand = {7, 8, 9, 10, 11, 14, 3, 3, 4, 4, 5, 5};
        int[] longChoice = AiPolicy.choosePlayValues(longHand, followContext(new int[]{6}, -1), std, balanced);
        check(longChoice != null && longChoice.length == 1 && longChoice[0] == 7,
                "outside the endgame the cheapest safe beater must still win, got " + str(longChoice));
    }

    /** 点数数组 → 可读字符串（null 安全）。 */
    private static String str(int[] values) {
        return values == null ? "null" : Arrays.toString(values);
    }

    /** 随机造一个合法的「上一手」牌型；造不出来就返回 null。 */    private static int[] previousOrNull(Random random, RuleOptions options) {
        for (int attempt = 0; attempt < 40; attempt++) {
            int[] values = randomHand(random, 1 + random.nextInt(4));
            Arrays.sort(values);
            if (RuleEngine.classify(values, options) != null) {
                return values;
            }
        }
        return null;
    }

    /** 「自己不是地主、队友没出过牌」的跟牌上下文。 */
    private static AiContext followContext(int[] previous, int minOpponentHandSize) {
        return AiContext.following(RuleEngine.classify(previous, RuleOptions.standard()),
                false, false, -1, 6, minOpponentHandSize, null);
    }

    // ------------------------------------------------------------------ 提示循环

    private static void hintCycling() {
        System.out.println("== 2. hint cycling ==");
        RuleOptions std = RuleOptions.standard();

        // 单张 3 在手，外面有一串更大的单张：候选必须按点数升序、第一项与 hint() 一致
        int[] hand = {3, 4, 5, 6, 7, 9};
        Combo previous = RuleEngine.classify(new int[]{3}, std);
        List<int[]> hints = RuleEngine.hints(hand, previous, std);
        check(hints.size() >= 5, "at least five singles beat a 3, got " + hints.size());
        int[] keys = new int[hints.size()];
        for (int i = 0; i < hints.size(); i++) {
            Combo combo = RuleEngine.classify(hints.get(i), std);
            check(combo != null && RuleEngine.beats(combo, previous),
                    "hint candidate " + Arrays.toString(hints.get(i)) + " must beat the previous play");
            check(covers(hand, hints.get(i)), "hint candidate must come from the hand");
            keys[i] = combo.key();
        }
        check(isSorted(keys), "hint candidates are sorted by key ascending: " + Arrays.toString(keys));
        check(Arrays.equals(hints.get(0), RuleEngine.hint(hand, previous, std)),
                "hints[0] must equal hint()");

        // 去重
        Set<String> distinct = new HashSet<>();
        for (int[] candidate : hints) {
            distinct.add(Arrays.toString(candidate));
        }
        check(distinct.size() == hints.size(), "hint candidates are de-duplicated");

        // 炸弹排最后：手上同时有单张 9 与炸弹 5555，跟单张 8 时应该先给 9
        int[] withBomb = {5, 5, 5, 5, 9};
        List<int[]> bombHints = RuleEngine.hints(withBomb, RuleEngine.classify(new int[]{8}, std), std);
        check(bombHints.size() == 2, "single 9 + bomb 5555 = two candidates, got " + bombHints.size());
        Combo firstCombo = RuleEngine.classify(bombHints.get(0), std);
        Combo lastCombo = RuleEngine.classify(bombHints.get(bombHints.size() - 1), std);
        check(firstCombo.type() == ComboType.SINGLE && firstCombo.key() == 9,
                "the cheap single comes first, got " + firstCombo);
        check(lastCombo.isNuke(), "the bomb comes last, got " + lastCombo);

        // 首出：候选与 findLeads 一致。「提示」的口径是「最便宜的一手」，所以首出给的是最小的单张
        // （AI 自己首出用的是另一套策略 DoudizhuAi.chooseLead，两者刻意不同，见 DoudizhuGame.hint）。
        int[] leadHand = {3, 4, 5, 6, 7};
        List<int[]> leads = RuleEngine.hints(leadHand, null, std);
        check(!leads.isEmpty(), "lead hints are never empty for a non-empty hand");
        check(Arrays.equals(leads.get(0), RuleEngine.hint(leadHand, null, std)), "lead hints[0] == hint()");
        Combo cheapestLead = RuleEngine.classify(leads.get(0), std);
        check(cheapestLead.type() == ComboType.SINGLE && cheapestLead.key() == 3,
                "the cheapest lead is the smallest single, got " + cheapestLead);
        int[] keys2 = new int[leads.size()];
        for (int i = 0; i < leads.size(); i++) {
            keys2[i] = RuleEngine.classify(leads.get(i), std).key();
        }
        check(isSorted(keys2), "lead hints are sorted by key ascending");
        check(hasType(leads, std, ComboType.STRAIGHT), "the five-card straight is also offered as a lead");

        // 压不过时返回空
        check(RuleEngine.hints(new int[]{3, 4}, RuleEngine.classify(new int[]{17}, std), std).isEmpty(),
                "no candidate beats the big joker");

        // 确定性
        check(Arrays.deepEquals(RuleEngine.hints(hand, previous, std).toArray(),
                        RuleEngine.hints(hand, previous, std).toArray()),
                "hint order is deterministic");
    }

    // ------------------------------------------------------------------ 规则开关

    private static void ruleSwitches() {
        System.out.println("== 3. rule switches ==");
        RuleOptions std = RuleOptions.standard();
        RuleOptions noTriple = std.withThreeWithTwo(false);
        RuleOptions noFour = std.withFourWithTwo(false);

        check(RuleEngine.classify(new int[]{3, 3, 3, 4}, std).type() == ComboType.TRIPLE_SINGLE,
                "三带一 legal by default");
        check(RuleEngine.classify(new int[]{3, 3, 3, 4}, noTriple) == null,
                "三带一 rejected when the switch is off");
        check(RuleEngine.classify(new int[]{3, 3, 3, 4, 4}, std).type() == ComboType.TRIPLE_PAIR,
                "三带二 legal by default");
        check(RuleEngine.classify(new int[]{3, 3, 3, 4, 4}, noTriple) == null,
                "三带二 rejected when the switch is off");
        check(RuleEngine.classify(new int[]{3, 3, 3, 3, 5, 7}, std).type() == ComboType.FOUR_TWO_SINGLE,
                "四带二 legal by default");
        check(RuleEngine.classify(new int[]{3, 3, 3, 3, 5, 7}, noFour) == null,
                "四带二 rejected when the switch is off");
        check(RuleEngine.classify(new int[]{3, 3, 3, 3, 5, 5, 7, 7}, noFour) == null,
                "四带两对 rejected when the switch is off");
        // 关掉带牌不影响三张/炸弹本身
        check(RuleEngine.classify(new int[]{3, 3, 3}, noTriple).type() == ComboType.TRIPLE,
                "plain triple stays legal");
        check(RuleEngine.classify(new int[]{3, 3, 3, 3}, noFour).type() == ComboType.BOMB,
                "four of a kind stays a bomb");

        // 生成器也必须遵守开关
        int[] hand = {3, 3, 3, 4, 4, 5, 5, 5, 5, 6, 7};
        check(hasType(RuleEngine.findLeads(hand, std), std, ComboType.TRIPLE_SINGLE),
                "generator emits 三带一 by default");
        check(!hasType(RuleEngine.findLeads(hand, noTriple), noTriple, ComboType.TRIPLE_SINGLE),
                "generator stops emitting 三带一 when the switch is off");
        check(!hasType(RuleEngine.findLeads(hand, noTriple), noTriple, ComboType.TRIPLE_PAIR),
                "generator stops emitting 三带二 when the switch is off");
        check(hasType(RuleEngine.findLeads(hand, std), std, ComboType.FOUR_TWO_SINGLE)
                        || hasType(RuleEngine.findLeads(hand, std), std, ComboType.FOUR_TWO_PAIR),
                "generator emits 四带二 by default");
        check(!hasType(RuleEngine.findLeads(hand, noFour), noFour, ComboType.FOUR_TWO_SINGLE),
                "generator stops emitting 四带二 when the switch is off");
        check(!hasType(RuleEngine.findLeads(hand, noFour), noFour, ComboType.FOUR_TWO_PAIR),
                "generator stops emitting 四带两对 when the switch is off");

        // 两副牌变体：三带与四带都能关，其余不受影响
        RuleOptions variant = RuleOptions.doubleDeck().withThreeWithTwo(false).withFourWithTwo(false);
        check(variant.fourPlayer(), "variant stays a four-player rule set");
        check(RuleEngine.classify(new int[]{3, 3, 3, 3, 3, 3, 3, 3}, variant).type() == ComboType.BOMB,
                "eight of a kind is still a bomb in the variant");
        check(RuleEngine.classify(new int[]{3, 4, 5, 6, 7}, variant).type() == ComboType.STRAIGHT,
                "straights are untouched by the variant");
    }

    // ------------------------------------------------------------------ 工具

    private static boolean hasType(List<int[]> moves, RuleOptions options, ComboType type) {
        for (int[] move : moves) {
            Combo combo = RuleEngine.classify(move, options);
            if (combo != null && combo.type() == type) {
                return true;
            }
        }
        return false;
    }

    private static boolean covers(int[] hand, int[] move) {
        return coversCounts(RuleEngine.counts(hand), RuleEngine.counts(move));
    }

    private static boolean coversCounts(int[] hand, int[] used) {
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (used[value] > hand[value]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSorted(int[] keys) {
        for (int i = 1; i < keys.length; i++) {
            if (keys[i] < keys[i - 1]) {
                return false;
            }
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (condition) {
            passed++;
            System.out.println("  ok    " + message);
        } else {
            failed++;
            System.out.println("  FAIL  " + message);
        }
    }

    private static List<Integer> ints(int... values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }
}
