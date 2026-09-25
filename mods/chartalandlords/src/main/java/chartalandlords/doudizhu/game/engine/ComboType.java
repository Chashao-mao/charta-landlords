package chartalandlords.doudizhu.game.engine;

import java.util.Locale;

/** 支持的牌型。 */
public enum ComboType {
    SINGLE,
    PAIR,
    TRIPLE,
    TRIPLE_SINGLE,
    TRIPLE_PAIR,
    STRAIGHT,
    DOUBLE_STRAIGHT,
    AIRPLANE,
    AIRPLANE_SINGLE,
    AIRPLANE_PAIR,
    FOUR_TWO_SINGLE,
    FOUR_TWO_PAIR,
    BOMB,
    ROCKET;

    /** 炸弹与王炸：可以压任意非炸弹牌型。 */
    public boolean isNuke() {
        return this == BOMB || this == ROCKET;
    }

    public boolean isSequence() {
        return this == STRAIGHT || this == DOUBLE_STRAIGHT || this == AIRPLANE
                || this == AIRPLANE_SINGLE || this == AIRPLANE_PAIR;
    }

    public String descriptionKey() {
        return "message.chartalandlords.combo." + name().toLowerCase(Locale.ROOT);
    }
}
