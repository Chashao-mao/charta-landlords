package chartalandlords.doudizhu.game.engine;


/**
 * 把 ChartaLandlords 内置的 {@link AiPolicy} 接成代理策略。
 *
 * <p>两个用途：</p>
 * <ul>
 *   <li><b>基线</b>：离线基准里当对手，量出「默认 AI 有多强」；</li>
 *   <li><b>兜底</b>：搜索策略算不出来（超预算 / 局面太大）时退回它，保证代理永远不会比默认 AI 更差。</li>
 * </ul>
 *
 * <p>上下文按公开信息重建：地主身份、队友是否刚出过、各家张数、没露面的牌——和模组本体里
 * {@code DoudizhuGame} 给内置 AI 的那份信息等价，没有多给任何东西。</p>
 */
public final class DefaultPolicy implements AgentPolicy {

    private final AiProfile profile;

    public DefaultPolicy() {
        this(AiProfile.balanced());
    }

    public DefaultPolicy(AiProfile profile) {
        this.profile = profile == null ? AiProfile.balanced() : profile;
    }

    public AiProfile profile() {
        return profile;
    }

    @Override
    public String name() {
        return "default(" + profile.id() + ")";
    }

    @Override
    public int[] choose(int[] hand, Combo previous, boolean landlord, int seat, Info info) {
        return AiPolicy.choosePlayValues(hand, context(hand, previous, landlord, seat, info), info.rule(), profile);
    }

    /** 按公开信息拼一个内置 AI 的决策上下文。 */
    public static AiContext context(int[] hand, Combo previous, boolean landlord, int seat, Info info) {
        boolean teammateLed = !landlord && info.lastPlaySeat() >= 0
                && !info.sameSide(seat, info.lastPlaySeat());
        if (previous == null) {
            return AiContext.lead(landlord, minOpponent(info, seat), info.unseen().clone());
        }
        return AiContext.following(previous, landlord, teammateLed,
                teammateHand(info, seat), landlordHand(info, seat), minOpponent(info, seat),
                info.unseen().clone());
    }

    static int landlordHand(Info info, int seat) {
        int landlord = info.landlord();
        return seat == landlord ? info.handSizes()[seat] : info.handSizes()[landlord];
    }

    /** 队友（其他农民）里最少的手牌数；自己不是农民时 -1。 */
    static int teammateHand(Info info, int seat) {
        if (seat == info.landlord()) {
            return -1;
        }
        int smallest = -1;
        for (int other = 0; other < info.seats(); other++) {
            if (other == seat || info.sameSide(other, info.landlord())) {
                continue;
            }
            smallest = smallest < 0 ? info.handSizes()[other] : Math.min(smallest, info.handSizes()[other]);
        }
        return smallest;
    }

    /** 对手里最少的手牌数：自己是地主 → 农民最少；自己是农民 → 只有地主。 */
    static int minOpponent(Info info, int seat) {
        if (seat == info.landlord()) {
            return teammateHand(info, seat);
        }
        return info.handSizes()[info.landlord()];
    }
}
