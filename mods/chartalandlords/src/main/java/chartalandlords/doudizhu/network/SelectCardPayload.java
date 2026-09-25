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
 * C2S：把手牌行里的一张牌挪到出牌选择区，或把选择区里的一张牌放回手牌。
 *
 * @param containerId 玩家当前打开的菜单 id（服务端据此校验）
 * @param slotId      菜单卡槽序号（见 {@link DoudizhuMenu} 的常量）
 * @param cardId      该行内的牌序号（-1 表示未悬停到具体牌）
 */
public record SelectCardPayload(int containerId, int slotId, int cardId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SelectCardPayload> TYPE =
            new CustomPacketPayload.Type<>(Doudizhu.id("card_select"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelectCardPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SelectCardPayload::containerId,
            ByteBufCodecs.VAR_INT, SelectCardPayload::slotId,
            ByteBufCodecs.VAR_INT, SelectCardPayload::cardId,
            SelectCardPayload::new
    );

    public static void handle(SelectCardPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && player.containerMenu instanceof DoudizhuMenu menu
                && menu.containerId == payload.containerId()
                && payload.cardId() >= 0) {
            menu.handleCardClick(payload.slotId(), payload.cardId());
        }
    }

    @Override
    public @NotNull CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
