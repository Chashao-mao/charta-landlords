package chartalandlords.doudizhu.game.engine;

/**
 * 强搜索策略的引擎级调用面：把「只含公开信息」的局面整理成 {@link AgentPolicy.Info}，
 * 交给搜索策略算，再对结果做一次兜底校验（牌在手上 / 牌型合法 / 压得过上一手）。
 *
 * <p>它是纯逻辑，所以离线基准、GameTest 与游戏层可以共用同一个入口；游戏层接线时只需要把
 * {@link AiContext} 缺的两项（几个人、自己坐哪）一起传进来——这也是下一步要给 AiContext 补的字段。</p>
 */
public final class StrongAi {

    private static final long SEED = 0xC1A6E7L;

    private StrongAi() {}

    /**
     * @param values        自己的手牌点数
     * @param previous      上一手（null = 首出）
     * @param landlordSeat  地主座位（-1 = 还没定）
     * @param seat          自己的座位
     * @param seats         本局人数
     * @param handSizes     各家剩余张数
     * @param lastPlaySeat  上一手是谁出的（-1 = 首出）
     * @param passCount     已经连续不出的人数
     * @param unseen        还没露面的点数计数
     * @param heldBottom    仍攥在地主手里的底牌点数
     * @param rule          规则开关
     * @param level         AI 档位：0 保守 / 1 均衡 / 2 激进（2 时用搜索，其余用内置策略）
     * @return 要出的牌；null = 不出
     */
    public static int[] choose(int[] values, Combo previous, int landlordSeat, int seat, int seats,
                               int[] handSizes, int lastPlaySeat, int passCount, int[] unseen, int[] heldBottom,
                               RuleOptions rule, int level) {
        if (values == null || values.length == 0) {
            return null;
        }
        RuleOptions options = rule == null ? RuleOptions.standard() : rule;
        boolean landlord = seat == landlordSeat;
        AgentPolicy.Info info = new AgentPolicy.Info(options, Math.max(2, seats), landlordSeat, handSizes,
                lastPlaySeat, passCount, unseen == null ? new int[RuleEngine.RANK_SLOTS] : unseen,
                heldBottom == null ? new int[0] : heldBottom);
        AgentPolicy policy = new CooperativePolicy(
                level >= 2 ? new SearchPolicy(SEED) : new DefaultPolicy(AiProfile.of(level)));
        int[] play = policy.choose(values, previous, landlord, seat, info);
        if (play == null || play.length == 0) {
            return null;
        }
        if (previous == null && !isLegal(values, play, null, options)) {
            return null;
        }
        return isLegal(values, play, previous, options) ? play : null;
    }

    /** 兜底校验：牌真在手上、牌型合法、压得过上一手。 */
    public static boolean isLegal(int[] values, int[] play, Combo previous, RuleOptions rule) {
        int[] hand = RuleEngine.counts(values);
        for (int value : play) {
            if (value < 0 || value >= hand.length || hand[value]-- <= 0) {
                return false;
            }
        }
        Combo combo = RuleEngine.classify(play, rule);
        return combo != null && RuleEngine.beats(combo, previous);
    }
}