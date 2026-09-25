package chartalandlords.doudizhu.game.engine;

import java.util.Arrays;

/**
 * 一局结算（纯逻辑，不依赖 Minecraft/Charta）。
 *
 * <h2>计分规则</h2>
 * <ul>
 *   <li><b>底分</b> = 叫分（1..3；抢地主模式下固定为 1）。叫分因此不再只是「谁当地主」，而是这一局的注码。</li>
 *   <li><b>局倍数</b>：每出一个炸弹或王炸翻一倍；春天 / 反春天各再翻一倍；
 *       抢地主模式下每多一个人抢再翻一倍（见 {@code extraDoublings}）。</li>
 *   <li><b>个人倍数</b>：明牌与加倍各翻一倍，<b>只作用于该玩家</b>。
 *       地主与每个农民逐对结算：{@code 单位分 = 底分 × 局倍数}，
 *       某一对（地主 ↔ 农民 i）的实际注码是 {@code 单位分 × 地主个人倍数 × 农民 i 个人倍数}。</li>
 *   <li><b>结算</b>：地主赢则从每个农民那里赢走上面那个注码，输则赔给每个农民同样的注码。
 *       因此任意人数、任意个人倍数下都是零和（个人倍数只会放大对应那一对，不会凭空造分）。</li>
 * </ul>
 *
 * <p>不做的事：四人两副牌的「8 张以上炸弹 ×3」与「每局固定底分池」都没有实现——
 * 它们不是通用规则，需要的话在 {@link #settle} 外面再叠一层即可。</p>
 */
public final class DoudizhuScore {

    private DoudizhuScore() {}

    /** 倍数上限：只用于防止 {@code 1 << n} 溢出，正常一局远远到不了。 */
    private static final int MAX_DOUBLINGS = 24;

    /**
     * 一局结算结果。
     *
     * @param baseBid      底分（叫分，1..3）
     * @param multiplier   局倍数（炸弹/王炸 + 春天/反春天 + 抢地主）
     * @param unit         局单位分 = 底分 × 局倍数（还没乘个人倍数）
     * @param spring       是否春天
     * @param antiSpring   是否反春天
     * @param landlordWon  地主是否获胜
     * @param factors      每个座位的个人倍数（1 / 2 / 4；下标 = 座位）
     * @param deltas       每个座位的分数变化（下标 = 座位，零和）
     */
    public record Settlement(int baseBid, int multiplier, int unit, boolean spring, boolean antiSpring,
                             boolean landlordWon, int[] factors, int[] deltas) {

        /** 某个座位的分数变化。 */
        public int delta(int seat) {
            return seat >= 0 && seat < deltas.length ? deltas[seat] : 0;
        }

        /** 某个座位的个人倍数。 */
        public int factor(int seat) {
            return seat >= 0 && seat < factors.length ? factors[seat] : 1;
        }

        /**
         * 地主与座位 {@code seat} 之间这一对的实际注码。
         *
         * @return 地主座位或越界座位返回 0（地主和自己不成对）
         */
        public int pairUnit(int seat, int landlordSeat) {
            if (seat == landlordSeat || seat < 0 || seat >= deltas.length) {
                return 0;
            }
            return unit * factor(landlordSeat) * factor(seat);
        }

        /** 全部座位分数之和（恒为 0，供断言与调试验证）。 */
        public int total() {
            return Arrays.stream(deltas).sum();
        }
    }

    /**
     * 不含个人倍数与抢地主的结算（等价于「个人倍数全 1、抢地主翻倍为 0」）。
     *
     * @param baseBid       叫分（&lt;1 时按 1 处理）
     * @param seatCount     座位数
     * @param landlordSeat  地主座位（越界时不产生地主加成）
     * @param landlordWon   地主是否获胜
     * @param nukes         本局打出的炸弹 + 王炸数
     * @param landlordPlays 地主出牌手数
     * @param farmersPlays  农民合计出牌手数
     */
    public static Settlement settle(int baseBid, int seatCount, int landlordSeat, boolean landlordWon,
                                    int nukes, int landlordPlays, int farmersPlays) {
        return settle(baseBid, seatCount, landlordSeat, landlordWon, nukes, landlordPlays, farmersPlays, 0, null);
    }

    /**
     * 完整结算。
     *
     * @param extraDoublings 额外的局倍数指数（抢地主：抢 n 次记 {@code n - 1}）
     * @param seatDoublings  每个座位的个人倍数指数（明牌 +1、加倍 +1）；null 或长度不足时按 0 处理
     */
    public static Settlement settle(int baseBid, int seatCount, int landlordSeat, boolean landlordWon,
                                    int nukes, int landlordPlays, int farmersPlays, int extraDoublings,
                                    int[] seatDoublings) {
        int seats = Math.max(1, seatCount);
        int bid = Math.max(1, baseBid);
        boolean spring = landlordWon && farmersPlays <= 0;
        boolean antiSpring = !landlordWon && landlordPlays <= 1;
        int doublings = Math.max(0, nukes) + (spring ? 1 : 0) + (antiSpring ? 1 : 0)
                + Math.max(0, extraDoublings);
        int multiplier = 1 << Math.min(MAX_DOUBLINGS, doublings);
        int unit = bid * multiplier;
        int[] factors = new int[seats];
        for (int seat = 0; seat < seats; seat++) {
            int exponent = seatDoublings != null && seat < seatDoublings.length ? Math.max(0, seatDoublings[seat]) : 0;
            factors[seat] = 1 << Math.min(MAX_DOUBLINGS, exponent);
        }
        int[] deltas = new int[seats];
        if (landlordSeat >= 0 && landlordSeat < seats) {
            int landlordFactor = factors[landlordSeat];
            for (int seat = 0; seat < seats; seat++) {
                if (seat == landlordSeat) {
                    continue;
                }
                // 逐对结算：这一对的注码只受「地主个人倍数 × 该农民个人倍数」影响
                int pairUnit = unit * landlordFactor * factors[seat];
                int landlordDelta = landlordWon ? pairUnit : -pairUnit;
                deltas[landlordSeat] += landlordDelta;
                deltas[seat] = -landlordDelta;
            }
        }
        // 没有地主（牌局中断）：全体 0 分，仍然零和
        return new Settlement(bid, multiplier, unit, spring, antiSpring, landlordWon, factors, deltas);
    }

    /** 只有炸弹、没有春天时的倍数（测试与展示用）。 */
    public static int nukeMultiplier(int nukes) {
        return 1 << Math.min(MAX_DOUBLINGS, Math.max(0, nukes));
    }
}
