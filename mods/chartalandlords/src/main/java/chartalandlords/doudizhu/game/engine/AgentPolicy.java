package chartalandlords.doudizhu.game.engine;


/**
 * 客户端代理 AI 的策略接口：给自己手牌 + 公开信息 → 要出的牌（点数数组），
 * {@code null} 表示不出（跟牌时才可以不出）。
 *
 * <p>接口刻意只传「公开信息」：代理要在客户端<b>公平地</b>打赢默认 AI，而不是靠读别人的手牌。
 * 每个实现都必须是纯逻辑（零 Minecraft / Charta 依赖），这样离线自对弈基准能直接量它的胜率。</p>
 */
public interface AgentPolicy {

    String name();

    int[] choose(int[] hand, Combo previous, boolean landlord, int seat, Info info);

    /**
     * 公开信息（谁都看得到的东西）。
     *
     * @param rule        规则开关
     * @param seats       人数
     * @param landlord    地主座位
     * @param handSizes   各家剩余张数（含自己）
     * @param lastPlaySeat 上一手是谁出的，-1 表示首出
     * @param passCount   已经连续不出的人数
     * @param unseen      还没露面的点数计数（总牌堆 − 自己手牌 − 已打出 − 仍在地主手里的底牌）
     * @param bottom      **仍攥在地主手里的**底牌点数（打出去的不算；自己不当地主时才是「已知的别人牌」）
     */
    record Info(RuleOptions rule, int seats, int landlord, int[] handSizes, int lastPlaySeat, int passCount,
                int[] unseen, int[] bottom) {

        /** 地主阵营 / 农民阵营：判断胜负时用。 */
        public boolean sameSide(int a, int b) {
            return (a == landlord) == (b == landlord);
        }
    }
}
