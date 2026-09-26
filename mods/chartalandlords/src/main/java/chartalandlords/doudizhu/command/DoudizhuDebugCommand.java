package chartalandlords.doudizhu.command;

import chartalandlords.doudizhu.Doudizhu;
import chartalandlords.doudizhu.game.DoudizhuGame;
import chartalandlords.doudizhu.game.DoudizhuMenu;
import chartalandlords.doudizhu.game.DoudizhuValues;
import chartalandlords.doudizhu.game.engine.DebugHands;
import chartalandlords.doudizhu.game.engine.RuleEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.lucaargolo.charta.common.game.api.card.Card;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 游戏内调试命令 {@code /doudizhu debug ...}：在真牌桌上改写四家手牌 / 地主 / 底牌 / 轮次，
 * 并导出规则引擎的报告，用来验证完整流程（界面、网络同步、AI 决策）。
 *
 * <p>规则判定本身不在这里做——它只是把状态喂给 {@link DoudizhuGame} 的调试接口，
 * 再把 {@link DebugHands} 的报告发出来。离线调试器 {@code tools/debug-hands.ps1} 用的是同一套代码，
 * 所以「离线跑出来的结论」和「游戏内跑出来的结论」不会漂移。</p>
 *
 * <h2>权限</h2>
 * <p>默认要求权限等级 2（单人存档需要开作弊，服务器需要 OP）；也可以在 JVM 参数里加
 * {@code -Dchartalandlords.debug=true} 免权限使用，方便本地反复调试。</p>
 */
public final class DoudizhuDebugCommand {

    /** JVM 参数开关：{@code -Dchartalandlords.debug=true}。 */
    public static final String DEBUG_PROPERTY = "chartalandlords.debug";

    private static final int MAX_SEAT = DoudizhuValues.MAX_PLAYERS - 1;

    private DoudizhuDebugCommand() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("doudizhu")
                .requires(DoudizhuDebugCommand::allowed)
                .then(Commands.literal("debug")
                        .executes(context -> help(context.getSource()))
                        .then(Commands.literal("help")
                                .executes(context -> help(context.getSource())))
                        .then(Commands.literal("show")
                                .executes(context -> show(context.getSource())))
                        .then(Commands.literal("hand")
                                .then(Commands.argument("seat", IntegerArgumentType.integer(0, MAX_SEAT))
                                        .then(Commands.argument("cards", StringArgumentType.greedyString())
                                                .executes(context -> hand(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "seat"),
                                                        StringArgumentType.getString(context, "cards"))))))
                        .then(Commands.literal("bottom")
                                .then(Commands.argument("cards", StringArgumentType.greedyString())
                                        .executes(context -> bottom(context.getSource(),
                                                StringArgumentType.getString(context, "cards")))))
                        .then(Commands.literal("landlord")
                                .then(Commands.argument("seat", IntegerArgumentType.integer(0, MAX_SEAT))
                                        .executes(context -> landlord(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "seat")))))
                        .then(Commands.literal("turn")
                                .then(Commands.argument("seat", IntegerArgumentType.integer(0, MAX_SEAT))
                                        .executes(context -> turn(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "seat")))))
                        .then(Commands.literal("rule")
                                .then(Commands.argument("switch", StringArgumentType.word())
                                        .then(Commands.argument("value", BoolArgumentType.bool())
                                                .executes(context -> rule(context.getSource(),
                                                        StringArgumentType.getString(context, "switch"),
                                                        BoolArgumentType.getBool(context, "value"))))))));
        Doudizhu.LOGGER.info("Doudizhu debug commands registered (/doudizhu debug help)");
    }

    private static boolean allowed(CommandSourceStack source) {
        return source.hasPermission(2) || System.getProperty(DEBUG_PROPERTY) != null;
    }

    // ------------------------------------------------------------------ 子命令

    private static int help(CommandSourceStack source) {
        for (String line : new String[]{
                "/doudizhu debug show — 导出当前牌局报告（手牌 / 地主 / 底牌 / 能出的组合 / 提示 / AI）",
                "/doudizhu debug hand <座位> <牌> — 整手替换某家手牌，例：hand 0 3 3 3 4 5",
                "/doudizhu debug bottom <牌> — 换底牌（还没发给地主时有效）",
                "/doudizhu debug landlord <座位> — 指定地主并直接进入出牌阶段",
                "/doudizhu debug turn <座位> — 强制轮到某一家",
                "/doudizhu debug rule <开关> <true|false> — 开关规则：mixed-joker-rocket /"
                        + " three-with-two / four-with-two / jokers-as-wing-pair / grab-landlord / declare-bonus",
                "牌面写法：3 4 5 6 7 8 9 10 J Q K A 2 加 S（小王）/ B（大王）"}) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int show(CommandSourceStack source) {
        DoudizhuMenu menu = menuOf(source);
        if (menu == null) {
            return 0;
        }
        DoudizhuGame game = menu.getGame();
        String report = game.debugDump();
        Doudizhu.LOGGER.info("Doudizhu debug dump:\n{}", report);
        for (String line : report.split("\n")) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int hand(CommandSourceStack source, int seat, String spec) {
        DoudizhuMenu menu = menuOf(source);
        if (menu == null) {
            return 0;
        }
        DoudizhuGame game = menu.getGame();
        List<Card> cards;
        try {
            cards = cardsFor(menu, spec);
        } catch (IllegalArgumentException exception) {
            return fail(source, exception.getMessage());
        }
        if (seat >= game.getPlayers().size()) {
            return fail(source, "这个座位没有人：座位 " + seat + "（本局只有 " + game.getPlayers().size() + " 家）");
        }
        if (!game.debugSetHand(game.getPlayers().get(seat), cards)) {
            return fail(source, "改牌失败：座位 " + seat + " 不属于本局");
        }
        source.sendSuccess(() -> Component.literal("座位 " + seat + " 的手牌已改为 " + DebugHands.format(
                DoudizhuValues.sortedValues(cards))), false);
        return 1;
    }

    private static int bottom(CommandSourceStack source, String spec) {
        DoudizhuMenu menu = menuOf(source);
        if (menu == null) {
            return 0;
        }
        DoudizhuGame game = menu.getGame();
        List<Card> cards;
        try {
            cards = cardsFor(menu, spec);
        } catch (IllegalArgumentException exception) {
            return fail(source, exception.getMessage());
        }
        if (!game.debugSetBottom(cards)) {
            return fail(source, "底牌已经翻开（发给地主了），改底牌请在叫分阶段之前");
        }
        source.sendSuccess(() -> Component.literal("底牌已改为 " + DebugHands.format(
                DoudizhuValues.sortedValues(cards))), false);
        return 1;
    }

    private static int landlord(CommandSourceStack source, int seat) {
        DoudizhuMenu menu = menuOf(source);
        if (menu == null) {
            return 0;
        }
        DoudizhuGame game = menu.getGame();
        if (!game.debugSetLandlord(seat)) {
            return fail(source, "指定地主失败：座位 " + seat + " 不属于本局");
        }
        source.sendSuccess(() -> Component.literal("座位 " + seat + " 现在是地主，已直接进入出牌阶段"), false);
        return 1;
    }

    private static int turn(CommandSourceStack source, int seat) {
        DoudizhuMenu menu = menuOf(source);
        if (menu == null) {
            return 0;
        }
        DoudizhuGame game = menu.getGame();
        if (!game.debugSetTurn(seat)) {
            return fail(source, "强制轮次失败：要么还没到出牌阶段，要么座位 " + seat + " 不属于本局");
        }
        source.sendSuccess(() -> Component.literal("现在轮到座位 " + seat), false);
        return 1;
    }

    private static int rule(CommandSourceStack source, String name, boolean value) {
        DoudizhuMenu menu = menuOf(source);
        if (menu == null) {
            return 0;
        }
        DoudizhuGame game = menu.getGame();
        if (!game.debugSetRuleSwitch(name, value)) {
            return fail(source, "未知规则开关：" + name);
        }
        source.sendSuccess(() -> Component.literal("规则开关 " + name + " = " + value), false);
        return 1;
    }

    // ------------------------------------------------------------------ 工具

    /** 发命令的玩家必须正坐在一张斗地主牌桌前。 */
    private static DoudizhuMenu menuOf(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            fail(source, "这条命令必须由玩家在牌桌前执行");
            return null;
        }
        if (!(player.containerMenu instanceof DoudizhuMenu menu)) {
            fail(source, "你当前没有坐在斗地主牌桌前（先坐下开一局）");
            return null;
        }
        return menu;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    /**
     * 把 {@code "3 3 4 S B"} 这种写法变成真正的牌。
     *
     * <p>牌不是凭空造的：从这副牌的牌堆里按点数取现成的牌（两副牌最多同点数 8 张），
     * 所以花色、牌面素材都是真的，只有「张数守恒」被打破——这正是调试想要的。</p>
     */
    private static List<Card> cardsFor(DoudizhuMenu menu, String spec) {
        int[] values = DebugHands.parse(spec);
        if (values.length == 0) {
            throw new IllegalArgumentException("没写牌面，例：hand 0 3 3 3 4 5");
        }
        List<Card> pool = new ArrayList<>();
        for (Card card : menu.getDeck().getCards()) {
            pool.add(card);
        }
        int[] used = new int[RuleEngine.RANK_SLOTS];
        List<Card> cards = new ArrayList<>(values.length);
        for (int value : values) {
            Card found = null;
            int seen = 0;
            for (Card card : pool) {
                if (DoudizhuValues.valueOf(card) != value) {
                    continue;
                }
                if (seen++ == used[value]) {
                    found = card;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("这副牌里没有多余的 " + DebugHands.name(value)
                        + "（最多 " + used[value] + " 张）");
            }
            used[value]++;
            cards.add(found.copy());
        }
        return cards;
    }
}
