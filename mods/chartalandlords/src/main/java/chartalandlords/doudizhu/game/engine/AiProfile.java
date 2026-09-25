package chartalandlords.doudizhu.game.engine;

/**
 * AI 性格档位（纯数据，不依赖 Minecraft/Charta）。
 *
 * <p>同一个引擎、同一套规则，只调这些权重就能从「保守」到「激进」，所以难度不写死在策略代码里，
 * 而是作为一张查表传进 {@link AiPolicy}。牌局选项里选档位，{@link #of(int)} 负责把选项值翻回来。</p>
 *
 * <h2>叫分 / 抢地主的门槛为什么是「相对值」</h2>
 * <p>{@link #bidOne} / {@link #bidTwo} / {@link #bidThree} 与 {@link #grabBase} 都是<b>相对控场分基线
 * 的偏移</b>，不是绝对分。原因是 {@link HandShape#controlScore} 的绝对值和手牌张数强相关：
 * 实测 3 人局 17 张的均值是 2，4 人局 25 张的均值是 12（多出来的 2 / 王 / 炸弹更多）。
 * 如果门槛写成绝对值，4 人局就会出现「几乎所有手牌都够叫 3 分」的退化现象——
 * 加了基线之后，同一张表在两种人数下都是合理的概率。</p>
 *
 * @param id                内部标识（日志用）
 * @param bidOne            控场分比基线高这么多就叫 1 分
 * @param bidTwo            高这么多叫 2 分
 * @param bidThree          高这么多叫 3 分
 * @param bombAt            对手剩这么多张时就必须动炸弹
 * @param blockAt           对手剩这么多张时进入「封杀」模式：跟牌宁可出大牌也不出小牌，首出尽量出他接不住的牌型
 * @param nukePenalty       主动领出炸弹/王炸的惩罚
 * @param controlPenalty    主动领出 2 / 大小王的惩罚
 * @param breakSetPenalty   拆对/拆三/拆炸的惩罚
 * @param overshootPenalty  严重超压（用大牌压小牌）的惩罚
 * @param leadEvaluationCap 首出时做「最少手数」评估的候选数上限（越大越慢也越准）
 * @param grabBase          抢地主：比基线高这么多就敢当第一个抢的人
 * @param grabStep          抢地主：每多一个已经抢过的人，门槛再抬高多少（倍数变大、风险变大）
 * @param grabLastDiscount  抢地主：自己是最后一个决定的人时门槛降低多少（没人能再抬价了）
 * @param revealMargin      明牌：比基线高这么多才敢亮牌（亮牌送情报，牌不够好就是白亏）
 * @param doubleMargin      加倍：比基线高这么多就敢加倍
 */
public record AiProfile(
        String id,
        int bidOne,
        int bidTwo,
        int bidThree,
        int bombAt,
        int blockAt,
        int nukePenalty,
        int controlPenalty,
        int breakSetPenalty,
        int overshootPenalty,
        int leadEvaluationCap,
        int grabBase,
        int grabStep,
        int grabLastDiscount,
        int revealMargin,
        int doubleMargin
) {

    /** 保守：叫分门槛高、能不炸就不炸、超压惩罚重、宁可拆牌也不乱丢大牌，也不太愿意抢/亮/加倍。 */
    public static AiProfile conservative() {
        return new AiProfile("conservative", 5, 9, 13, 1, 1, 2600, 900, 160, 90, 24, 4, 3, 2, 10, 5);
    }

    /** 均衡：默认档，就是本模组长期调出来的那套权重。 */
    public static AiProfile balanced() {
        return new AiProfile("balanced", 3, 7, 11, 2, 2, 2000, 600, 120, 40, 32, 2, 2, 2, 8, 3);
    }

    /** 激进：敢叫、敢抢、敢亮、敢炸、愿意用大牌抢节奏换手数，对手还剩 3 张就开始封杀。 */
    public static AiProfile aggressive() {
        return new AiProfile("aggressive", 1, 4, 8, 3, 3, 900, 250, 70, 10, 40, 0, 1, 3, 6, 1);
    }

    /** 牌局选项的取值：0 = 保守，1 = 均衡，2 = 激进。越界一律回落到均衡。 */
    public static AiProfile of(int option) {
        return switch (option) {
            case 0 -> conservative();
            case 2 -> aggressive();
            default -> balanced();
        };
    }

    /** 反向映射，供选项界面回显。 */
    public int ordinal() {
        if (id.equals(conservative().id())) {
            return 0;
        }
        return id.equals(aggressive().id()) ? 2 : 1;
    }

    /** 选项界面上的档位数。 */
    public static final int OPTION_COUNT = 3;
}
