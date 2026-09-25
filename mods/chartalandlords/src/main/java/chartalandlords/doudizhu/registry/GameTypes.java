package chartalandlords.doudizhu.registry;

import chartalandlords.doudizhu.Doudizhu;
import chartalandlords.doudizhu.game.DoudizhuGame;
import chartalandlords.doudizhu.game.DoudizhuMenu;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The game type is appended to Charta's synced {@code charta:game_type} registry; the card table
 * screen lists every entry of that registry, so the game shows up next to Crazy Eights/Fun/Solitaire.
 *
 * <p>注册键遵循 Charta 的「命名空间 = 模组、路径 = 牌局」约定（对齐上游的
 * {@code charta:crazy_eights} / {@code charta:solitaire}）。牌桌图标与语言键都由该键派生：
 * {@code chartalandlords:textures/gui/game/doudizhu.png} 与 {@code chartalandlords.doudizhu}。</p>
 */
public final class GameTypes {

    private GameTypes() {}

    /** {@code charta:game_type} 下的牌局路径；同时决定牌桌图标与语言键。 */
    public static final String LANDLORDS_PATH = "doudizhu";

    public static final ResourceKey<Registry<GameType<?, ?>>> CHARTA_GAME_TYPE_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("charta", "game_type"));

    public static final DeferredRegister<GameType<?, ?>> REGISTRY =
            DeferredRegister.create(CHARTA_GAME_TYPE_KEY, Doudizhu.MOD_ID);

    public static final DeferredHolder<GameType<?, ?>, GameType<DoudizhuGame, DoudizhuMenu>> DOUDIZHU =
            REGISTRY.register(LANDLORDS_PATH, () -> DoudizhuGame::new);
}
