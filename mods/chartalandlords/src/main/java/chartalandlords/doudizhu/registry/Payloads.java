package chartalandlords.doudizhu.registry;

import chartalandlords.doudizhu.network.GameActionPayload;
import chartalandlords.doudizhu.network.MoveCardPayload;
import chartalandlords.doudizhu.network.SelectCardPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 本模组自有的 C2S 网络包注册。
 *
 * <h2>协议端口 / protocol port</h2>
 * <p>NeoForge 通过 {@code registrar(version)} 的版本字符串做握手校验：两端字符串不一致时，
 * 本模组的自定义包会被直接拒绝，而不是静默按错误的偏移解析。所以这里是「网络协议端口」的
 * 唯一来源：<b>任何 payload 的字段增删、含义变化或动作序号变化，都必须同时提升
 * {@link #PROTOCOL_VERSION}</b>。</p>
 *
 * <p>取值规则：跟随 Minecraft 大版本起步（1.21.1 → {@code "1"}），payload 线格式或<b>语义</b>变更时
 * 递增（{@code 1 → 2 → 3 …}）。与 Charta 本体保持一致（上游同样从 {@code "1"} 起步），
 * 避免扩展模组与本体风格不一。</p>
 *
 * <p>历史：{@code "8"} 起，界面卡槽重新编号：底牌不再占一条整行（改成面板顶部的小牌预览）、
 * 明牌展示不再画成牌带（改成只当数据通道、摆在屏幕外），于是 {@code SelectCardPayload.slotId}
 * 指向的卡槽整体变化。{@code "7"} 起，界面里新增「明牌展示行」并且注册在所有牌带<b>之前</b>，
 * 于是 {@code SelectCardPayload.slotId} 指向的卡槽整体后移——这是实打实的 slotId 含义变更。
 * {@code "6"} 起，客户端多了一个<b>发送能力</b>（{@code chartalandlords:card_move}，
 * 手牌拖拽排序）以及新的动作 id 与数据槽。包本身是纯追加、旧客户端不发也不会解析错，
 * 但新客户端对着旧服务端一拖牌就会发出对方不认识的 payload id，所以仍然是两端不一致，
 * 必须靠握手挡住。{@code "5"} 起，「明牌 / 加倍阶段」由「一人一次二选一」改成「两个开关 + 确认」——
 * 明牌与加倍可以同时选（个人倍数 ×4），{@link chartalandlords.doudizhu.game.DoudizhuAction#DECLARE_PASS}
 * 的含义由「跳过」变成「确认并结束」。{@code "4"} 起，{@code DoudizhuGame.Phase} 追加了 {@code DECLARING}
 * （明牌 / 加倍阶段，排在 {@code PLAYING} 之前）并新增三个动作 id；{@code DATA_PHASE} 同步的是枚举序号，
 * 所以序号重排同样是语义变更。{@code "3"} 起，资源命名空间由 {@code doudizhu} 改为
 * {@code chartalandlords}，payload id 与注册身份全部改变（新旧版本无法互通）。{@code "2"} 起，
 * {@code SelectCardPayload.slotId} 的含义随界面绘制顺序重排而改变
 * （底牌 → 上一手 → 出牌区 → 手牌的注册顺序）。新旧客户端在这些版本下会把同一个 slotId
 * 解释成不同的卡槽，因此必须靠协议版本把它们挡在门外。</p>
 *
 * <p>payload id 命名同样对齐上游的 {@code <mod_id>:<snake_case_描述>}：
 * {@code doudizhu:game_action}、{@code doudizhu:card_select}。</p>
 */
public final class Payloads {

    /** 网络协议端口；见类注释，payload 线格式或语义变化时必须提升。 */
    public static final String PROTOCOL_VERSION = "8";

    private Payloads() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(SelectCardPayload.TYPE, SelectCardPayload.STREAM_CODEC, SelectCardPayload::handle);
        registrar.playToServer(GameActionPayload.TYPE, GameActionPayload.STREAM_CODEC, GameActionPayload::handle);
        // 1.5.0 追加：手牌手动排序。纯追加的 payload id，旧客户端不发它、也不会解析错任何东西，
        // 所以这一条不构成协议端口的提升理由（端口升到 "5" 是因为 DECLARE_PASS 的语义变了）。
        registrar.playToServer(MoveCardPayload.TYPE, MoveCardPayload.STREAM_CODEC, MoveCardPayload::handle);
    }
}
