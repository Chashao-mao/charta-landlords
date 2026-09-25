package chartalandlords.doudizhu.test;

import chartalandlords.doudizhu.game.engine.DoudizhuScore;
import chartalandlords.doudizhu.game.engine.AiProfile;
import chartalandlords.doudizhu.game.engine.RuleOptions;
import chartalandlords.doudizhu.game.engine.ComboType;
import chartalandlords.doudizhu.game.engine.Combo;
import chartalandlords.doudizhu.Doudizhu;
import chartalandlords.doudizhu.game.DoudizhuAction;
import chartalandlords.doudizhu.game.DoudizhuGame;
import chartalandlords.doudizhu.game.DoudizhuMenu;
import chartalandlords.doudizhu.game.DoudizhuRules;
import chartalandlords.doudizhu.game.DoudizhuValues;
import com.mojang.authlib.GameProfile;
import chartalandlords.doudizhu.registry.GameTypes;
import chartalandlords.doudizhu.registry.JokerRanks;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.Games;
import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.card.Rank;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import dev.lucaargolo.charta.common.game.api.game.Game;
import dev.lucaargolo.charta.common.game.api.game.GameOption;
import dev.lucaargolo.charta.common.game.impl.AutoPlayer;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import dev.lucaargolo.charta.common.utils.CardPlayerHead;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * 斗地主自动化验收测试：牌型判定、比较规则、牌堆数据、AI 完整对局、超时兜底、选牌/提示流程。
 *
 * <p>运行方式：gradle.ps1 -Project &lt;工程&gt; -Task runGameTestServer</p>
 */
@GameTestHolder(Doudizhu.MOD_ID)
@PrefixGameTestTemplate(false)
public class DoudizhuGameTests {

    /** 单局 tick 上限：超过即视为死锁。 */
    private static final int MAX_GAME_TICKS = 40000;
    private static final int SIMULATED_GAMES = 100;

    // ------------------------------------------------------------------ 牌型判定

    @GameTest(template = "empty")
    public static void classifyCombinations(GameTestHelper helper) {
        expectType(helper, ComboType.SINGLE, "single", c("three"));
        expectType(helper, ComboType.PAIR, "pair", c("three"), c("three"));
        expectType(helper, ComboType.TRIPLE, "triple", c("three"), c("three"), c("three"));
        expectType(helper, ComboType.TRIPLE_SINGLE, "triple + single",
                c("three"), c("three"), c("three"), c("four"));
        expectType(helper, ComboType.TRIPLE_PAIR, "triple + pair",
                c("three"), c("three"), c("three"), c("four"), c("four"));
        expectType(helper, ComboType.STRAIGHT, "straight of five",
                c("three"), c("four"), c("five"), c("six"), c("seven"));
        expectType(helper, ComboType.DOUBLE_STRAIGHT, "double straight of three pairs",
                c("three"), c("three"), c("four"), c("four"), c("five"), c("five"));
        expectType(helper, ComboType.AIRPLANE, "airplane of two triples",
                c("three"), c("three"), c("three"), c("four"), c("four"), c("four"));
        expectType(helper, ComboType.AIRPLANE_SINGLE, "airplane with two singles",
                c("three"), c("three"), c("three"), c("four"), c("four"), c("four"), c("five"), c("seven"));
        expectType(helper, ComboType.AIRPLANE_PAIR, "airplane with two pairs",
                c("three"), c("three"), c("three"), c("four"), c("four"), c("four"),
                c("five"), c("five"), c("seven"), c("seven"));
        expectType(helper, ComboType.FOUR_TWO_SINGLE, "four with two singles",
                c("three"), c("three"), c("three"), c("three"), c("five"), c("seven"));
        expectType(helper, ComboType.FOUR_TWO_PAIR, "four with two pairs",
                c("three"), c("three"), c("three"), c("three"), c("five"), c("five"), c("seven"), c("seven"));
        expectType(helper, ComboType.BOMB, "bomb of four",
                c("three"), c("three"), c("three"), c("three"));
        expectType(helper, ComboType.ROCKET, "rocket of two jokers", smallJoker(), bigJoker());
        expectType(helper, ComboType.ROCKET, "rocket of four jokers",
                true, smallJoker(), smallJoker(), bigJoker(), bigJoker());
        expectType(helper, ComboType.PAIR, "pair of small jokers", smallJoker(), smallJoker());

        expectInvalid(helper, "straight of four", c("three"), c("four"), c("five"), c("six"));
        expectInvalid(helper, "double straight of two pairs",
                c("three"), c("three"), c("four"), c("four"));
        expectInvalid(helper, "straight containing a two",
                c("ten"), c("jack"), c("queen"), c("king"), c("ace"), c("two"));
        expectInvalid(helper, "two unrelated cards", c("three"), c("four"));
        expectInvalid(helper, "four of a kind plus junk",
                c("three"), c("three"), c("three"), c("three"), c("five"));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void compareCombinations(GameTestHelper helper) {
        helper.assertTrue(beats(cards(c("five")), cards(c("four"))), "higher single wins");
        helper.assertFalse(beats(cards(c("four")), cards(c("five"))), "lower single loses");
        helper.assertFalse(beats(cards(c("five")), cards(c("four"), c("four"))), "single cannot beat a pair");
        helper.assertFalse(beats(cards(c("four"), c("four")), cards(c("five"))), "a pair cannot beat a single");
        helper.assertTrue(beats(four("three"), cards(c("king"), c("king"))), "bomb beats a pair");
        helper.assertTrue(beats(four("four"), four("three")), "higher bomb wins");
        helper.assertTrue(beats(cards(smallJoker(), bigJoker()), four("ace")), "rocket beats any bomb");
        helper.assertFalse(beats(four("ace"), cards(smallJoker(), bigJoker())), "nothing beats the rocket");
        helper.assertFalse(beats(cards(c("four"), c("five"), c("six"), c("seven"), c("eight"), c("nine")),
                        cards(c("three"), c("four"), c("five"), c("six"), c("seven"))),
                "straights of different length are not comparable");
        helper.assertTrue(beats(cards(c("five"), c("six"), c("seven"), c("eight"), c("nine")),
                        cards(c("three"), c("four"), c("five"), c("six"), c("seven"))),
                "higher straight wins");
        helper.assertTrue(beats(four("three"),
                        cards(c("king"), c("king"), c("king"), c("four"), c("four"))),
                "bomb beats triple with a pair");
        helper.assertFalse(beats(cards(c("three"), c("three"), c("three"), c("three"), c("four"), c("five")),
                        four("four")),
                "four with two is not a bomb");
        helper.assertTrue(beats4(cards(c("three"), c("three"), c("three"), c("three"), c("three")),
                        four("ace")),
                "a bigger bomb beats a higher ranked bomb");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 牌堆数据

    @GameTest(template = "empty")
    public static void shippedDecksLoad(GameTestHelper helper) {
        Deck single = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        Deck doubleDeck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu_double"));
        helper.assertTrue(single.getCards().size() == 54,
                "doudizhu deck must contain 54 cards but has " + single.getCards().size());
        helper.assertTrue(doubleDeck.getCards().size() == 108,
                "doudizhu_double deck must contain 108 cards but has " + doubleDeck.getCards().size());
        long jokers = single.getCards().stream().filter(DoudizhuValues::isJoker).count();
        helper.assertTrue(jokers == 2, "the single deck must contain two jokers but has " + jokers);
        long bigJokers = doubleDeck.getCards().stream()
                .filter(card -> card.rank() == JokerRanks.BIG_JOKER.get()).count();
        helper.assertTrue(bigJokers == 2, "the double deck must contain two big jokers but has " + bigJokers);

        // 牌局类型必须挂在 charta:game_type 上（牌桌选局列表就是遍历这个注册表）
        var gameTypeKey = Games.getRegistry().getKey(GameTypes.DOUDIZHU.get());
        helper.assertTrue(Doudizhu.id("doudizhu").equals(gameTypeKey),
                "the doudizhu game type must be registered as doudizhu:doudizhu but was " + gameTypeKey);
        helper.assertTrue(Games.getRegistry().size() >= 4,
                "the charta:game_type registry must contain the built in games plus doudizhu");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 完整对局

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void threePlayerGamesFinish(GameTestHelper helper) {
        simulateGames(helper, 3, SIMULATED_GAMES);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void fourPlayerGamesFinish(GameTestHelper helper) {
        simulateGames(helper, 4, SIMULATED_GAMES);
        helper.succeed();
    }

    private static void simulateGames(GameTestHelper helper, int seats, int games) {
        int deckSize = seats == 3 ? 54 : 108;
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id(seats == 3 ? "doudizhu" : "doudizhu_double"));
        helper.assertTrue(deck.getCards().size() == deckSize,
                "the " + deckSize + " card deck must be loaded before simulating");
        for (int game = 0; game < games; game++) {
            List<CardPlayer> players = new ArrayList<>();
            for (int seat = 0; seat < seats; seat++) {
                players.add(new AiSeat("AI-" + seat));
            }
            DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
            doudizhu.startGame();
            int ticks = 0;
            while (!doudizhu.isGameOver() && ticks < MAX_GAME_TICKS) {
                doudizhu.tick();
                ticks++;
            }
            helper.assertTrue(doudizhu.isGameOver(),
                    seats + "p game " + game + " did not finish within " + MAX_GAME_TICKS + " ticks");
            helper.assertTrue(doudizhu.landlordSeat() >= 0, seats + "p game " + game + " has no landlord");
            int hands = 0;
            int emptied = 0;
            for (CardPlayer player : players) {
                int size = doudizhu.getPlayerHand(player).size();
                hands += size;
                if (size == 0) {
                    emptied++;
                }
            }
            helper.assertTrue(emptied == 1,
                    seats + "p game " + game + " must end with exactly one empty hand but had " + emptied);
            int total = hands + doudizhu.getPlayArea().size() + doudizhu.remainingStockSize()
                    + doudizhu.discardedCardCount()
                    + (doudizhu.isBottomRevealed() ? 0 : doudizhu.getBottomSlot().size());
            helper.assertTrue(total == deckSize,
                    seats + "p game " + game + " lost cards: " + total + " of " + deckSize);
        }
    }

    // ------------------------------------------------------------------ 超时兜底

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void bidTimeoutStillWorksButPlayNoLongerSkips(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Silent-" + seat));
        }
        DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
        useClassicBidding(doudizhu);
        doudizhu.startGame();

        int ticks = 0;
        while (doudizhu.phase() != DoudizhuGame.Phase.PLAYING && ticks < 20000) {
            doudizhu.tick();
            ticks++;
        }
        helper.assertTrue(doudizhu.phase() == DoudizhuGame.Phase.PLAYING,
                "the bid timeout must still finish bidding for silent players, phase=" + doudizhu.phase());
        helper.assertTrue(doudizhu.landlordSeat() >= 0, "bidding must still produce a landlord");

        // 出牌阶段不再有超时：沉默的地主无限思考也不会被代打
        for (int i = 0; i < DoudizhuValues.BID_TIMEOUT_TICKS * 3; i++) {
            doudizhu.tick();
        }
        helper.assertTrue(doudizhu.lastPlaySize() == 0,
                "the play phase must not auto-play any more, but a play was registered");
        helper.assertTrue(doudizhu.currentSeat() == doudizhu.landlordSeat(),
                "the turn must stay with the silent landlord instead of being skipped");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void dealingIsRandom(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Deal-" + seat));
        }
        Set<String> signatures = new HashSet<>();
        int[] bigJokerSeats = new int[3];
        int deals = 200;
        for (int deal = 0; deal < deals; deal++) {
            DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
            doudizhu.startGame();
            int ticks = 0;
            while (!doudizhu.isGameReady() && ticks < 5000) {
                doudizhu.tick();
                ticks++;
            }
            helper.assertTrue(doudizhu.isGameReady(), "deal " + deal + " must finish");
            StringBuilder signature = new StringBuilder();
            for (int seat = 0; seat < players.size(); seat++) {
                List<Integer> values = new ArrayList<>();
                doudizhu.getPlayerHand(players.get(seat)).forEach(card -> values.add(DoudizhuValues.valueOf(card)));
                values.sort(Integer::compareTo);
                signature.append(values).append('|');
                if (values.contains(DoudizhuValues.BIG_JOKER_VALUE)) {
                    bigJokerSeats[seat]++;
                }
            }
            List<Integer> bottom = new ArrayList<>();
            doudizhu.getBottomSlot().forEach(card -> bottom.add(DoudizhuValues.valueOf(card)));
            bottom.sort(Integer::compareTo);
            signature.append(bottom);
            signatures.add(signature.toString());
        }
        helper.assertTrue(signatures.size() >= 190,
                "deals must not repeat: only " + signatures.size() + " distinct deals out of " + deals);
        helper.assertTrue(bigJokerSeats[0] < deals && bigJokerSeats[1] < deals && bigJokerSeats[2] < deals,
                "the big joker must not always land in the same seat: " + Arrays.toString(bigJokerSeats));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void redealRestoresFaceState(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Redeal-" + seat));
        }
        DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
        doudizhu.startGame();

        int dealsCompleted = 0;
        boolean wasReady = false;
        int ticks = 0;
        while (dealsCompleted < 2 && ticks < 20000) {
            doudizhu.tick();
            ticks++;
            boolean ready = doudizhu.isGameReady();
            if (ready && !wasReady) {
                dealsCompleted++;
            }
            wasReady = ready;
        }
        helper.assertTrue(dealsCompleted >= 2, "an all-pass round must trigger a redeal");
        for (CardPlayer player : players) {
            doudizhu.getPlayerHand(player).forEach(card -> helper.assertFalse(card.flipped(),
                    "hand cards must be face up after a redeal"));
        }
        doudizhu.getBottomSlot().forEach(card -> helper.assertTrue(card.flipped(),
                "bottom cards must stay face down before the reveal"));
        helper.succeed();
    }

    /**
     * 牌桌<b>全程</b>都摆着各座位的手牌扇形，地主面前还留着一张扣着的「地主牌」。
     *
     * <p>以前第一轮结束就把它们收走，于是牌局进行到一半时桌面上只剩一张上一手牌，看上去像没人打牌。
     * 1.7.0 起扇形不再中途收走；1.8.0 起桌面上的底牌从「摊开整副」改成「一张扣着的牌」——
     * 摊开既会和出牌区抢地方，也看不出那是底牌还是谁打出的牌。</p>
     */
    @GameTest(template = "empty", timeoutTicks = 4800)
    public static void tableKeepsShowingHandsAndBottom(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Table-" + seat));
        }
        DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
        doudizhu.startGame();
        // 打到第一轮结束（有人出过牌、其余人都不出）之后再检查
        int ticks = 0;
        while (!doudizhu.isGameOver() && doudizhu.roundsPlayed() == 0 && ticks < MAX_GAME_TICKS) {
            doudizhu.tick();
            ticks++;
        }
        helper.assertTrue(doudizhu.isTableHandsVisible(),
                "the table must keep drawing seat hands for the whole round");
        for (int seat = 0; seat < players.size(); seat++) {
            CardPlayer player = players.get(seat);
            int shown = doudizhu.getCensoredHand(player).size();
            int hand = doudizhu.getPlayerHand(player).size();
            helper.assertTrue(shown == hand,
                    "seat " + seat + " must keep a fan that matches its hand: " + shown + " vs " + hand);
            if (doudizhu.isRevealed(seat)) {
                doudizhu.getCensoredHand(player).forEach(card -> helper.assertFalse(card.flipped(),
                        "a revealed hand must stay face up on the table"));
            } else {
                doudizhu.getCensoredHand(player).forEach(card -> helper.assertTrue(card.flipped(),
                        "a hand that did not reveal must stay face down on the table"));
            }
        }
        helper.assertTrue(doudizhu.isBottomRevealed(), "the bottom cards must be revealed by now");
        // 桌面上只剩一张扣着的「地主牌」：真正的底牌摊在界面里（预览 + 看牌页），
        // 桌面上摊 3~8 张正面牌既会和出牌区抢地方，也看不出那是底牌还是谁打的牌。
        helper.assertTrue(doudizhu.getBottomSlot().size() == 1,
                "the table must keep exactly one face-down landlord card but has "
                        + doudizhu.getBottomSlot().size());
        doudizhu.getBottomSlot().forEach(card -> helper.assertTrue(card.flipped(),
                "the landlord card on the table must stay face down"));
        helper.assertTrue(doudizhu.getBottomViewSlot().size() == DoudizhuValues.bottomCount(3),
                "the menu bottom preview must keep the whole bottom hand");
        doudizhu.getBottomViewSlot().forEach(card -> helper.assertFalse(card.flipped(),
                "the menu bottom preview must show the bottom cards face up"));
        helper.assertTrue(doudizhu.revealedBottomCards().size() == DoudizhuValues.bottomCount(3),
                "the revealed bottom cards must still be readable (tracker / hands screen)");
        helper.succeed();
    }

    /**
     * 命中判定回归：被压在下面那行的空槽不能抢走点击（曾经导致 3 人局两半点不动、4 人局右半点不动）。
     * 坐标全部由 {@link DoudizhuMenu} 的几何常量推导，不再写死像素。用纯几何断言覆盖，不需要 GUI。
     */
    @GameTest(template = "empty")
    public static void hitTestPrefersTopmostNonEmptySlot(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "DoudizhuHitTest"));
        AbstractCardMenu.Definition definition =
                new AbstractCardMenu.Definition(BlockPos.ZERO, deck, new int[]{-1, -1, -1}, new byte[0]);
        DoudizhuMenu menu = new DoudizhuMenu(0, player.getInventory(), definition);
        int screenHeight = 240;

        // 手牌两行：段 0/1 在前排（视觉上更靠下、画得更晚），段 2/3 在后排
        int frontY = (int) DoudizhuMenu.visualRowScreenY(DoudizhuMenu.VISUAL_ROW_HAND_FRONT, screenHeight);
        int backY = (int) DoudizhuMenu.visualRowScreenY(DoudizhuMenu.VISUAL_ROW_HAND_BACK, screenHeight);
        double insideFrontY = frontY + 6;          // 两行重叠区里，前排一定覆盖到的位置
        double backOnlyY = frontY - 4;             // 只有后排覆盖、前排还没开始的位置
        double leftX = DoudizhuMenu.SEGMENT_LEFT_X + 20;
        double rightX = DoudizhuMenu.SEGMENT_RIGHT_X + 20;
        int frontLeftSlot = DoudizhuMenu.handSlotOfSegment(0);
        int frontRightSlot = DoudizhuMenu.handSlotOfSegment(1);

        // 3 人局农民：段 0/1 = 9/8 张，段 2/3 空 —— 后排整行是空的
        fillHandSegments(menu, 17);
        helper.assertTrue(hitTest(menu, screenHeight, leftX, insideFrontY) == frontLeftSlot,
                "an empty back row must not shadow the left half of the front row");
        helper.assertTrue(hitTest(menu, screenHeight, rightX, insideFrontY) == frontRightSlot,
                "an empty back row must not shadow the right half of the front row");
        helper.assertTrue(hitTest(menu, screenHeight, leftX, backOnlyY) == -1,
                "clicking a band that only shows an empty row must resolve to nothing");

        // 4 人局农民：段 0/1/2 = 9/9/7 张，段 3 空 —— 后排只有右半是空的（旧实现下右半点不动）
        fillHandSegments(menu, 25);
        helper.assertTrue(hitTest(menu, screenHeight, rightX, insideFrontY) == frontRightSlot,
                "the empty fourth segment must not shadow the front row's right half");
        helper.assertTrue(hitTest(menu, screenHeight, leftX, insideFrontY) == frontLeftSlot,
                "the row drawn on top (the front row) must win where two rows overlap");
        helper.assertTrue(backY < frontY,
                "the back hand row must sit above the front row so the front row covers its bottom");
        helper.succeed();
    }

    /** 按「段」而不是槽位序号填手牌：槽位序号与段的对应关系由菜单显式给出（见 handSlotOfSegment）。 */
    private static void fillHandSegments(DoudizhuMenu menu, int cards) {
        for (int segment = 0; segment < DoudizhuMenu.HAND_SEGMENT_COUNT; segment++) {
            int from = segment * DoudizhuValues.SEGMENT_CAPACITY;
            int count = Math.max(0, Math.min(DoudizhuValues.SEGMENT_CAPACITY, cards - from));
            List<Card> part = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                part.add(c("three"));
            }
            menu.getCardSlot(DoudizhuMenu.handSlotOfSegment(segment)).setCards(part);
        }
    }

    private static int hitTest(DoudizhuMenu menu, int screenHeight, double mouseX, double mouseY) {
        return DoudizhuMenu.resolveTopSlotIndex(menu.cardSlots, screenHeight, 0, 0, mouseX, mouseY);
    }

    @GameTest(template = "empty")
    public static void retractSelectionReturnsCards(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Retract-" + seat));
        }
        DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
        useClassicBidding(doudizhu);
        doudizhu.startGame();
        while (!doudizhu.isGameReady()) {
            doudizhu.tick();
        }
        CardPlayer landlord = doudizhu.seatPlayer(doudizhu.currentSeat());
        bidThenAwaitPlaying(doudizhu, landlord, 3);
        helper.assertTrue(doudizhu.phase() == DoudizhuGame.Phase.PLAYING,
                "a 3 point bid must end bidding (and the declare window), phase=" + doudizhu.phase());
        int handBefore = doudizhu.getPlayerHand(landlord).size();

        doudizhu.selectFromHand(landlord, 0, 0);
        doudizhu.selectFromHand(landlord, 0, 0);
        helper.assertTrue(doudizhu.selectionSize(landlord) == 2, "two cards must be selected");
        helper.assertTrue(doudizhu.getPlayerHand(landlord).size() == handBefore - 2,
                "selected cards must leave the hand");

        doudizhu.retractSelection(landlord);
        helper.assertTrue(doudizhu.selectionSize(landlord) == 0, "retracting must empty the play area");
        helper.assertTrue(doudizhu.getPlayerHand(landlord).size() == handBefore,
                "retracting must return every selected card to the hand");

        doudizhu.retractSelection(landlord);
        helper.assertTrue(doudizhu.getPlayerHand(landlord).size() == handBefore,
                "retracting with an empty play area must do nothing");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 选牌 / 提示

    /** 用 FakePlayer 真正构造一次菜单，校验「两行两段」的槽位布局、绘制顺序与底牌槽绑定。 */
    @GameTest(template = "empty")
    public static void menuLayoutMatchesTwoByTwoHand(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "DoudizhuLayout"));
        AbstractCardMenu.Definition definition =
                new AbstractCardMenu.Definition(BlockPos.ZERO, deck, new int[]{-1, -1, -1}, new byte[0]);
        DoudizhuMenu menu = new DoudizhuMenu(0, player.getInventory(), definition);

        helper.assertTrue(menu.cardSlots.size() == DoudizhuMenu.SLOT_COUNT_TOTAL,
                "menu must expose " + DoudizhuMenu.SLOT_COUNT_TOTAL + " card slots but has "
                        + menu.cardSlots.size());
        // 上一手展示带必须装得下最长牌型（4 人局连对十二连 = 24 张），不能静默丢牌
        helper.assertTrue(DoudizhuMenu.PLAY_BAND_CAPACITY >= DoudizhuMenu.MAX_PLAY_SIZE,
                "the last-play band must fit the longest legal play: capacity " + DoudizhuMenu.PLAY_BAND_CAPACITY
                        + " < " + DoudizhuMenu.MAX_PLAY_SIZE);

        List<Float> handRows = new ArrayList<>();
        Set<Integer> handSegments = new HashSet<>();
        for (int segment = 0; segment < DoudizhuMenu.HAND_SEGMENT_COUNT; segment++) {
            int slotId = DoudizhuMenu.handSlotOfSegment(segment);
            CardSlot<DoudizhuGame, DoudizhuMenu> slot = menu.cardSlots.get(slotId);
            helper.assertTrue(slot.getType() == CardSlot.Type.HORIZONTAL, "hand slots must be horizontal rows");
            helper.assertTrue(DoudizhuMenu.handSegmentOfSlot(slotId) == segment,
                    "slot " + slotId + " must map back to hand segment " + segment);
            helper.assertTrue(handSegments.add(segment), "every hand slot must own a distinct segment");
            helper.assertTrue(slot.y == DoudizhuMenu.visualRowY(DoudizhuMenu.handSegmentRow(segment)),
                    "hand slot " + slotId + " must sit on the visual row of its segment");
            if (!handRows.contains(slot.y)) {
                handRows.add(slot.y);
            }
        }
        helper.assertTrue(handSegments.size() == DoudizhuMenu.HAND_SEGMENT_COUNT,
                "the hand must expose all " + DoudizhuMenu.HAND_SEGMENT_COUNT + " segments");
        helper.assertTrue(handRows.size() == DoudizhuValues.HAND_ROWS,
                "the hand must be laid out in exactly " + DoudizhuValues.HAND_ROWS + " rows but used " + handRows.size());

        // 绘制顺序（修复「上面那行盖住下面那行」）：前排段 0/1 必须注册在后——序号更大 = Charta 画得更晚 =
        // 压在后排上，后排因此只被压住底部、露出左上角点数；前排自身在屏幕上更低（y 更大）。
        int frontSlot = DoudizhuMenu.handSlotOfSegment(0);
        int backSlot = DoudizhuMenu.handSlotOfSegment(2);
        helper.assertTrue(frontSlot > backSlot,
                "the front hand row (segments 0/1) must be registered after the back row");
        helper.assertTrue(menu.cardSlots.get(frontSlot).y > menu.cardSlots.get(backSlot).y,
                "the front hand row must sit lower on screen than the back row");

        // 核心不变量：绘制顺序必须与屏幕纵向顺序一致 —— 序号越大 = Charta 画得越晚 = 屏幕位置越低。
        // 这一条同时覆盖了三件事：手牌后排露出左上角点数、上一手不被别的带压住、
        // 出牌区/上一手在 >10 张需要多行时逐行露出左上角点数。
        // 底牌预览槽除外：它是 PREVIEW 类型，框架给它的是「屏幕绝对 y」，不参与纵向排序，
        // 所以单独放在末尾（见 SLOT_BOTTOM_PREVIEW）。
        float previousY = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < DoudizhuMenu.SLOT_COUNT; index++) {
            CardSlot<DoudizhuGame, DoudizhuMenu> slot = menu.cardSlots.get(index);
            helper.assertTrue(slot.y >= previousY,
                    "slot " + index + " (y=" + slot.y + ") is drawn later but sits higher than slot "
                            + (index - 1) + " (y=" + previousY + ")");
            previousY = slot.y;
        }
        // 明牌展示槽是纯数据通道：只给「看牌」界面读别人的手牌，不画在界面上。
        // 于是它摆到屏幕外，并且注册在所有牌带之后（不参与「序号越大越靠下」那条绘制顺序）。
        helper.assertTrue(DoudizhuMenu.SLOT_REVEAL_START
                        == DoudizhuMenu.SLOT_COUNT_TOTAL - DoudizhuMenu.REVEAL_ROW_COUNT,
                "the reveal data slots must be registered after every drawn band");
        for (int seat = 0; seat < DoudizhuMenu.REVEAL_ROW_COUNT; seat++) {
            int slot = DoudizhuMenu.revealSlotOfSeat(seat);
            helper.assertTrue(DoudizhuMenu.isRevealSlot(slot) && DoudizhuMenu.revealSeatOfSlot(slot) == seat,
                    "reveal slot " + slot + " must map back to seat " + seat);
            helper.assertTrue(menu.cardSlots.get(slot).y == DoudizhuMenu.OFF_SCREEN_Y,
                    "seat " + seat + "'s reveal slot must sit off screen");
            helper.assertFalse(DoudizhuMenu.isSelectableSlot(slot),
                    "the reveal data slots must never be clickable");
        }
        helper.assertTrue(menu.cardSlots.get(DoudizhuMenu.SLOT_REVEAL_START).y > menu.cardSlots.get(0).y,
                "the reveal data slots must not be mixed in with the drawn bands");
        // 底牌不再占整行：改成面板顶部的小牌预览，只占 41×18
        helper.assertTrue(menu.cardSlots.size() == DoudizhuMenu.SLOT_COUNT_TOTAL,
                "the menu must expose " + DoudizhuMenu.SLOT_COUNT_TOTAL + " slots (incl. the bottom preview)");
        helper.assertTrue(menu.cardSlots.get(DoudizhuMenu.SLOT_BOTTOM_PREVIEW).getType() == CardSlot.Type.PREVIEW,
                "the bottom cards must be a compact PREVIEW slot, not a full band");
        helper.assertTrue(CardSlot.getHeight(CardSlot.Type.PREVIEW) < DoudizhuMenu.ROW_HEIGHT / 2f,
                "the bottom preview must be much shorter than a card band");
        for (int index = 0; index < DoudizhuMenu.SLOT_COUNT; index++) {
            helper.assertFalse(menu.cardSlots.get(index).getType() == CardSlot.Type.PREVIEW,
                    "slot " + index + " must not be a preview slot: previews are absolutely positioned");
        }
        helper.assertTrue(DoudizhuMenu.revealSlotOfSeat(0) < DoudizhuMenu.revealSlotOfSeat(3),
                "reveal slots must be ordered by seat");
        // 带内同样是「下面的行后画」：最低行 = 带内最后一个槽位
        helper.assertTrue(DoudizhuMenu.playRowOfSlot(DoudizhuMenu.SLOT_PLAY_START) == DoudizhuMenu.PLAY_ROW_COUNT - 1,
                "the play band must register its top row first");
        helper.assertTrue(DoudizhuMenu.playRowOfSlot(
                        DoudizhuMenu.SLOT_PLAY_START + DoudizhuMenu.PLAY_ROW_COUNT - 1) == 0,
                "the play band's lowest row must be registered last");
        helper.assertTrue(
                DoudizhuMenu.selectRowOfSlot(DoudizhuMenu.SLOT_SELECT_START) == DoudizhuMenu.SELECT_ROW_COUNT - 1,
                "the select band must register its top row first");
        helper.assertTrue(DoudizhuMenu.selectRowOfSlot(
                        DoudizhuMenu.SLOT_SELECT_START + DoudizhuMenu.SELECT_ROW_COUNT - 1) == 0,
                "the select band's lowest row must be registered last");
        helper.assertTrue(DoudizhuMenu.handSegmentOfSlot(DoudizhuMenu.SLOT_HAND_START) == 2,
                "the hand must register the back row (segment 2) first");
        helper.assertTrue(DoudizhuMenu.isSelectableSlot(DoudizhuMenu.SLOT_HAND_START), "hand slots are selectable");
        helper.assertTrue(DoudizhuMenu.isSelectableSlot(DoudizhuMenu.SLOT_SELECT_START), "select slots are selectable");
        helper.assertFalse(DoudizhuMenu.isSelectableSlot(DoudizhuMenu.SLOT_BOTTOM_PREVIEW),
                "the bottom preview is not selectable");
        helper.assertFalse(DoudizhuMenu.isSelectableSlot(DoudizhuMenu.SLOT_REVEAL_START),
                "the reveal rows are not selectable");
        helper.assertFalse(DoudizhuMenu.isSelectableSlot(DoudizhuMenu.SLOT_PLAY_START), "the play band is not selectable");
        for (int band = 0; band < DoudizhuMenu.SELECT_ROW_COUNT; band++) {
            int slotId = DoudizhuMenu.SLOT_SELECT_START + band;
            helper.assertTrue(DoudizhuMenu.isSelectSlot(slotId), "slot " + slotId + " must be a select slot");
            helper.assertFalse(DoudizhuMenu.isHandSlot(slotId), "slot " + slotId + " must not be a hand slot");
        }

        DoudizhuGame game = menu.getGame();
        helper.assertTrue(menu.cardSlots.get(DoudizhuMenu.SLOT_BOTTOM_PREVIEW).getSlot() == game.getBottomViewSlot(),
                "the bottom preview must show the menu-only bottom card slot");
        helper.assertTrue(game.getBottomSlot() != game.getBottomViewSlot(),
                "the table bottom slot and the menu bottom slot must be distinct");
        helper.succeed();
    }

    /**
     * 扇形命中判定的往返一致：<b>鼠标点在第 N 张牌上，就必须解析出第 N 张</b>。
     *
     * <p>这一条正是玩家反馈的两个 bug 的共同根因——「点第二行的牌，第一行的牌进了出牌区」与
     * 「点上一手和底牌重叠的位置，拿起了上一手的牌」：命中算出的下标和真正画出来的位置不是同一套
     * 几何。现在绘制（{@link DoudizhuMenu#cardLeftX}）与命中（{@link DoudizhuMenu#resolveCardIndex}）
     * 共用同一支扇形排布，所以这里逐个张数、逐张牌对拍。</p>
     */
    @GameTest(template = "empty")
    public static void fanHitTestRoundTripsEveryCard(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "DoudizhuFan"));
        AbstractCardMenu.Definition definition =
                new AbstractCardMenu.Definition(BlockPos.ZERO, deck, new int[]{-1, -1, -1}, new byte[0]);
        DoudizhuMenu menu = new DoudizhuMenu(0, player.getInventory(), definition);

        int screenHeight = 320;
        int left = DoudizhuMenu.panelLeft(520);
        int top = 0;
        CardSlot<DoudizhuGame, DoudizhuMenu> slot = menu.cardSlots.get(DoudizhuMenu.playSlotOfRow(0));
        for (int count : new int[]{1, 2, 3, 5, 10, 20}) {
            List<Card> cards = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                cards.add(new Card());
            }
            slot.getSlot().setCards(cards);
            float[] bounds = DoudizhuMenu.slotBounds(slot, screenHeight, left, top);
            helper.assertTrue(DoudizhuMenu.resolveCardIndex(slot, screenHeight, left, top,
                            bounds[0] + 1, bounds[1] + bounds[3] / 2f) >= 0,
                    "the whole fan band must resolve to a card (count " + count + ")");
            for (int index = 0; index < count; index++) {
                float x = DoudizhuMenu.cardLeftX(slot, screenHeight, left, top, index) + 3f;
                int hit = DoudizhuMenu.resolveCardIndex(slot, screenHeight, left, top,
                        x, bounds[1] + bounds[3] / 2f);
                helper.assertTrue(hit == index, "with " + count + " cards the mouse on card " + index
                        + " must resolve to " + index + " but resolved to " + hit);
            }
            float lastLeft = DoudizhuMenu.cardLeftX(slot, screenHeight, left, top, count - 1);
            helper.assertTrue(lastLeft + CardSlot.getWidth(CardSlot.Type.DEFAULT) <= bounds[0] + bounds[2] + 0.5f,
                    "the fan must stay inside its band (count " + count + ")");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void selectionHintAndPassFlow(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Flow-" + seat));
        }
        DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
        useClassicBidding(doudizhu);
        doudizhu.startGame();
        while (!doudizhu.isGameReady()) {
            doudizhu.tick();
        }
        CardPlayer landlord = doudizhu.seatPlayer(doudizhu.currentSeat());
        bidThenAwaitPlaying(doudizhu, landlord, 3);
        helper.assertTrue(doudizhu.phase() == DoudizhuGame.Phase.PLAYING,
                "a 3 point bid must end bidding (and the declare window), phase=" + doudizhu.phase());
        helper.assertTrue(doudizhu.landlordSeat() == doudizhu.seatOf(landlord), "the bidder must be the landlord");

        int handBefore = doudizhu.getPlayerHand(landlord).size();
        doudizhu.selectFromHand(landlord, 0, 0);
        helper.assertTrue(doudizhu.getPlayerHand(landlord).size() == handBefore - 1,
                "selecting must remove the card from the hand");
        helper.assertTrue(doudizhu.selectionSize(landlord) == 1, "selecting must fill the play area");

        doudizhu.deselectToHand(landlord, 0, 0);
        helper.assertTrue(doudizhu.selectionSize(landlord) == 0, "deselecting must empty the play area");
        helper.assertTrue(doudizhu.getPlayerHand(landlord).size() == handBefore,
                "deselecting must return the card to the hand");

        doudizhu.selectFromHand(landlord, 0, 0);
        doudizhu.playSelection(landlord);
        helper.assertTrue(doudizhu.lastPlaySize() == 1, "playing a single card must register the play");
        helper.assertTrue(doudizhu.getPlayerHand(landlord).size() == handBefore - 1,
                "the played card must leave the hand");
        helper.assertTrue(doudizhu.selectionSize(landlord) == 0, "playing must clear the play area");

        CardPlayer next = doudizhu.seatPlayer(doudizhu.currentSeat());
        helper.assertTrue(next != landlord, "the turn must move on after a play");
        doudizhu.hint(next);
        if (doudizhu.selectionSize(next) > 0) {
            doudizhu.playSelection(next);
            helper.assertTrue(doudizhu.lastPlaySize() > 0, "the hinted combination must be playable");
        } else {
            doudizhu.passTurn(next);
            helper.assertTrue(doudizhu.selectionSize(next) == 0, "passing must not keep a selection");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 玩法完善：计分 / 选项 / 提示循环

    /** 一局打完必须有零和结算；牌局选项要能真正改到规则；连按提示每次都给合法候选。 */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void scoringOptionsAndHintCycling(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Score-" + seat));
        }
        DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
        doudizhu.startGame();
        int ticks = 0;
        while (!doudizhu.isGameOver() && ticks < MAX_GAME_TICKS) {
            doudizhu.tick();
            ticks++;
        }
        helper.assertTrue(doudizhu.isGameOver(), "the scored game must finish");
        DoudizhuScore.Settlement settlement = doudizhu.settlement();
        helper.assertTrue(settlement != null, "a finished game must produce a settlement");
        helper.assertTrue(settlement.total() == 0,
                "the settlement must be zero-sum but summed to " + settlement.total());
        helper.assertTrue(settlement.multiplier() >= 1, "the multiplier is at least 1");
        int cumulative = 0;
        for (int seat = 0; seat < 3; seat++) {
            cumulative += doudizhu.totalScore(seat);
        }
        helper.assertTrue(cumulative == 0, "cumulative seat scores must stay zero-sum");

        // 牌局选项 → RuleOptions：关掉三带一之后判定必须跟着变
        DoudizhuGame configured = new DoudizhuGame(players, deck);
        helper.assertTrue(configured.getOptions().size() >= 2, "the game must expose rule options");
        helper.assertTrue(configured.ruleOptions().threeWithTwo(), "三带一 is on by default");
        List<Card> tripleWithSingle = cards(c("three"), c("three"), c("three"), c("four"));
        helper.assertTrue(DoudizhuRules.classify(tripleWithSingle, configured.ruleOptions()) != null,
                "三带一 must be legal before the option is turned off");
        ((GameOption.Bool) configured.getOptions().get(0)).set(false);
        helper.assertFalse(configured.ruleOptions().threeWithTwo(),
                "turning the option off must reach RuleOptions");
        helper.assertTrue(DoudizhuRules.classify(tripleWithSingle, configured.ruleOptions()) == null,
                "三带一 must be rejected once the option is off");
        ((GameOption.Bool) configured.getOptions().get(0)).set(true);
        helper.assertTrue(DoudizhuRules.classify(tripleWithSingle, configured.ruleOptions()) != null,
                "turning the option back on must restore 三带一");

        // 提示循环：连按两次都必须给出一手合法且在手里的牌，且不丢牌
        DoudizhuGame game = new DoudizhuGame(players, deck);
        useClassicBidding(game);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        CardPlayer landlord = game.seatPlayer(game.currentSeat());
        bidThenAwaitPlaying(game, landlord, 3);
        CardPlayer actor = landlord;
        int handBefore = game.getPlayerHand(actor).size();
        game.hint(actor);
        helper.assertTrue(game.selectionSize(actor) > 0, "the first hint must select something");
        List<Card> firstChoice = selectionOf(game, actor);
        helper.assertTrue(DoudizhuRules.classify(firstChoice, game.ruleOptions()) != null,
                "the first hinted combination must be legal");
        game.hint(actor);
        helper.assertTrue(game.selectionSize(actor) > 0, "the second hint must still select something");
        List<Card> secondChoice = selectionOf(game, actor);
        helper.assertTrue(DoudizhuRules.classify(secondChoice, game.ruleOptions()) != null,
                "the second hinted combination must be legal");
        helper.assertTrue(game.getPlayerHand(actor).size() + game.selectionSize(actor) == handBefore,
                "hinting must never lose or duplicate cards");
        // 手动改过选牌之后，提示要回到第一个候选
        game.retractSelection(actor);
        game.hint(actor);
        helper.assertTrue(selectionOf(game, actor).size() == firstChoice.size(),
                "after a manual change the hint must restart from the first candidate");
        helper.succeed();
    }

    private static List<Card> selectionOf(DoudizhuGame game, CardPlayer player) {
        List<Card> cards = new ArrayList<>();
        game.getSelection(player).forEach(cards::add);
        return cards;
    }

    /**
     * 记牌器守恒：对每个座位、整局反复校验
     * {@code sum(未露面) + 这个座位看得见的牌 == 整副牌}。
     *
     * <p>这是截图里唯一没法肉眼看出来的量：手牌、选牌区、桌面、弃牌堆、已翻开底牌分别算不算「已见」，
     * 任何一项漏掉都会让记牌器的数字和桌面对不上。</p>
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void cardCounterStaysConsistent(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Counter-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        game.startGame();
        int rounds = 0;
        for (int tick = 0; tick < MAX_GAME_TICKS && !game.isGameOver(); tick++) {
            game.tick();
            if (tick % 40 == 0) {
                assertCounterConservation(helper, game, players, deck.getCards().size(), rounds++);
            }
        }
        helper.assertTrue(rounds > 0, "the card counter must have been sampled at least once");
        assertCounterConservation(helper, game, players, deck.getCards().size(), rounds);
        helper.succeed();
    }

    private static void assertCounterConservation(GameTestHelper helper, DoudizhuGame game,
                                                  List<CardPlayer> players, int deckSize, int round) {
        boolean revealed = game.isBottomRevealed();
        int bottom = DoudizhuValues.bottomCount(players.size());
        // 先把「全场可见牌」的点数直方图查一遍：任何点数超过整副牌的数量，说明有牌被重复计数了。
        // 注意桌面与弃牌堆是全场共享的，只能加一次。
        int[] global = new int[DoudizhuValues.MAX_VALUE + 1];
        for (CardPlayer player : players) {
            tally(global, game.getPlayerHand(player).getCards());
            tally(global, game.getSelection(player).getCards());
        }
        tally(global, game.getPlayArea().getCards());
        tally(global, game.getDiscardSlot().getCards());
        for (int value = DoudizhuValues.MIN_VALUE; value <= DoudizhuValues.MAX_VALUE; value++) {
            int perDeck = value >= DoudizhuValues.SMALL_JOKER_VALUE ? 1 : players.size() == 4 ? 8 : 4;
            if (global[value] > perDeck) {
                helper.assertTrue(false, "round " + round + " rank " + value + " appears " + global[value]
                        + " times but the deck only has " + perDeck + " | global="
                        + java.util.Arrays.toString(global) + " | bottom="
                        + java.util.Arrays.toString(game.revealedBottomValues()) + " | hands="
                        + game.getPlayerHand(players.get(0)).size() + "/"
                        + game.getPlayerHand(players.get(1)).size() + "/"
                        + game.getPlayerHand(players.get(2)).size()
                        + " play=" + game.getPlayArea().size()
                        + " discard=" + game.discardedCardCount());
            }
        }
        for (int seat = 0; seat < players.size(); seat++) {
            CardPlayer player = players.get(seat);
            int[] unseen = game.unseenCounts(seat);
            int[] expected = expectedUnseen(game, player, seat, players);
            for (int value = DoudizhuValues.MIN_VALUE; value <= DoudizhuValues.MAX_VALUE; value++) {
                helper.assertTrue(unseen[value] >= 0,
                        "round " + round + " seat " + seat + " rank " + value + " went negative");
                if (unseen[value] != expected[value]) {
                    helper.assertTrue(false, "round " + round + " seat " + seat + " rank " + value
                            + ": counter says " + unseen[value] + " but expected " + expected[value]
                            + " | counter=" + java.util.Arrays.toString(unseen)
                            + " expected=" + java.util.Arrays.toString(expected)
                            + " | " + counterDetail(game, player, players, unseen, game.isBottomRevealed(),
                            DoudizhuValues.bottomCount(players.size()), deckSize));
                }
            }
        }
    }

    /**
     * 独立算一遍记牌器应有的结果：整副牌 − 自己看得见的一切。
     *
     * <p>「看得见」= 自己的手牌 + 选牌区 + 桌面 + 弃牌堆 +（非地主时）<b>还捏在地主手里的底牌</b>
     * + <b>别人明牌的手牌</b>。底牌只按对象身份回溯，已经打出去的不算——那时它已经通过桌面/弃牌堆
     * 计过一次了。明牌那一项必须和 {@code DoudizhuGame.unseenCounts} 保持一致，否则 AI 一旦亮牌，
     * 这条守恒断言就会红。</p>
     */
    private static int[] expectedUnseen(DoudizhuGame game, CardPlayer viewer, int seat, List<CardPlayer> players) {
        int[] expected = new int[DoudizhuValues.MAX_VALUE + 1];
        int perRank = players.size() == 4 ? 8 : 4;
        for (int value = DoudizhuValues.MIN_VALUE; value <= DoudizhuValues.MAX_VALUE; value++) {
            expected[value] = value >= DoudizhuValues.SMALL_JOKER_VALUE ? (players.size() == 4 ? 2 : 1) : perRank;
        }
        subtract(expected, game.getPlayerHand(viewer).getCards());
        subtract(expected, game.getSelection(viewer).getCards());
        subtract(expected, game.getPlayArea().getCards());
        subtract(expected, game.getDiscardSlot().getCards());
        for (int other = 0; other < players.size(); other++) {
            if (other != seat && game.isRevealed(other)) {
                subtract(expected, game.getPlayerHand(players.get(other)).getCards());
            }
        }
        // 地主自己没明牌时，底牌是额外的公开信息；地主明牌了整手牌上面已经扣过，不能扣第二遍
        boolean landlordRevealed = game.landlordSeat() >= 0 && game.landlordSeat() < players.size()
                && game.isRevealed(game.landlordSeat());
        if (game.isBottomRevealed() && !landlordRevealed && seat != game.landlordSeat() && game.landlordSeat() >= 0) {
            CardPlayer landlord = players.get(game.landlordSeat());
            for (Card card : game.revealedBottomCards()) {
                if (containsIdentity(game.getPlayerHand(landlord).getCards(), card)) {
                    int value = DoudizhuValues.valueOf(card);
                    if (value >= 0 && value < expected.length && expected[value] > 0) {
                        expected[value]--;
                    }
                }
            }
        }
        return expected;
    }

    private static void subtract(int[] counts, Iterable<Card> cards) {
        for (Card card : cards) {
            int value = DoudizhuValues.valueOf(card);
            if (value >= 0 && value < counts.length && counts[value] > 0) {
                counts[value]--;
            }
        }
    }

    private static boolean containsIdentity(Iterable<Card> cards, Card card) {
        for (Card candidate : cards) {
            if (candidate == card) {
                return true;
            }
        }
        return false;
    }

    /** 某个座位手牌的点数列表（调试用）。 */
    private static String handRanks(DoudizhuGame game, int seat) {
        CardPlayer player = game.seatPlayer(seat);
        if (player == null) {
            return "[]";
        }
        List<Integer> values = new ArrayList<>();
        game.getPlayerHand(player).forEach(card -> values.add(DoudizhuValues.valueOf(card)));
        values.sort(Integer::compareTo);
        return values.toString();
    }

    /** 全场可见牌的点数直方图（手牌 + 选牌区 + 桌面 + 弃牌堆，各算一次）。 */
    private static int[] globalHistogram(DoudizhuGame game, List<CardPlayer> players) {
        int[] global = new int[DoudizhuValues.MAX_VALUE + 1];
        for (CardPlayer player : players) {
            tally(global, game.getPlayerHand(player).getCards());
            tally(global, game.getSelection(player).getCards());
        }
        tally(global, game.getPlayArea().getCards());
        tally(global, game.getDiscardSlot().getCards());
        return global;
    }

    /** 把一组牌按点数累加进直方图（忽略非法点数）。 */
    private static void tally(int[] counts, Iterable<Card> cards) {
        for (Card card : cards) {
            int value = DoudizhuValues.valueOf(card);
            if (value >= DoudizhuValues.MIN_VALUE && value <= DoudizhuValues.MAX_VALUE) {
                counts[value]++;
            }
        }
    }

    /** 记牌器对不上时，把每个点数的「应有 / 记账」两侧列出来。 */
    private static String counterDetail(DoudizhuGame game, CardPlayer player, List<CardPlayer> players, int[] unseen,
                                        boolean revealed, int bottom, int deckSize) {
        int[] seen = new int[DoudizhuValues.MAX_VALUE + 1];
        StringBuilder outliers = new StringBuilder();
        for (Card card : cardsOf(game, player)) {
            int value = DoudizhuValues.valueOf(card);
            if (value < DoudizhuValues.MIN_VALUE || value > DoudizhuValues.MAX_VALUE) {
                outliers.append(' ').append(card.rank()).append("->").append(value);
            } else {
                seen[value]++;
            }
        }
        StringBuilder detail = new StringBuilder("revealed=").append(revealed)
                .append(" marks=");
        for (int seat = 0; seat < players.size(); seat++) {
            detail.append(game.seatMarks(seat)).append(seat + 1 < players.size() ? "/" : "");
        }
        detail.append(" hand=").append(game.getPlayerHand(player).size())
                .append(" sel=").append(game.getSelection(player).size())
                .append(" play=").append(game.getPlayArea().size())
                .append(" discard=").append(game.discardedCardCount())
                .append(" landlord=").append(game.landlordSeat())
                .append(" seat=").append(game.seatOf(player))
                .append(" bottom=").append(java.util.Arrays.toString(game.revealedBottomValues()))
                .append(" landlordHand=").append(handRanks(game, game.landlordSeat()))
                .append(" global=").append(java.util.Arrays.toString(globalHistogram(game, players)));
        for (int value = DoudizhuValues.MIN_VALUE; value <= DoudizhuValues.MAX_VALUE; value++) {
            detail.append(" [").append(value).append(':').append(unseen[value]).append('/').append(seen[value])
                    .append(']');
        }
        detail.append(" outliers=[").append(outliers).append(']');
        return detail.toString();
    }

    /** 手牌 + 选牌区 + 桌面 + 弃牌堆里的所有牌。 */
    private static List<Card> cardsOf(DoudizhuGame game, CardPlayer player) {
        List<Card> cards = new ArrayList<>();
        game.getPlayerHand(player).forEach(cards::add);
        game.getSelection(player).forEach(cards::add);
        game.getPlayArea().forEach(cards::add);
        game.getDiscardSlot().forEach(cards::add);
        return cards;
    }

    /**
     * 整局回放不变量：任何**真正落桌**的出牌都必须严格压过上一手，且本身是合法牌型。
     *
     * <p>玩家反馈「AI 用 10 压 10」。纯规则层与 AI 策略层已经由离线 harness 穷举证明不会产生同点数候选
     * （见 {@code PlayHarnessMain} 第 4 节），所以这一条盯的是<b>集成层</b>：框架投递、上一手状态、
     * 落桌路径有没有可能让一手非法牌真的摆到桌上。3 人局与 4 人局各跑 10 整局，逐手校验合法性。</p>
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void everyCommittedPlayBeatsTheLast(GameTestHelper helper) {
        int committed = 0;
        int followUps = 0;
        for (int seats : new int[]{3, 4}) {
            Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id(seats == 3 ? "doudizhu" : "doudizhu_double"));
            boolean fourPlayer = seats == 4;
            for (int game = 0; game < 10; game++) {
                List<CardPlayer> players = new ArrayList<>();
                for (int seat = 0; seat < seats; seat++) {
                    players.add(new AiSeat("Legal-" + seats + "-" + seat));
                }
                DoudizhuGame doudizhu = new DoudizhuGame(players, deck);
                doudizhu.startGame();
                Combo previous = null;
                int lastSignature = 0;
                int ticks = 0;
                while (!doudizhu.isGameOver() && ticks < MAX_GAME_TICKS) {
                    doudizhu.tick();
                    ticks++;
                    List<Card> now = new ArrayList<>();
                    doudizhu.getPlayArea().forEach(now::add);
                    int signature = now.isEmpty() ? 0
                            : now.size() * 31 + System.identityHashCode(now.get(now.size() - 1));
                    if (signature == lastSignature) {
                        continue;
                    }
                    lastSignature = signature;
                    if (now.isEmpty()) {
                        // 其余人都不出，桌面清空：下一手是首出
                        previous = null;
                        continue;
                    }
                    Combo combo = DoudizhuRules.classify(now, fourPlayer);
                    helper.assertTrue(combo != null,
                            seats + "p game " + game + ": a committed play must be a legal combination, got "
                                    + now.size() + " cards");
                    if (previous != null) {
                        followUps++;
                        helper.assertTrue(DoudizhuRules.beats(combo, previous),
                                seats + "p game " + game + ": committed play " + combo
                                        + " does NOT beat the previous " + previous
                                        + " (this is the reported '10 over 10' class of bug)");
                    }
                    committed++;
                    previous = combo;
                }
                helper.assertTrue(doudizhu.isGameOver(), seats + "p game " + game + " did not finish");
            }
        }
        helper.assertTrue(committed > 200, "the replay must cover many plays, saw " + committed);
        helper.assertTrue(followUps > 100, "the replay must compare many follow-ups, saw " + followUps);
        helper.succeed();
    }

    /**
     * 计分板必须跨局存活。
     *
     * <p>玩家反馈「头像右下角的分数一直是 0」。原因是 Charta 每开一局都会
     * {@code type.create(...)} 新建一个 Game 实例（{@code CardTableBlockEntity.startGame}），
     * 而 {@code getOrderedPlayers()} 每次还会 shuffle 决定起始座位，所以分数既不能放实例字段、
     * 也不能按座位号存。这条测试用**两个全新实例 + 同一批座位**验证分数确实接得上。</p>
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void scoreboardSurvivesNewGameInstance(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Persist-" + seat));
        }

        DoudizhuGame first = new DoudizhuGame(players, deck);
        first.startGame();
        playToEnd(first);
        helper.assertTrue(first.isGameOver(), "the first round must finish");
        int[] afterFirst = totals(first, 3);
        helper.assertTrue(afterFirst[0] + afterFirst[1] + afterFirst[2] == 0,
                "a single settlement must be zero-sum");
        helper.assertTrue(afterFirst[0] != 0 || afterFirst[1] != 0 || afterFirst[2] != 0,
                "the first round must actually move the scoreboard");
        helper.assertTrue(first.roundsPlayed() >= 1, "the table must count the finished round");

        // 全新实例、同一批座位：分数必须已经在那儿
        DoudizhuGame second = new DoudizhuGame(players, deck);
        second.startGame();
        helper.assertTrue(Arrays.equals(totals(second, 3), afterFirst),
                "the scoreboard must survive a brand new Game instance: was "
                        + Arrays.toString(afterFirst) + " now " + Arrays.toString(totals(second, 3)));

        // 再打一局，分数在上一局基础上继续累加
        playToEnd(second);
        DoudizhuScore.Settlement settlement = second.settlement();
        helper.assertTrue(settlement != null, "the second round must settle too");
        int[] afterSecond = totals(second, 3);
        for (int seat = 0; seat < 3; seat++) {
            int expected = afterFirst[seat] + settlement.delta(seat);
            helper.assertTrue(afterSecond[seat] == expected,
                    "seat " + seat + " must accumulate across rounds: expected " + expected
                            + " but had " + afterSecond[seat]);
        }
        helper.assertTrue(second.roundsPlayed() >= 2, "two rounds must be counted, saw "
                + second.roundsPlayed());
        helper.succeed();
    }

    // ------------------------------------------------------------------ 抢地主 / 明牌 / 加倍 / AI 档位

    /** 这些用例测的是叫分语义，而 1.4.0 起抢地主是默认模式，所以显式关掉它。 */
    private static void useClassicBidding(DoudizhuGame game) {
        game.grabLandlordOption().set(false);
    }

    /**
     * 默认就该是抢地主模式：直接开一局，走完抢地主 + 明牌 / 加倍，必须能正常定出地主并打完。
     *
     * <p>这条用例专门盯「默认值」本身：选项默认值是构建时写死的，改错了别的用例不一定抓得到。</p>
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void grabLandlordIsTheDefaultMode(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Default-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        helper.assertTrue(game.isGrabMode(), "grab-the-landlord must be on by default");
        helper.assertTrue(game.grabLandlordOption().get(), "the option itself must default to true");
        helper.assertTrue(game.highestBid() == 0, "no bid has happened before the game starts");

        game.startGame();
        playToEnd(game);
        helper.assertTrue(game.isGameOver(), "a default game must play to the end");
        helper.assertTrue(game.landlordSeat() >= 0, "a default game must still produce a landlord");
        helper.assertTrue(game.highestBid() == 1,
                "grab mode fixes the base bid at 1, got " + game.highestBid());
        helper.assertTrue(game.grabCount() >= 1,
                "a default game that reached the play phase must have had at least one grab");
        DoudizhuScore.Settlement settlement = game.settlement();
        helper.assertTrue(settlement != null && settlement.total() == 0,
                "a default grab game must settle to zero");
        helper.succeed();
    }

    /**
     * 抢地主模式：一圈之内每人各自「抢 / 不抢」，最后一个抢的人当地主，抢的次数越高倍数越高。
     *
     * <p>同时校验「第一个抢的人不翻倍」——抢 3 次只算 2 次翻倍，倍数从 ×4 起步。</p>
     */
    @GameTest(template = "empty")
    public static void grabLandlordModeDecidesLandlordAndMultiplier(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Grab-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        game.grabLandlordOption().set(true);
        helper.assertTrue(game.isGrabMode(), "the grab option must reach the game");
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.BIDDING,
                "grab mode still starts in the bidding phase, got " + game.phase());

        List<Integer> order = new ArrayList<>();
        int decisions = 0;
        while (game.phase() == DoudizhuGame.Phase.BIDDING && decisions <= players.size()) {
            int seat = game.currentSeat();
            order.add(seat);
            game.bid(game.seatPlayer(seat), 1);
            decisions++;
        }
        helper.assertTrue(decisions == players.size(),
                "every seat must decide exactly once, got " + decisions);
        helper.assertTrue(game.grabCount() == players.size(), "every grab must be counted");
        helper.assertTrue(game.highestBid() == 1, "the base bid must stay 1 in grab mode, got " + game.highestBid());
        helper.assertTrue(game.landlordSeat() == order.get(order.size() - 1),
                "the last grabber must be the landlord: seat " + order.get(order.size() - 1)
                        + " but got " + game.landlordSeat());
        helper.assertTrue(game.grabDoublings() == players.size() - 1,
                "n grabs must count n-1 extra doublings, got " + game.grabDoublings());

        playToEnd(game);
        helper.assertTrue(game.isGameOver(), "a grab-mode game must finish");
        DoudizhuScore.Settlement settlement = game.settlement();
        helper.assertTrue(settlement != null, "a finished grab game must settle");
        helper.assertTrue(settlement.multiplier() % (1 << (players.size() - 1)) == 0,
                "three grabs must keep the table multiplier a multiple of x4, got x" + settlement.multiplier());
        helper.assertTrue(settlement.total() == 0, "grab settlement must stay zero-sum");
        helper.succeed();
    }

    /** 抢地主模式下一个都不抢：跟叫分模式全不叫一样，重新发牌或强制指定地主，绝不卡死。 */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void grabLandlordModeSurvivesNobodyGrabbing(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("NoGrab-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        game.grabLandlordOption().set(true);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        int decisions = 0;
        while (game.phase() == DoudizhuGame.Phase.BIDDING && decisions <= players.size()) {
            // 记录座位：重新发牌会把 currentSeat 换掉，所以每一轮都要重新取
            game.bid(game.seatPlayer(game.currentSeat()), 0);
            decisions++;
        }
        helper.assertTrue(game.grabCount() == 0, "nobody grabbed, so no grab may be counted");
        helper.assertTrue(game.grabDoublings() == 0, "no grabs means no extra doublings");
        // 全不叫会重新发牌；最多 MAX_BID_ROUNDS 轮之后必须强制指定地主并进入明牌/出牌阶段
        int ticks = 0;
        while (game.phase() == DoudizhuGame.Phase.BIDDING && ticks < MAX_GAME_TICKS) {
            game.tick();
            ticks++;
        }
        helper.assertTrue(game.phase() != DoudizhuGame.Phase.BIDDING,
                "the game must leave bidding even when nobody ever grabs, got " + game.phase());
        helper.assertTrue(game.landlordSeat() >= 0, "a landlord must still be forced");
        helper.succeed();
    }

    /**
     * 明牌 / 加倍阶段：每人一次机会，明牌公开手牌，两者都是「个人倍数 ×2」。
     *
     * <p>关键断言是「第二个动作被忽略」——重复发送不能让同一个人既明牌又加倍，
     * 否则个人倍数会凭空变成 ×4。</p>
     */
    @GameTest(template = "empty")
    public static void declarePhaseAppliesPerSeatMarks(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Declare-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        helper.assertTrue(game.isDeclareEnabled(), "the declare phase must be on by default");
        useClassicBidding(game);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        CardPlayer landlord = game.seatPlayer(game.currentSeat());
        game.bid(landlord, 3);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.DECLARING,
                "bidding must hand over to the declare phase, got " + game.phase());

        int unseenBeforeReveal = sum(game.unseenCounts(1));
        helper.assertTrue(java.util.Arrays.equals(game.unseenCounts(1), expectedUnseen(game, players.get(1), 1, players)),
                "the counter must be consistent before the reveal too");
        game.declare(players.get(0), DoudizhuAction.REVEAL_HAND);
        // 明牌是公开信息：从别的座位看，明牌者的整手牌必须立刻从「未露面」里扣掉，
        // 否则记牌器会虚报，AI 的「这手牌外面压不动」判断也会偏乐观。
        // 同时绝不能重复扣：座位 0 是地主时，它的手牌里含已经公开过的 3 张底牌，那几张只算一次。
        int unseenAfterReveal = sum(game.unseenCounts(1));
        helper.assertTrue(java.util.Arrays.equals(game.unseenCounts(1), expectedUnseen(game, players.get(1), 1, players)),
                "revealing must make the whole hand count as seen by others, each card exactly once");
        int expectedDrop = game.handSizeAt(0)
                - (game.landlordSeat() == 0 ? DoudizhuValues.bottomCount(players.size()) : 0);
        helper.assertTrue(unseenBeforeReveal - unseenAfterReveal == expectedDrop,
                "revealing seat 0 must remove exactly its not-yet-public cards from the unseen pool: "
                        + unseenBeforeReveal + " -> " + unseenAfterReveal + " (expected a drop of "
                        + expectedDrop + ", landlord=" + game.landlordSeat() + ")");
        // 座位 0：明牌 + 加倍<b>同时</b>打开 → 个人倍数 ×4，而且明牌不可撤销
        game.declare(players.get(0), DoudizhuAction.DOUBLE_UP);
        game.declare(players.get(0), DoudizhuAction.REVEAL_HAND);
        game.declare(players.get(0), DoudizhuAction.REVEAL_HAND);
        helper.assertTrue(game.isRevealed(0), "seat 0 must be revealed");
        helper.assertTrue(game.isDoubled(0), "seat 0 must be doubled as well - the two switches stack");
        helper.assertTrue(game.personalFactor(0) == 4 && game.personalDoublings(0) == 2,
                "reveal + double must be a personal stake of x4, got x" + game.personalFactor(0));
        helper.assertFalse(game.canReveal(0), "revealing cannot be undone, so the switch must be spent");
        helper.assertTrue(game.canDeclare(0), "seat 0 has not confirmed yet");

        // 座位 1：加倍是可以反复切换的开关
        game.declare(players.get(1), DoudizhuAction.DOUBLE_UP);
        helper.assertTrue(game.isDoubled(1), "seat 1 must be doubled");
        helper.assertFalse(game.isRevealed(1), "doubling must not reveal the hand");
        game.declare(players.get(1), DoudizhuAction.DOUBLE_UP);
        helper.assertFalse(game.isDoubled(1), "pressing double again must switch it back off");
        helper.assertTrue(game.personalFactor(1) == 1, "seat 1 is back to x1");
        game.declare(players.get(1), DoudizhuAction.DOUBLE_UP);
        helper.assertTrue(game.isDoubled(1) && game.personalFactor(1) == 2, "seat 1 doubles again");
        helper.assertTrue((game.seatMarks(2) & 4) == 0,
                "seat 2 must not be marked as decided yet, got " + game.seatMarks(2));

        // 确认：三个座位都确认之后进入出牌阶段
        game.declare(players.get(0), DoudizhuAction.DECLARE_PASS);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.DECLARING,
                "one seat confirming must not start the play phase while others are still deciding");
        helper.assertFalse(game.canDeclare(0), "a confirmed seat must not be able to keep toggling");
        game.declare(players.get(0), DoudizhuAction.REVEAL_HAND);
        game.declare(players.get(0), DoudizhuAction.DOUBLE_UP);
        game.declare(players.get(1), DoudizhuAction.DECLARE_PASS);
        game.declare(players.get(2), DoudizhuAction.DECLARE_PASS);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.PLAYING,
                "once everyone confirmed the play phase must start, got " + game.phase());
        // 只看明牌 / 加倍那三位（bit0/1/2）；bit3 是「抢过」、bit4 是「叫分阶段表过态」，
        // 它们属于另一个阶段，这里用掩码隔开，免得耦合。
        helper.assertTrue((game.seatMarks(0) & 7) == 7,
                "seat 0 marks = revealed + doubled + confirmed, got " + (game.seatMarks(0) & 7));
        helper.assertTrue((game.seatMarks(1) & 7) == 6,
                "seat 1 marks = doubled + confirmed, got " + (game.seatMarks(1) & 7));
        helper.assertTrue((game.seatMarks(2) & 7) == 4,
                "seat 2 marks = confirmed only, got " + (game.seatMarks(2) & 7));

        // 明牌的手牌对所有人可见：共享的「看不见的手牌」槽里必须是正面牌，且点数与真手牌一一对应
        List<Card> real = new ArrayList<>();
        game.getPlayerHand(players.get(0)).forEach(real::add);
        List<Card> shown = new ArrayList<>();
        game.getCensoredHand(players.get(0)).forEach(shown::add);
        helper.assertTrue(shown.size() == real.size(),
                "a revealed hand must mirror the real size: " + shown.size() + " vs " + real.size());
        for (int index = 0; index < shown.size(); index++) {
            helper.assertFalse(shown.get(index).flipped(),
                    "revealed card " + index + " must be face up");
            helper.assertTrue(DoudizhuValues.valueOf(shown.get(index)) == DoudizhuValues.valueOf(real.get(index)),
                    "revealed card " + index + " must match the real hand");
        }
        // 没有明牌的人仍然只有背面
        List<Card> hidden = new ArrayList<>();
        game.getCensoredHand(players.get(1)).forEach(hidden::add);
        helper.assertTrue(hidden.size() == game.handSizeAt(1), "a hidden hand must still mirror the size");
        for (Card card : hidden) {
            helper.assertTrue(card.flipped(), "a hand that did not reveal must stay face down");
        }
        helper.succeed();
    }

    /** 明牌 / 加倍阶段不会无限等：窗口走完，没有确认的人自动按「当前开关状态」结束。 */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void declareWindowTimesOutForSilentPlayers(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("SilentDeclare-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        useClassicBidding(game);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        game.bid(game.seatPlayer(game.currentSeat()), 3);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.DECLARING, "the declare window must open");

        int window = DoudizhuGame.declareWindowTicks();
        int ticks = 0;
        while (game.phase() == DoudizhuGame.Phase.DECLARING && ticks < window * 3) {
            game.tick();
            ticks++;
        }
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.PLAYING,
                "the declare window must time out into the play phase, got " + game.phase());
        helper.assertTrue(ticks <= window + 2,
                "the window must last about " + window + " ticks, took " + ticks);
        for (int seat = 0; seat < 3; seat++) {
            helper.assertFalse(game.isRevealed(seat), "seat " + seat + " never revealed");
            helper.assertFalse(game.isDoubled(seat), "seat " + seat + " never doubled");
        }
        helper.succeed();
    }

    /** 关掉「明牌 / 加倍」选项后，叫分必须直接进到出牌阶段，不再有中间阶段。 */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void declarePhaseCanBeTurnedOff(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("NoDeclare-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        game.declareBonusOption().set(false);
        useClassicBidding(game);
        helper.assertFalse(game.isDeclareEnabled(), "the declare option must be off");
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        game.bid(game.seatPlayer(game.currentSeat()), 3);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.PLAYING,
                "with the option off the bid must go straight to playing, got " + game.phase());
        // 关掉之后 declare(...) 必须完全无效
        game.declare(players.get(0), DoudizhuAction.REVEAL_HAND);
        game.declare(players.get(0), DoudizhuAction.DOUBLE_UP);
        helper.assertFalse(game.isRevealed(0), "declaring outside the declare phase must be ignored");
        helper.assertFalse(game.isDoubled(0), "doubling outside the declare phase must be ignored");
        helper.succeed();
    }

    /**
     * 明牌 + 加倍叠满（个人倍数 ×4）必须真的落到结算里。
     *
     * <p>纯逻辑层的「指数 2 → 倍数 ×4」由 {@code PlayHarnessMain} 断言；这里盯的是
     * <b>两个开关 → 结算里的个人倍数</b>这一段接线，以及整局仍然是零和。</p>
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void stackedMarksReachTheSettlement(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Stack-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        game.startGame();
        playToEnd(game);
        helper.assertTrue(game.isGameOver(), "the game must finish");
        DoudizhuScore.Settlement settlement = game.settlement();
        helper.assertTrue(settlement != null, "a finished game must settle");
        for (int seat = 0; seat < 3; seat++) {
            helper.assertTrue(settlement.factor(seat) == game.personalFactor(seat),
                    "seat " + seat + ": the settled personal stake must match the switches, expected x"
                            + game.personalFactor(seat) + " but settled x" + settlement.factor(seat));
        }
        helper.assertTrue(settlement.total() == 0, "personal stakes must keep the settlement zero-sum");
        helper.succeed();
    }

    /** AI 难度档位：选项要真的通到 {@code AiPolicy}，三档互不相同，而且不影响规则。 */
    @GameTest(template = "empty")
    public static void aiDifficultyOptionReachesTheEngine(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Profile-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        helper.assertTrue(game.getOptions().contains(game.aiDifficultyOption()),
                "the difficulty option must be exposed in the option list");
        helper.assertTrue(game.getOptions().contains(game.grabLandlordOption()),
                "the grab option must be exposed in the option list");
        helper.assertTrue(game.getOptions().contains(game.declareBonusOption()),
                "the declare option must be exposed in the option list");
        helper.assertTrue(game.aiProfile().id().equals(AiProfile.balanced().id()),
                "balanced must be the default difficulty, got " + game.aiProfile().id());

        game.aiDifficultyOption().set(0);
        helper.assertTrue(game.aiProfile().id().equals(AiProfile.conservative().id()),
                "option 0 must reach the cautious profile, got " + game.aiProfile().id());
        game.aiDifficultyOption().set(2);
        helper.assertTrue(game.aiProfile().id().equals(AiProfile.aggressive().id()),
                "option 2 must reach the aggressive profile, got " + game.aiProfile().id());
        game.aiDifficultyOption().set(1);
        helper.assertTrue(game.aiProfile().id().equals(AiProfile.balanced().id()),
                "option 1 must reach the balanced profile, got " + game.aiProfile().id());

        // 难度只调权重，不许动规则：换档前后 RuleOptions 必须逐项一致
        RuleOptions before = game.ruleOptions();
        game.aiDifficultyOption().set(2);
        RuleOptions after = game.ruleOptions();
        helper.assertTrue(before.threeWithTwo() == after.threeWithTwo()
                        && before.fourWithTwo() == after.fourWithTwo()
                        && before.maxAirplane() == after.maxAirplane()
                        && before.fourPlayer() == after.fourPlayer(),
                "changing the AI difficulty must not change any rule option");

        // 三档都要能打完一整局（换档不会把 AI 卡住或产生非法出牌）
        game.aiDifficultyOption().set(0);
        game.startGame();
        playToEnd(game);
        helper.assertTrue(game.isGameOver(), "the cautious AI must be able to finish a game");
        game.aiDifficultyOption().set(2);
        game.startGame();
        playToEnd(game);
        helper.assertTrue(game.isGameOver(), "the aggressive AI must be able to finish a game");
        helper.succeed();
    }

    /** 动作序号是线协议：老动作一个都不能移位，新动作只能追加在末尾。 */
    @GameTest(template = "empty")
    public static void actionIdsStayAppendOnly(GameTestHelper helper) {
        helper.assertTrue(DoudizhuAction.PLAY.id() == 0, "PLAY must stay id 0");
        helper.assertTrue(DoudizhuAction.PASS.id() == 1, "PASS must stay id 1");
        helper.assertTrue(DoudizhuAction.HINT.id() == 2, "HINT must stay id 2");
        helper.assertTrue(DoudizhuAction.BID_1.id() == 3, "BID_1 must stay id 3");
        helper.assertTrue(DoudizhuAction.BID_2.id() == 4, "BID_2 must stay id 4");
        helper.assertTrue(DoudizhuAction.BID_3.id() == 5, "BID_3 must stay id 5");
        helper.assertTrue(DoudizhuAction.BID_PASS.id() == 6, "BID_PASS must stay id 6");
        helper.assertTrue(DoudizhuAction.RETRACT.id() == 7, "RETRACT must stay id 7");
        helper.assertTrue(DoudizhuAction.REVEAL_HAND.id() == 8, "REVEAL_HAND is appended as id 8");
        helper.assertTrue(DoudizhuAction.DOUBLE_UP.id() == 9, "DOUBLE_UP is appended as id 9");
        helper.assertTrue(DoudizhuAction.DECLARE_PASS.id() == 10, "DECLARE_PASS is appended as id 10");
        for (DoudizhuAction action : DoudizhuAction.values()) {
            helper.assertTrue(DoudizhuAction.byId(action.id()) == action,
                    "byId must round-trip " + action);
        }
        helper.assertTrue(DoudizhuAction.byId(-1) == null && DoudizhuAction.byId(999) == null,
                "unknown action ids must resolve to null instead of throwing");
        helper.assertTrue(DoudizhuAction.REVEAL_HAND.bidScore() == -1
                        && DoudizhuAction.DOUBLE_UP.bidScore() == -1
                        && DoudizhuAction.DECLARE_PASS.bidScore() == -1,
                "declare actions are not bids");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 手牌排序 / 出牌手感

    /**
     * 手动牌序：拖动之后顺序要留住，不能被出牌、选牌、撤回重排掉。
     *
     * <p>这一条对应玩家反馈「地主新到手的牌无法调整牌序」：既要有调整手段，也要保证调整结果
     * <b>不会在下一手就自己变回去</b>。</p>
     */
    @GameTest(template = "empty")
    public static void manualHandOrderSurvivesPlayAndRevert(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Order-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        useClassicBidding(game);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        CardPlayer landlord = game.seatPlayer(game.currentSeat());
        bidThenAwaitPlaying(game, landlord, 3);
        int seat = game.seatOf(landlord);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.PLAYING, "the game must be playable");

        // 默认是自动排序：手牌按点数单调不减
        helper.assertFalse(game.isManualOrder(seat), "the hand starts auto-sorted");
        helper.assertTrue(isRankOrdered(game, landlord), "a fresh hand must be sorted by rank");

        // 把最后一张拖到最前面：手动牌序生效，而且这一手不再自动排序
        int size = game.getPlayerHand(landlord).size();
        int last = DoudizhuValues.valueOf(cardAt(game, landlord, size - 1));
        helper.assertTrue(game.moveHandCard(landlord, size - 1, 0), "dragging the last card to the front must move it");
        helper.assertTrue(game.isManualOrder(seat), "dragging must switch this seat to manual order");
        helper.assertTrue(DoudizhuValues.valueOf(cardAt(game, landlord, 0)) == last,
                "the dragged card must now be first");
        helper.assertTrue(game.getPlayerHand(landlord).size() == size, "dragging must never lose a card");

        // 越界与空操作都要被安静地忽略，绝不能丢牌或抛异常
        helper.assertFalse(game.moveHandCard(landlord, 0, 0), "moving a card onto itself is a no-op");
        helper.assertTrue(game.moveHandCard(landlord, -5, 999),
                "out-of-range indices must be clamped to a real move, not rejected");
        helper.assertTrue(game.getPlayerHand(landlord).size() == size, "clamped moves must not lose cards");
        helper.assertFalse(game.moveHandCard(landlord, 0, 0), "the same-position no-op must stay a no-op");
        helper.assertTrue(game.getPlayerHand(landlord).size() == size, "still no card lost");

        // 选一张牌再撤回：手牌顺序必须原样回来（不能整体重排）
        List<Integer> before = handValues(game, landlord);
        game.selectFromHand(landlord, 0, 0);
        helper.assertTrue(game.getPlayerHand(landlord).size() == size - 1, "selecting removes one card");
        game.retractSelection(landlord);
        helper.assertTrue(handValues(game, landlord).equals(before),
                "retracting must put the card back exactly where it was, not re-sort the hand");

        // 打出一张之后仍然不重排
        List<Integer> beforePlay = handValues(game, landlord);
        game.selectFromHand(landlord, 0, 0);
        game.playSelection(landlord);
        List<Integer> afterPlay = handValues(game, landlord);
        List<Integer> expected = new ArrayList<>(beforePlay);
        expected.remove(0);
        helper.assertTrue(afterPlay.equals(expected),
                "playing must only remove that card and keep the rest in place");

        // 「整理」把它拉回默认顺序
        helper.assertTrue(game.tidyHand(landlord), "tidy must be accepted");
        helper.assertFalse(game.isManualOrder(seat), "tidying drops the manual order");
        helper.assertTrue(isRankOrdered(game, landlord), "tidying must restore the rank order");
        helper.succeed();
    }

    /** 手动牌序下，地主拿到底牌后三张新牌接在末尾，而且还能继续拖。 */
    @GameTest(template = "empty")
    public static void bottomCardsAppendUnderManualOrder(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Bottom-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        useClassicBidding(game);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        CardPlayer landlord = game.seatPlayer(game.currentSeat());
        int seat = game.seatOf(landlord);
        int handBefore = game.getPlayerHand(landlord).size();
        // 拿底牌之前先摆一个手动牌序
        game.moveHandCard(landlord, 0, handBefore - 1);
        helper.assertTrue(game.isManualOrder(seat), "the landlord must be in manual order");
        List<Integer> beforeBottom = handValues(game, landlord);

        game.bid(landlord, 3);
        helper.assertTrue(game.getPlayerHand(landlord).size() == handBefore + DoudizhuValues.bottomCount(3),
                "the landlord must receive the bottom cards");
        List<Integer> after = handValues(game, landlord);
        helper.assertTrue(after.subList(0, beforeBottom.size()).equals(beforeBottom),
                "the manual order must be preserved and the new cards appended at the end");
        // 新到手的牌也能拖
        helper.assertTrue(game.moveHandCard(landlord, after.size() - 1, 0), "the new cards must be draggable too");
        helper.assertTrue(game.getPlayerHand(landlord).size() == after.size(), "dragging must not lose cards");
        helper.succeed();
    }

    /**
     * 出牌手感：非法出牌<b>不再清空选牌</b>，玩家可以拿掉一张直接重试。
     *
     * <p>以前这里会把选牌整批退回手牌，等于「点错一次就得从头再选」，是最伤手感的一处。</p>
     */
    @GameTest(template = "empty")
    public static void rejectedPlayKeepsTheSelection(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("Feel-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        useClassicBidding(game);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        CardPlayer landlord = game.seatPlayer(game.currentSeat());
        bidThenAwaitPlaying(game, landlord, 3);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.PLAYING, "the game must be playable");

        // 故意选一个一定不合法的两张牌（两张不同点数）
        int size = game.getPlayerHand(landlord).size();
        int firstValue = DoudizhuValues.valueOf(cardAt(game, landlord, 0));
        int secondIndex = -1;
        for (int index = 1; index < size; index++) {
            if (DoudizhuValues.valueOf(cardAt(game, landlord, index)) != firstValue) {
                secondIndex = index;
                break;
            }
        }
        helper.assertTrue(secondIndex > 0, "the hand must contain two different ranks");
        game.selectFromHand(landlord, 0, 0);
        game.selectFromHand(landlord, 0, secondIndex - 1);
        int selected = game.selectionSize(landlord);
        helper.assertTrue(selected == 2, "two cards must be selected");

        game.playSelection(landlord);
        helper.assertTrue(game.selectionSize(landlord) == selected,
                "a rejected play must keep the selection, got " + game.selectionSize(landlord));
        helper.assertTrue(game.getPlayerHand(landlord).size() == size - selected,
                "the hand must not change when the play is rejected");
        helper.assertTrue(game.lastPlaySize() == 0, "nothing may be committed");

        // 拿掉一张之后仍然能正常改选，并且能正常出牌
        game.deselectToHand(landlord, 0, 0);
        helper.assertTrue(game.selectionSize(landlord) == 1, "the player can still adjust the selection");
        game.retractSelection(landlord);
        helper.assertTrue(game.selectionSize(landlord) == 0, "retract still clears everything");
        helper.assertTrue(game.getPlayerHand(landlord).size() == size, "every card ends up back in hand");
        game.selectFromHand(landlord, 0, 0);
        game.playSelection(landlord);
        helper.assertTrue(game.lastPlaySize() == 1, "a legal single must still play normally");
        helper.succeed();
    }

    private static Card cardAt(DoudizhuGame game, CardPlayer player, int index) {
        List<Card> cards = new ArrayList<>();
        game.getPlayerHand(player).forEach(cards::add);
        return cards.get(index);
    }

    private static List<Integer> handValues(DoudizhuGame game, CardPlayer player) {
        List<Integer> values = new ArrayList<>();
        game.getPlayerHand(player).forEach(card -> values.add(DoudizhuValues.valueOf(card)));
        return values;
    }

    /** 手牌是否按点数单调不减。 */
    private static boolean isRankOrdered(DoudizhuGame game, CardPlayer player) {
        List<Integer> values = handValues(game, player);
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index) < values.get(index - 1)) {
                return false;
            }
        }
        return true;
    }

    /** 叫分 / 抢地主的表态必须逐座位可见：谁抢了、谁不抢、谁叫了几分。 */
    @GameTest(template = "empty")
    public static void bidDecisionsAreTrackedPerSeat(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new AiSeat("Decide-" + seat));
        }

        // 抢地主模式：抢的人标 bit3，表态标 bit4，并且当前地主候选跟着最后一个抢的人走
        DoudizhuGame grab = new DoudizhuGame(players, deck);
        grab.startGame();
        while (!grab.isGameReady()) {
            grab.tick();
        }
        helper.assertTrue(grab.leadingSeat() < 0, "nobody has grabbed before the first decision");
        for (int seat = 0; seat < 3; seat++) {
            helper.assertFalse(grab.hasGrabbed(seat), "nobody has grabbed yet (seat " + seat + ")");
            helper.assertFalse(grab.hasBidDecided(seat), "nobody has decided yet (seat " + seat + ")");
            helper.assertTrue(grab.bidValue(seat) == -1, "a bid value is unknown before the decision");
        }
        int grabs = 0;
        while (grab.phase() == DoudizhuGame.Phase.BIDDING && grabs < 4) {
            int seat = grab.currentSeat();
            grab.bid(grab.seatPlayer(seat), 1);
            grabs++;
            helper.assertTrue(grab.hasGrabbed(seat), "the seat that grabbed must be marked, seat " + seat);
            helper.assertTrue(grab.hasBidDecided(seat), "the seat that grabbed must also count as decided");
            helper.assertTrue(grab.leadingSeat() == seat,
                    "the last grabber must be the current landlord candidate");
        }
        helper.assertTrue(grab.grabCount() == 3, "all three seats grabbed");

        // 叫分模式：每个座位的分值必须被记下来（0 = 不叫）
        DoudizhuGame bid = new DoudizhuGame(players, deck);
        useClassicBidding(bid);
        bid.startGame();
        while (!bid.isGameReady()) {
            bid.tick();
        }
        int firstSeat = bid.currentSeat();
        bid.bid(bid.seatPlayer(firstSeat), 2);
        helper.assertTrue(bid.bidValue(firstSeat) == 2, "seat " + firstSeat + " bid 2, got " + bid.bidValue(firstSeat));
        helper.assertTrue(bid.hasBidDecided(firstSeat), "the bidder must be marked as decided");
        helper.assertFalse(bid.hasGrabbed(firstSeat), "叫分模式不产生「抢」标记");
        helper.assertTrue(bid.leadingSeat() == firstSeat, "the highest bidder is the current candidate");
        int secondSeat = bid.currentSeat();
        bid.bid(bid.seatPlayer(secondSeat), 0);
        helper.assertTrue(bid.bidValue(secondSeat) == 0, "a pass must be recorded as 0, got " + bid.bidValue(secondSeat));
        helper.assertTrue(bid.hasBidDecided(secondSeat), "a pass still counts as decided");
        helper.assertTrue(bid.leadingSeat() == firstSeat, "passing must not change the leading seat");
        helper.succeed();
    }

    /**
     * 明牌之后，界面里那份「明牌展示槽」必须真的有整手正面牌。
     *
     * <p>牌桌上那圈牌确实会翻成正面，但牌局界面占了屏幕中央一大块，自己这个视角里别人的牌扇
     * 基本被面板挡住——玩家反馈的「明牌了看不到牌」就是这么来的。展示槽是界面里唯一一定看得见的那份，
     * 所以这里逐张比对：张数一致、全部正面、点数与真手牌一一对应，而且会跟着出牌一起变少。</p>
     */
    @GameTest(template = "empty")
    public static void revealedHandIsMirroredIntoTheScreen(GameTestHelper helper) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat("View-" + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        useClassicBidding(game);
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        CardPlayer landlord = game.seatPlayer(game.currentSeat());
        int seat = game.seatOf(landlord);
        game.bid(landlord, 3);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.DECLARING, "the declare phase must be open");

        // 明牌之前：展示槽必须是空的（空槽框架不画，所以这几行平时不占画面）
        for (int other = 0; other < 3; other++) {
            helper.assertTrue(game.getRevealView(other).isEmpty(),
                    "seat " + other + " has not revealed, so its reveal view must stay empty");
        }

        game.declare(players.get(0), DoudizhuAction.REVEAL_HAND);
        List<Card> real = new ArrayList<>();
        game.getPlayerHand(players.get(0)).forEach(real::add);
        List<Card> shown = new ArrayList<>();
        game.getRevealView(0).forEach(shown::add);
        helper.assertTrue(shown.size() == real.size(),
                "the reveal view must mirror the whole hand: " + shown.size() + " vs " + real.size());
        for (int index = 0; index < shown.size(); index++) {
            helper.assertFalse(shown.get(index).flipped(),
                    "reveal view card " + index + " must be face up");
            helper.assertTrue(DoudizhuValues.valueOf(shown.get(index)) == DoudizhuValues.valueOf(real.get(index)),
                    "reveal view card " + index + " must match the real hand");
        }
        // 必须是副本：动展示槽不能影响真手牌
        game.getRevealView(0).clear();
        helper.assertTrue(game.getPlayerHand(players.get(0)).size() == real.size(),
                "clearing the reveal view must not touch the real hand");
        helper.assertTrue(game.getPlayerHand(players.get(0)).size() == real.size(),
                "the real hand must be intact");

        // 没明牌的座位一直是空的
        helper.assertTrue(game.getRevealView(1).isEmpty() && game.getRevealView(2).isEmpty(),
                "seats that never revealed must have empty reveal views");
        helper.succeed();
    }

    /**
     * 世界牌桌上的「轮到谁」提示：轮到谁，谁那圈牌就往桌心推一点，换人时上一家自动退回。
     *
     * <p>1.8.0 之前这件事是靠悬浮文字做的：一张牌才 0.156 格宽，任何悬浮字都比牌还大，
     * 视角一动就糊成一片，观战的人只嫌它挡路。现在改成纯<b>位置</b>信号
     * （见 {@code DoudizhuGame.markTurn}）——位置本身就是牌局状态，所以用纯逻辑测：
     * 打出一手牌，前后各量一次「扇形到桌心的距离」。</p>
     */
    @GameTest(template = "empty")
    public static void turnMarkerOnlyPushesTheActiveFan(GameTestHelper helper) {
        DoudizhuGame game = threeSeatTableReadyToPlay(helper, "Marker-");
        // 桌心：牌局进行中「上一手牌」会偏离它，所以先固定记下来当参考点
        float[] centre = new float[]{game.getPlayArea().getX(), game.getPlayArea().getY()};
        int active = game.currentSeat();
        helper.assertTrue(active == game.landlordSeat(), "the landlord must lead the first trick");
        int next = (active + 1) % game.seatCount();
        int idle = (active + 2) % game.seatCount();

        float[] beforeActive = fanOffset(game, active, centre);
        float[] beforeNext = fanOffset(game, next, centre);
        float[] beforeIdle = fanOffset(game, idle, centre);
        helper.assertTrue(beforeActive[2] < beforeNext[2] - 1f && beforeActive[2] < beforeIdle[2] - 1f,
                "the active seat's fan must sit closer to the table centre than the other seats: "
                        + beforeActive[2] + " vs " + beforeNext[2] + "/" + beforeIdle[2]);
        helper.assertTrue(Math.abs(beforeNext[2] - beforeIdle[2]) < 0.01f,
                "both inactive fans must sit exactly at their rest distance from the centre");

        // 打出一张单牌：轮次交给下一家，标记也要跟着换人
        game.selectFromHand(game.seatPlayer(active), 0, 0);
        game.playSelection(game.seatPlayer(active));
        helper.assertTrue(game.currentSeat() == next,
                "the turn must move to the next seat, got " + game.currentSeat());

        float[] afterActive = fanOffset(game, active, centre);
        float[] afterNext = fanOffset(game, next, centre);
        float[] afterIdle = fanOffset(game, idle, centre);
        float pushed = afterActive[2] - beforeActive[2];
        float pulled = beforeNext[2] - afterNext[2];
        helper.assertTrue(pushed > 1f,
                "the previous seat's fan must retreat once the turn moves on, moved " + pushed);
        helper.assertTrue(Math.abs(pushed - pulled) < 0.01f,
                "the previous seat must retreat by exactly what the new seat advances: "
                        + pushed + " vs " + pulled);
        helper.assertTrue(afterIdle[0] == beforeIdle[0] && afterIdle[1] == beforeIdle[1],
                "an idle seat's fan must not move at all");
        helper.succeed();
    }

    /** 某座位扇形相对<b>指定桌心</b>的偏移：{@code [dx, dy, 距离]}。 */
    private static float[] fanOffset(DoudizhuGame game, int seat, float[] centre) {
        GameSlot fan = game.getCensoredHand(game.seatPlayer(seat));
        float dx = fan.getX() - centre[0];
        float dy = fan.getY() - centre[1];
        return new float[]{dx, dy, (float) Math.sqrt(dx * dx + dy * dy)};
    }

    /** 两点距离。 */
    private static float distance(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 上一手牌往「出牌者那一侧」偏一小段：牌心那几张牌不写名字，
     * 观战的人靠这个方向看出「这手是谁打的」。
     *
     * <p>偏移是从<b>扇形基准位置</b>算的，所以一个人既出了上一手、又正好轮到他时，
     * 牌不会跟着他被推出去的那圈牌一起前后晃——这一点由「第二次出牌换人之后方向跟着换」间接覆盖。</p>
     */
    @GameTest(template = "empty")
    public static void lastPlayLeansTowardsWhoeverPlayedIt(GameTestHelper helper) {
        DoudizhuGame game = threeSeatTableReadyToPlay(helper, "Lean-");
        float[] centre = new float[]{game.getPlayArea().getX(), game.getPlayArea().getY()};
        int first = game.currentSeat();

        game.selectFromHand(game.seatPlayer(first), 0, 0);
        game.playSelection(game.seatPlayer(first));

        float[] fanOfFirst = fanOffset(game, first, centre);
        float[] play = new float[]{game.getPlayArea().getX(), game.getPlayArea().getY()};
        float[] lean = new float[]{play[0] - centre[0], play[1] - centre[1]};
        float moved = distance(play, centre);
        helper.assertTrue(moved > fanOfFirst[2] * 0.4f,
                "the last play must land clearly in front of the player, not a hair off centre: "
                        + moved + " of " + fanOfFirst[2]);
        helper.assertTrue(moved < fanOfFirst[2] * 0.85f,
                "the lean must stay off the player's own fan: moved " + moved + " vs fan " + fanOfFirst[2]);
        helper.assertTrue(lean[0] * fanOfFirst[0] + lean[1] * fanOfFirst[1] > 0f,
                "the lean must point at the seat that played it");
        helper.assertTrue(distance(play, new float[]{centre[0] + fanOfFirst[0], centre[1] + fanOfFirst[1]})
                        < fanOfFirst[2],
                "the play must end up closer to that seat's fan than the centre was");
        // 朝向：出牌区抄出牌者扇形的 angle 与 stackDirection，所以不同人出的牌朝向不同
        GameSlot playedFan = game.getCensoredHand(game.seatPlayer(first));
        helper.assertTrue(game.getPlayArea().getAngle() == playedFan.getAngle(),
                "the last play must be rotated like the seat that played it: "
                        + game.getPlayArea().getAngle() + " vs " + playedFan.getAngle());
        helper.assertTrue(game.getPlayArea().getStackDirection() == playedFan.getStackDirection(),
                "the last play must stack the way that seat's fan stacks");
        // 地主自己出的牌必须和他的底牌错开：两者都摆在他面前，早先两套独立数值会撞在一起
        // （玩家反馈「底牌和出牌重合了」）。出牌区一定比底牌更靠桌心，且至少隔开 60 单位。
        float[] bottom = new float[]{game.getBottomSlot().getX(), game.getBottomSlot().getY()};
        helper.assertTrue(distance(bottom, centre) > distance(play, centre) + 60f,
                "the landlord's bottom cards must stay behind his own play: bottom "
                        + distance(bottom, centre) + " vs play " + distance(play, centre));

        // 换人出牌：方向跟着换，说明用的是「扇形基准位置」而不是已经推出去的那一圈
        int second = game.currentSeat();
        game.hint(game.seatPlayer(second));
        if (game.selectionSize(game.seatPlayer(second)) > 0) {
            game.playSelection(game.seatPlayer(second));
            float[] fanOfSecond = fanOffset(game, second, centre);
            float[] again = new float[]{
                    game.getPlayArea().getX() - centre[0], game.getPlayArea().getY() - centre[1]};
            helper.assertTrue(again[0] * fanOfSecond[0] + again[1] * fanOfSecond[1] > 0f,
                    "after the next play the lean must point at the new seat");
        }

        // 其余人都不出：这一手被收走，牌心回到原位
        game.passTurn(game.seatPlayer(game.currentSeat()));
        game.passTurn(game.seatPlayer(game.currentSeat()));
        helper.assertTrue(game.getPlayArea().isEmpty(), "everyone passing must clear the last play");
        float[] cleared = new float[]{game.getPlayArea().getX(), game.getPlayArea().getY()};
        helper.assertTrue(distance(cleared, centre) < 1f,
                "a cleared play area must return to the middle of the table, off by "
                        + distance(cleared, centre));
        helper.succeed();
    }

    /** 3 个静默座位、扇形按真实牌桌摆好、已经进入出牌阶段的牌局（地主领出）。 */
    private static DoudizhuGame threeSeatTableReadyToPlay(GameTestHelper helper, String prefix) {
        Deck deck = ChartaMod.CARD_DECKS.getDeck(Doudizhu.id("doudizhu"));
        List<CardPlayer> players = new ArrayList<>();
        for (int seat = 0; seat < 3; seat++) {
            players.add(new SilentSeat(prefix + seat));
        }
        DoudizhuGame game = new DoudizhuGame(players, deck);
        useClassicBidding(game);
        // 模拟真实牌桌的摆位：三个座位的扇形分别落在桌心的三个方向、等距，
        // 而且各自朝向不同（Charta 按椅子朝向设置 angle / stackDirection）。
        // 必须在第一次 tick 之前摆好——那一步会把扇形位置记成「基准位置」。
        GameSlot centre = game.getPlayArea();
        Direction[] facing = {Direction.NORTH, Direction.EAST, Direction.SOUTH};
        // 扇形离桌心 1.5 格（240 单位，160 = 1 格）——和真实牌桌上椅子到桌心的距离同量级，
        // 否则「底牌 vs 出牌区」这类按距离错开的规则在测试里会退化成极端情况。
        float fanDistance = 240f;
        for (int seat = 0; seat < players.size(); seat++) {
            double angle = seat * (2.0 * Math.PI / players.size());
            GameSlot fan = game.getCensoredHand(players.get(seat));
            fan.setX(centre.getX() + (float) Math.cos(angle) * fanDistance);
            fan.setY(centre.getY() + (float) Math.sin(angle) * fanDistance);
            fan.setAngle(seat * 90f);
            fan.setStackDirection(facing[seat % facing.length].getClockWise());
        }
        game.startGame();
        while (!game.isGameReady()) {
            game.tick();
        }
        bidThenAwaitPlaying(game, game.seatPlayer(game.currentSeat()), 3);
        helper.assertTrue(game.phase() == DoudizhuGame.Phase.PLAYING,
                "the world-table test needs the playing phase, got " + game.phase());
        return game;
    }

    private static void playToEnd(DoudizhuGame game) {        int ticks = 0;
        while (!game.isGameOver() && ticks < MAX_GAME_TICKS) {
            game.tick();
            ticks++;
        }
    }

    /** 叫分之后一路推进到出牌阶段。
     *
     * <p>1.3.0 起叫分与出牌之间多了一个「明牌 / 加倍」阶段，测试里 3 个 {@code SilentSeat}
     * 谁都不会点按钮，所以这里必须把那个窗口 tick 完，否则会停在 {@code DECLARING} 上。</p>
     */
    private static void bidThenAwaitPlaying(DoudizhuGame game, CardPlayer bidder, int score) {
        game.bid(bidder, score);
        int ticks = 0;
        while (game.phase() == DoudizhuGame.Phase.DECLARING && ticks < 4000) {
            game.tick();
            ticks++;
        }
    }

    private static int[] totals(DoudizhuGame game, int seats) {
        int[] totals = new int[seats];
        for (int seat = 0; seat < seats; seat++) {
            totals[seat] = game.totalScore(seat);
        }
        return totals;
    }

    /** 记牌器数组的总和（用于「明牌让手牌算作已见」这类整体变化的断言）。 */
    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    // ------------------------------------------------------------------ 工具

    private static void expectType(GameTestHelper helper, ComboType expected, String label, Card... cards) {
        expectType(helper, expected, label, false, cards);
    }

    private static void expectType(GameTestHelper helper, ComboType expected, String label, boolean fourPlayer,
                                   Card... cards) {
        Combo combo = DoudizhuRules.classify(cards(cards), fourPlayer);
        helper.assertTrue(combo != null, label + " must be a valid combination");
        helper.assertTrue(combo.type() == expected,
                label + " must be " + expected + " but was " + combo.type());
    }

    private static void expectInvalid(GameTestHelper helper, String label, Card... cards) {
        Combo combo = DoudizhuRules.classify(cards(cards), false);
        helper.assertTrue(combo == null, label + " must be invalid but was " + combo);
    }

    private static boolean beats(List<Card> candidate, List<Card> previous) {
        return DoudizhuRules.beats(require(candidate, false), require(previous, false));
    }

    private static boolean beats4(List<Card> candidate, List<Card> previous) {
        return DoudizhuRules.beats(require(candidate, true), require(previous, true));
    }

    private static Combo require(List<Card> cards, boolean fourPlayer) {
        Combo combo = DoudizhuRules.classify(cards, fourPlayer);
        if (combo == null) {
            throw new IllegalStateException("test combination is invalid: " + cards.size() + " cards");
        }
        return combo;
    }

    private static List<Card> cards(Card... cards) {
        return new ArrayList<>(List.of(cards));
    }

    private static List<Card> four(String rank) {
        return cards(c(rank), c(rank), c(rank), c(rank));
    }

    /** 取一张 black spade 牌；判定只看点数，花色不影响测试。 */
    private static Card c(String rank) {
        Rank value = Ranks.getRegistry().get(ResourceLocation.fromNamespaceAndPath("charta", rank));
        Suit suit = Suits.getRegistry().get(ResourceLocation.fromNamespaceAndPath("charta", "spades"));
        if (value == null || suit == null) {
            throw new IllegalStateException("unknown charta card: " + rank);
        }
        return Card.create(suit, value);
    }

    private static Card smallJoker() {
        return Card.create(Suits.BLANK, JokerRanks.SMALL_JOKER.get());
    }

    private static Card bigJoker() {
        return Card.create(Suits.BLANK, JokerRanks.BIG_JOKER.get());
    }

    /** 会自己出牌的 AI 座位（等价于坐在游戏椅上的生物）。 */
    private static final class AiSeat extends AutoPlayer {

        private final String name;

        private AiSeat(String name) {
            super(1f);
            this.name = name;
        }

        @Override
        public Component getName() {
            return Component.literal(name);
        }

        @Override
        public DyeColor getColor() {
            return DyeColor.WHITE;
        }

        @Override
        public CardPlayerHead getHead() {
            return CardPlayerHead.ROBOT;
        }
    }

    /** 永不主动出牌的座位：用于验证超时兜底逻辑（模拟一直不点按钮的真人）。 */
    private static final class SilentSeat implements CardPlayer {

        private final LinkedList<Card> hand = new LinkedList<>();
        private final String name;
        private CompletableFuture<GamePlay> play = new CompletableFuture<>();

        private SilentSeat(String name) {
            this.name = name;
        }

        @Override
        public LinkedList<Card> hand() {
            return hand;
        }

        @Override
        public void play(GamePlay play) {
            this.play.complete(play);
        }

        @Override
        public void afterPlay(Consumer<GamePlay> consumer) {
            this.play.thenAccept(value -> {
                try {
                    consumer.accept(value);
                } catch (Exception exception) {
                    Doudizhu.LOGGER.error("Error while handling a silent player's play", exception);
                }
            });
        }

        @Override
        public void resetPlay() {
            this.play = new CompletableFuture<>();
        }

        @Override
        public void tick(Game<?, ?> game) {
            // 永不主动出牌
        }

        @Override
        public boolean shouldCompute() {
            return false;
        }

        @Override
        public void openScreen(Game<?, ?> game, BlockPos pos, Deck deck) {
        }

        @Override
        public void sendMessage(Component message) {
        }

        @Override
        public void sendTitle(Component title, @Nullable Component subtitle) {
        }

        @Override
        public Component getName() {
            return Component.literal(name);
        }

        @Override
        public DyeColor getColor() {
            return DyeColor.WHITE;
        }

        @Override
        public int getId() {
            return -1;
        }

        @Override
        public @Nullable LivingEntity getEntity() {
            return null;
        }

        @Override
        public CardPlayerHead getHead() {
            return CardPlayerHead.UNKNOWN;
        }
    }
}
