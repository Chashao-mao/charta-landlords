package chartalandlords.doudizhu.game.engine;

/**
 * 一手合法牌成型的描述。
 *
 * @param type   牌型
 * @param key    比较用关键点数（顺子/连对/飞机取最大点数）
 * @param length 关键结构长度（顺子张数、连对对数、飞机三张数、其余为 1）
 * @param size   总张数
 * @param bomb   是否炸弹/王炸
 */
public record Combo(ComboType type, int key, int length, int size, boolean bomb) {

    public boolean isNuke() {
        return bomb;
    }
}
