package chartalandlords.doudizhu.network;

import chartalandlords.doudizhu.Doudizhu;
import chartalandlords.doudizhu.game.DoudizhuAction;
import chartalandlords.doudizhu.game.DoudizhuMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * C2S：出牌 / 不出 / 提示 / 叫分 等按钮动作。
 *
 * <p>{@code actionId} 就是 {@link DoudizhuAction} 的 {@code ordinal()}，它是网络协议的一部分：
 * 增删动作或调整枚举顺序都必须提升 {@code Payloads.PROTOCOL_VERSION}，因此新动作只允许
 * <b>追加</b>在枚举末尾（见 {@link DoudizhuAction} 的注释）。</p>
 */
public record GameActionPayload(int containerId, int actionId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GameActionPayload> TYPE =
            new CustomPacketPayload.Type<>(Doudizhu.id("game_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GameActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GameActionPayload::containerId,
            ByteBufCodecs.VAR_INT, GameActionPayload::actionId,
            GameActionPayload::new
    );

    public static GameActionPayload of(int containerId, DoudizhuAction action) {
        return new GameActionPayload(containerId, action.id());
    }

    public static void handle(GameActionPayload payload, IPayloadContext context) {
        DoudizhuAction action = DoudizhuAction.byId(payload.actionId());
        if (action != null
                && context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof DoudizhuMenu menu
                && menu.containerId == payload.containerId()) {
            menu.handleAction(action);
        }
    }

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
