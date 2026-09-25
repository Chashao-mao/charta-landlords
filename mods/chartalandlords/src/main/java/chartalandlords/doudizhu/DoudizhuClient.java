package chartalandlords.doudizhu;

import chartalandlords.doudizhu.client.DoudizhuScreen;
import chartalandlords.doudizhu.registry.Menus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@Mod(value = Doudizhu.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Doudizhu.MOD_ID, value = Dist.CLIENT)
public class DoudizhuClient {

    public DoudizhuClient(ModContainer container) {
        // 世界里的局面提示不走额外渲染器：一开始试过在牌桌上方浮一排字，
        // 但一张牌桌只有 1 格宽、一张牌只有 0.16 格，能看清的字一定大得糊住整张桌子。
        // 现在改成「用牌本身说话」——地主面前的底牌、被推到椅子那侧的扇形、往前一步的当前行动方，
        // 都是牌自己的位置信息，不额外画东西。
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(Menus.DOUDIZHU.get(), DoudizhuScreen::new);
    }
}
