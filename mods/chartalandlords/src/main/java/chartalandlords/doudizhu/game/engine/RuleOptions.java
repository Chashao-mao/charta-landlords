package chartalandlords.doudizhu.game.engine;

/**
 * 规则开关与长度上限（纯数据，不依赖 Minecraft/Charta）。
 *
 * <p>派生的 {@code doubleDeck()} 只切换 {@code fourPlayer}，其余开关沿用同一套默认值，
 * 因此 54 张（3 人）与 108 张（4 人两副牌）不会出现两套互相矛盾的规则。
 * 想要 Pagat 记载的严格两副牌变体（禁用三带与四带）就显式关掉：
 * {@code RuleOptions.doubleDeck().withThreeWithTwo(false).withFourWithTwo(false)}。</p>
 *
 * @param fourPlayer               4 人两副牌（王炸 = 两小王 + 两大王）
 * @param threeWithTwo             允许三带一 / 三带二（默认 true；两副牌变体可关）
 * @param fourWithTwo              允许四带二 / 四带两对（默认 true；两副牌变体可关）
 * @param fourTwoSinglesMayBePair  四带二的「二」可以是一对（默认 true）
 * @param jokersAsWingPair         允许大小王同时作为同一手牌的带牌（默认 false，Pagat 禁止）
 * @param maxStraight              顺子最大长度（张），默认 12（3..A）
 * @param maxPairStraight          连对最大对数，默认 12
 * @param maxAirplane              飞机最大三张数，默认 6
 * @param maxAirplaneSingle        飞机带单最大三张数，默认 5
 * @param maxAirplanePair          飞机带对最大三张数，默认 4
 */
public record RuleOptions(
        boolean fourPlayer,
        boolean threeWithTwo,
        boolean fourWithTwo,
        boolean fourTwoSinglesMayBePair,
        boolean jokersAsWingPair,
        int maxStraight,
        int maxPairStraight,
        int maxAirplane,
        int maxAirplaneSingle,
        int maxAirplanePair
) {

    /** 顺子/连对/飞机的硬上限：3..A 共 12 个点数，任何规则下都不会更长。 */
    public static final int HARD_CHAIN_LIMIT = 12;

    public RuleOptions {
        // 上限只做下限保护：非法（<=0）时回落到硬上限，避免生成器出现死循环。
        maxStraight = sane(maxStraight);
        maxPairStraight = sane(maxPairStraight);
        maxAirplane = sane(maxAirplane);
        maxAirplaneSingle = sane(maxAirplaneSingle);
        maxAirplanePair = sane(maxAirplanePair);
    }

    private static int sane(int value) {
        return value <= 0 ? HARD_CHAIN_LIMIT : Math.min(value, HARD_CHAIN_LIMIT);
    }

    /** 3 人局默认规则。 */
    public static RuleOptions standard() {
        return new RuleOptions(false, true, true, true, false, 12, 12, 6, 5, 4);
    }

    /** 4 人两副牌默认规则。 */
    public static RuleOptions doubleDeck() {
        return new RuleOptions(true, true, true, true, false, 12, 12, 6, 5, 4);
    }

    /** 便捷工厂：{@code true} → {@link #doubleDeck()}，否则 {@link #standard()}。 */
    public static RuleOptions of(boolean fourPlayer) {
        return fourPlayer ? doubleDeck() : standard();
    }

    public RuleOptions withThreeWithTwo(boolean value) {
        return new RuleOptions(fourPlayer, value, fourWithTwo, fourTwoSinglesMayBePair, jokersAsWingPair,
                maxStraight, maxPairStraight, maxAirplane, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withFourWithTwo(boolean value) {
        return new RuleOptions(fourPlayer, threeWithTwo, value, fourTwoSinglesMayBePair, jokersAsWingPair,
                maxStraight, maxPairStraight, maxAirplane, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withFourTwoSinglesMayBePair(boolean value) {
        return new RuleOptions(fourPlayer, threeWithTwo, fourWithTwo, value, jokersAsWingPair,
                maxStraight, maxPairStraight, maxAirplane, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withJokersAsWingPair(boolean value) {
        return new RuleOptions(fourPlayer, threeWithTwo, fourWithTwo, fourTwoSinglesMayBePair, value,
                maxStraight, maxPairStraight, maxAirplane, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withFourPlayer(boolean value) {
        return new RuleOptions(value, threeWithTwo, fourWithTwo, fourTwoSinglesMayBePair, jokersAsWingPair,
                maxStraight, maxPairStraight, maxAirplane, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withMaxStraight(int value) {
        return new RuleOptions(fourPlayer, threeWithTwo, fourWithTwo, fourTwoSinglesMayBePair, jokersAsWingPair,
                value, maxPairStraight, maxAirplane, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withMaxPairStraight(int value) {
        return new RuleOptions(fourPlayer, threeWithTwo, fourWithTwo, fourTwoSinglesMayBePair, jokersAsWingPair,
                maxStraight, value, maxAirplane, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withMaxAirplane(int value) {
        return new RuleOptions(fourPlayer, threeWithTwo, fourWithTwo, fourTwoSinglesMayBePair, jokersAsWingPair,
                maxStraight, maxPairStraight, value, maxAirplaneSingle, maxAirplanePair);
    }

    public RuleOptions withMaxAirplaneSingle(int value) {
        return new RuleOptions(fourPlayer, threeWithTwo, fourWithTwo, fourTwoSinglesMayBePair, jokersAsWingPair,
                maxStraight, maxPairStraight, maxAirplane, value, maxAirplanePair);
    }

    public RuleOptions withMaxAirplanePair(int value) {
        return new RuleOptions(fourPlayer, threeWithTwo, fourWithTwo, fourTwoSinglesMayBePair, jokersAsWingPair,
                maxStraight, maxPairStraight, maxAirplane, maxAirplaneSingle, value);
    }
}
