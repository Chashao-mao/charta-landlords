package chartalandlords.doudizhu.game.engine;

/**
 * 农民配合策略（{@link CooperativePolicy}）的离线断言：不压队友、地主快走完就封杀。
 *
 * <p>零 Minecraft 依赖，由 {@code tools/logic-check.ps1} 用裸 javac/java 跑。</p>
 */
public final class CoopHarnessMain {

    private static int passed;
    private static int failed;
    private static final RuleOptions RULE = RuleOptions.doubleDeck();

    private CoopHarnessMain() {}

    public static void main(String[] args) {
        int[] hand = {3, 3, 4, 5, 6, 7, 7, 7};          // 自己是农民，手里有一对 3 / 三张 7
        int[] pairThrees = {3, 3};
        int[] tripleSevens = {7, 7, 7};

        // 场景：队友（座位 0）刚打出一对 5，轮到我（座位 1）；地主 1 = 座位 2
        AgentPolicy greedy = fixed(pairThrees);                       // 委托策略：想用一对 3 压
        CooperativePolicy coop = new CooperativePolicy(greedy);
        AgentPolicy.Info teammateLed = info(RULE, new int[]{11, 8, 9}, 0, 2, new int[]{5, 5});

        check(coop.choose(hand, combo(new int[]{5, 5}), false, 1, teammateLed) == null,
                "队友领出时不压队友（委托策略想压也被拦下）");

        AgentPolicy finisher = fixed(hand.clone());                    // 委托策略：一把走完
        CooperativePolicy coopFinisher = new CooperativePolicy(finisher);
        check(coopFinisher.choose(hand, combo(new int[]{5, 5}), false, 1, teammateLed) != null,
                "能一把走完时不受「不压队友」限制");

        // 地主只剩 2 张：委托策略想不出，配合策略必须挑一手压住
        AgentPolicy passive = fixed(null);
        CooperativePolicy blocker = new CooperativePolicy(passive);
        AgentPolicy.Info landlordTwo = info(RULE, new int[]{8, 9, 2}, 2, 2, new int[]{5, 5});   // 下标 = 座位，地主(2)只剩 2 张   // 2 = 地主自己领出
        int[] blockPlay = blocker.choose(hand, combo(new int[]{5, 5}), false, 1, landlordTwo);
        check(blockPlay != null, "地主剩 2 张时必须有牌压住");
        check(blockPlay == null || StrongAi.isLegal(hand, blockPlay, combo(new int[]{5, 5}), RULE),
                "强行压出的那一手是合法的");

        // 地主还有很多牌：委托策略不出，配合策略也不该硬出
        AgentPolicy.Info landlordMany = info(RULE, new int[]{9, 8, 9}, 2, 2, new int[]{5, 5});
        check(blocker.choose(hand, combo(new int[]{5, 5}), false, 1, landlordMany) == null,
                "地主牌还多时不为了压而压");

        // 自己是地主：没有队友，委托策略的选择原样放行
        check(new CooperativePolicy(fixed(tripleSevens)).choose(hand, combo(new int[]{5, 5}),
                        true, 1, info(RULE, new int[]{8, 9, 9}, 0, 2, new int[]{5, 5})) != null,
                "地主身份下配合规则不生效");

        System.out.println("COOP HARNESS " + (failed == 0 ? "PASS" : "FAIL")
                + ": " + (passed + failed) + " checks, " + failed + " failures");
        if (failed > 0) {
            throw new IllegalStateException(failed + " coop checks failed");
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 固定出某一手的委托策略。 */
    private static AgentPolicy fixed(int[] play) {
        return new AgentPolicy() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public int[] choose(int[] hand, Combo previous, boolean landlord, int seat, Info info) {
                return play == null ? null : play.clone();
            }
        };
    }

    private static AgentPolicy.Info info(RuleOptions rule, int[] handSizes, int lastPlaySeat, int seat,
                                         int[] unseen) {
        return new AgentPolicy.Info(rule, handSizes.length, 2, handSizes, lastPlaySeat, 0, unseen, new int[0]);
    }

    private static Combo combo(int[] values) {
        return RuleEngine.classify(values, RULE);
    }

    private static void check(boolean condition, String label) {
        if (condition) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((condition ? "  ok    " : "  FAIL  ") + label);
    }
}