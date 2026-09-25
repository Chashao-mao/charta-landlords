package chartalandlords.doudizhu.registry;

import chartalandlords.doudizhu.Doudizhu;
import chartalandlords.doudizhu.game.DoudizhuMenu;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class Menus {

    private Menus() {}

    public static final DeferredRegister<MenuType<?>> REGISTRY =
            DeferredRegister.create(Registries.MENU, Doudizhu.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<DoudizhuMenu>> DOUDIZHU =
            REGISTRY.register("doudizhu", () -> {
                IContainerFactory<DoudizhuMenu> factory = (containerId, inventory, data) ->
                        new DoudizhuMenu(containerId, inventory, AbstractCardMenu.Definition.STREAM_CODEC.decode(data));
                return new MenuType<>(factory, FeatureFlags.VANILLA_SET);
            });
}
