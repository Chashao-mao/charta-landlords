package chartalandlords.doudizhu.game;

/**
 * 客户端按钮 → 服务端动作。
 *
 * <p><b>协议约定：</b>{@link #id()} 就是网络包里的 {@code actionId}。新动作只允许<b>追加</b>在枚举
 * 末尾——插入或删除都会平移后面所有动作的序号，让旧客户端在服务端触发错误的行为。任何顺序变化
 * 都必须同时提升 {@code Payloads.PROTOCOL_VERSION}。</p>
 */
public enum DoudizhuAction {
    PLAY,
    PASS,
    HINT,
    BID_1,
    BID_2,
    BID_3,
    BID_PASS,
    /** 右键撤回：把出牌区选中的牌全部退回手牌（1.0.0 之后追加，保持既有动作序号稳定）。 */
    RETRACT,
    /**
     * 明牌：把自己的手牌公开给所有人，个人倍数 ×2（1.3.0 追加）。
     *
     * <p>追加在末尾，前 8 个动作的 {@link #id()} 完全不变；但 1.3.0 的「明牌 / 加倍阶段」也把
     * {@code DoudizhuGame.Phase} 的顺序改了，所以协议端口同样从 {@code "3"} 升到 {@code "4"}。</p>
     *
     * <p>1.5.0 起明牌与加倍可以<b>同时选</b>（个人倍数 ×4），于是这一阶段的动作语义变成
     * 「两个开关 + 确认」：{@link #REVEAL_HAND} 与 {@link #DOUBLE_UP} 是开关，
     * {@link #DECLARE_PASS} 是「结束我的决定」。动作序号一个都没动，但 {@code DECLARE_PASS}
     * 的含义从「跳过」变成「确认并结束」，属于语义变更，所以协议端口升到 {@code "5"}。</p>
     */
    REVEAL_HAND,
    /** 加倍：个人倍数 ×2，可在确认前反复切换（1.3.0 追加）。 */
    DOUBLE_UP,
    /**
     * 结束明牌 / 加倍阶段里「我」的决定（1.3.0 追加，1.5.0 起含义由「跳过」变为「确认并结束」）。
     *
     * <p>两个开关是<b>按下即生效</b>的：明牌会立刻把牌面公开，加倍会立刻改变个人倍数。
     * 所以这个动作只表示「我不再改了」，不携带任何选择内容。</p>
     */
    DECLARE_PASS,
    /**
     * 整理手牌：清掉手动牌序，按点数重新排一遍（1.5.0 追加）。
     *
     * <p>手动拖过牌之后手牌就不再自动排序了，所以需要一个「回到默认顺序」的出口。</p>
     */
    TIDY_HAND;

    public int id() {
        return ordinal();
    }

    public static DoudizhuAction byId(int id) {
        DoudizhuAction[] values = values();
        return id >= 0 && id < values.length ? values[id] : null;
    }

    /** 叫分动作携带的分值（不叫返回 0）；非叫分动作返回 -1。 */
    public int bidScore() {
        return switch (this) {
            case BID_1 -> 1;
            case BID_2 -> 2;
            case BID_3 -> 3;
            case BID_PASS -> 0;
            default -> -1;
        };
    }
}
