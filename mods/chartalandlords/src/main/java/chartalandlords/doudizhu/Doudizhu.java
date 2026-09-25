package chartalandlords.doudizhu;

import chartalandlords.doudizhu.registry.GameTypes;
import chartalandlords.doudizhu.registry.Menus;
import chartalandlords.doudizhu.registry.Payloads;
import chartalandlords.doudizhu.registry.JokerRanks;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the Doudizhu（斗地主） extension for Charta.
 *
 * <p>The mod does not add blocks or items of its own: it registers two extra joker ranks,
 * a new {@code charta:game_type} entry, a menu type, two network payloads and a client screen.
 * The 54/108 card decks are shipped as regular data pack data inside this jar.</p>
 *
 * <h2>命名空间规范 / namespace policy</h2>
 * <p>模组身份分四层，任何一处都不重复写死字面量：</p>
 * <ul>
 *   <li><b>模组名</b>（{@code ChartaLandlords}）：{@code neoforge.mods.toml} 的 {@code displayName}，
 *       玩家在模组列表里看到的名字；产物名是 {@code charta-landlords-<version>.jar}。</li>
 *   <li>{@link #MOD_ID}（{@code chartalandlords}）是<b>资源命名空间</b>：注册表键
 *       （{@code charta:game_type/chartalandlords:doudizhu}、
 *       {@code charta:rank/chartalandlords:small_joker}）、
 *       网络包 id（{@code chartalandlords:game_action}、{@code chartalandlords:card_select}）、
 *       数据包路径（{@code data/chartalandlords/**}）与资源路径（{@code assets/chartalandlords/**}）、
 *       语言键前缀（{@code chartalandlords.*}）。本模组注册的一切都挂在它下面。</li>
 *   <li>{@link #GROUP_ID}（{@code chartalandlords}）是<b>组织命名空间</b>：Maven {@code group}；
 *       Java 包用「组织 + 模块名」= {@code chartalandlords.doudizhu}，不与 mod_id 绑死
 *       （否则会变成 {@code chartalandlords.chartalandlords}）。</li>
 *   <li>牌局路径 {@code doudizhu}（{@link chartalandlords.doudizhu.registry.GameTypes#LANDLORDS_PATH}）
 *       保持拼音原名，与 Charta 的「命名空间 = 模组、路径 = 牌局」一致。</li>
 * </ul>
 */
@Mod(Doudizhu.MOD_ID)
public class Doudizhu {

    /** 资源命名空间；详见类注释的命名空间规范。 */
    public static final String MOD_ID = "chartalandlords";
    /** ChartaLandlords 系列的组织命名空间（Maven group / Java 包根）。 */
    public static final String GROUP_ID = "chartalandlords";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Doudizhu(IEventBus modBus, ModContainer container) {
        JokerRanks.REGISTRY.register(modBus);
        GameTypes.REGISTRY.register(modBus);
        Menus.REGISTRY.register(modBus);
        modBus.addListener(Payloads::register);
        LOGGER.info("Doudizhu loaded: Charta extension with joker ranks, decks and game type '{}'",
                GameTypes.LANDLORDS_PATH);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
