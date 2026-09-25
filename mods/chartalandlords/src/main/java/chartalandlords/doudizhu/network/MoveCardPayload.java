package chartalandlords.doudizhu.network;

import chartalandlords.doudizhu.Doudizhu;
import chartalandlords.doudizhu.game.DoudizhuMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * C2S：在<b>手牌内部</b>把一张牌拖到另一个位置（手动调整牌序）。
 *
 * <p>和 {@link SelectCardPayload} 分开是两个原因：</p>
 * <ol>
 *   <li><b>语义不同</b>：这个包只改变手牌内部的顺序，不改变「手牌 / 出牌区」的归属，
 *       所以它绝不能和选牌共用一个入口——两者对不合法参数的处理完全不同（选牌失败要静默忽略，
 *       移动失败也必须静默忽略，但两者的边界不一样）；</li>
 *   <li><b>不改已有包的字段</b>：给 {@code SelectCardPayload} 加一个「目标位置」字段会让
 *       <b>所有</b>旧客户端的选牌请求被按新格式解析，所以新增一个 payload id 是唯一安全的做法。</li>
 * </ol>
 *
 * <p><b>协议端口照样必须提升（"5" → "6"）</b>：这个包本身是纯追加的，旧客户端不发它也不会解析错
 * 任何东西，但反过来不成立——新客户端对着一个<b>没有注册这个包</b>的旧服务端，一拖牌就会发出
 * 对方不认识的 payload id。客户端比服务端「多一个发送能力」同样是两端不一致，
 * 必须靠握手把它挡住，否则得到的是「拖一下就报错」而不是「拖不动」。</p>
 *
 * @param containerId 玩家当前打开的菜单 id（服务端据此校验）
 * @param from        源位置在整副手牌里的下标（0 起，按显示顺序）
 * @param to          目标位置在整副手牌里的下标（0 起）；服务端会把下标夹到合法范围
 */
public record MoveCardPayload(int containerId, int from, int to) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MoveCardPayload> TYPE =
            new CustomPacketPayload.Type<>(Doudizhu.id("card_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MoveCardPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MoveCardPayload::containerId,
            ByteBufCodecs.VAR_INT, MoveCardPayload::from,
            ByteBufCodecs.VAR_INT, MoveCardPayload::to,
            MoveCardPayload::new
    );

    public static void handle(MoveCardPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof DoudizhuMenu menu
                && menu.containerId == payload.containerId()) {
            menu.handleCardMove(payload.from(), payload.to());
        }
    }

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
