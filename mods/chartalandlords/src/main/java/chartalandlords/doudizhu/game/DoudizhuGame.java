package chartalandlords.doudizhu.game;

import chartalandlords.doudizhu.game.engine.DoudizhuScore;
import chartalandlords.doudizhu.game.engine.AiContext;
import chartalandlords.doudizhu.game.engine.AiProfile;
import chartalandlords.doudizhu.game.engine.RuleEngine;
import chartalandlords.doudizhu.game.engine.RuleOptions;
import chartalandlords.doudizhu.game.engine.Combo;
import chartalandlords.doudizhu.Doudizhu;
import chartalandlords.doudizhu.registry.JokerRanks;
import dev.lucaargolo.charta.common.block.entity.CardTableBlockEntity;
import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.game.Game;
import dev.lucaargolo.charta.common.game.api.game.GameOption;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.sound.ModSounds;
import dev.lucaargolo.charta.common.utils.CardImage;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;

/**
 * 斗地主牌局：叫分选地主、发牌（3 人 17×3+3，4 人 25×4+8）、完整牌型判定与 AI 托管。
 *
 * <p>座位上的生物由 Charta 的自动玩家驱动：AI 通过 {@link #getBestPlay(CardPlayer)} 决定要出什么，
 * 真人玩家通过菜单与网络包来选牌、出牌、叫分。</p>
 */
public class DoudizhuGame extends Game<DoudizhuGame, DoudizhuMenu> {

    /** 世界桌面上的底牌槽位序号。 */
    public static final int BOTTOM_SLOT = 0;
    /** 世界桌面上的出牌槽位序号。 */
    public static final int PLAY_SLOT = 1;

    /**
     * 牌局阶段。
     *
     * <p>{@code DECLARING} 是 1.3.0 追加的「明牌 / 加倍」阶段（地主确定之后、出牌之前）。
     * 它排在 {@code PLAYING} 之前是有意的<b>顺序重排</b>，而 {@code DATA_PHASE} 传的是 {@link #ordinal()}，
     * 所以协议端口必须同步从 {@code "3"} 提升到 {@code "4"}（见 {@code Payloads.PROTOCOL_VERSION}）。</p>
     */
    public enum Phase {
        BIDDING,
        DECLARING,
        PLAYING,
        OVER
    }

    private static final int ROW_CAPACITY = DoudizhuValues.ROW_CAPACITY;
    private static final int SEGMENT_CAPACITY = DoudizhuValues.SEGMENT_CAPACITY;
    private static final int AI_BID_MIN_DELAY = 16;
    private static final int AI_BID_EXTRA_DELAY = 24;
    /** 明牌 / 加倍阶段：AI 决定前的随机延迟，让几个人不要同一 tick 一起喊出来。 */
    private static final int AI_DECLARE_MIN_DELAY = 20;
    private static final int AI_DECLARE_EXTRA_DELAY = 30;
    /**
     * 明牌 / 加倍阶段的总时长（tick）：10 秒。
     *
     * <p>阶段是<b>倒计时制</b>而不是「等每个人点完」：真人挂机不该把牌局卡死，
     * 而 AI 会在这个窗口内自己决定完。</p>
     */
    private static final int DECLARE_WINDOW_TICKS = 200;
    /** 连续这么多轮没人叫分后，直接指定手牌最强者当地主，避免无限重发。 */
    private static final int MAX_BID_ROUNDS = 2;
    /** 发牌专用强随机源：洗牌与抽牌都走它，避免共享随机序列带来的「手感规律」。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Random random = new Random();

    /**
     * 桌面上的底牌槽（{@link #BOTTOM_SLOT}）。
     *
     * <p>叫分阶段放整副底牌（扣着，就是桌心上方那叠牌）；地主确定之后只剩<b>一张扣着的牌</b>，
     * 摆在地主面前当地主标记——真正的底牌摊在界面里（底牌预览 + 「看牌」页），桌面上不摊开。</p>
     */
    private final GameSlot bottomCards;
    private final GameSlot playArea;
    /** 界面专用底牌槽：始终保留整副底牌的正面副本，供底牌预览 /「看牌」页 / 记牌器读取。 */
    private final GameSlot bottomView = displaySlot();
    private final GameSlot playRow0 = displaySlot();
    private final GameSlot playRow1 = displaySlot();
    /**
     * 每个座位的「明牌展示槽」：只在该座位明牌之后填上整手正面牌，否则保持为空。
     *
     * <p><b>为什么明牌必须在这里再展示一遍</b>：牌桌上那圈牌确实会翻成正面（{@link #getCensoredHand}），
     * 但牌局界面占了屏幕中央一大块，自己这一侧的视角里别人的牌扇基本被面板挡住——
     * 「明牌了看不见」就是这么来的。展示槽注册成明牌展示行之后，牌局界面里也有一份，
     * 而且空槽框架根本不画，所以没人明牌时布局和以前一模一样。</p>
     */
    private final List<GameSlot> revealViews = new ArrayList<>();

    /**
     * 各座位扇形的「常态位置」：第一次摆位时记下来，轮次标记在这个基准上偏移。
     *
     * <p>记基准而不是「改完再改回来」是为了跟顺序无关：{@link #layoutTableSlots()} 只在第一次
     * tick 跑一次，而轮次标记可能在它之前就设过，直接叠加偏移会越推越远。</p>
     */
    private final Map<CardPlayer, float[]> fanRest = new HashMap<>();
    /** 当前被标记「轮到他」的座位；{@code -1} 表示不标记。 */
    private int markedFanSeat = -1;

    private final Map<CardPlayer, List<GameSlot>> handViewMap = new HashMap<>();
    private final Map<CardPlayer, List<GameSlot>> selectViewMap = new HashMap<>();
    private final Map<CardPlayer, GameSlot> selections = new HashMap<>();
    /** 「提示」轮换：每个玩家上次给出的候选序号，以及当时的上下文键。 */
    private final Map<CardPlayer, Integer> hintCursor = new HashMap<>();
    private final Map<CardPlayer, Integer> hintContext = new HashMap<>();
    private final Map<CardPlayer, Integer> hintTotal = new HashMap<>();
    /** 玩家手动增删选牌的次数；用于判断「提示」是否该回到第一个候选。 */
    private int selectionRevision = 0;
    /** 出牌序号：每成功打出一手 +1，供界面识别「桌上换牌了」。 */
    private int playRevision = 0;

    private GameSlot stock = new GameSlot(new LinkedList<>());
    /** 弃牌堆：记录所有已经打出、不再回到场上的牌（仅用于守恒与统计，不参与渲染）。 */
    private GameSlot discard = new GameSlot(new LinkedList<>());

    private Phase phase = Phase.BIDDING;
    private int landlordSeat = -1;
    private int winnerSeat = -1;
    private int currentSeat = 0;
    private int highestBid = 0;
    private int highestBidSeat = -1;
    private int bidsMade = 0;
    private int bidRounds = 0;
    private int bidTicks = 0;
    private int aiBidDelay = AI_BID_MIN_DELAY;
    private int passCount = 0;
    private Combo lastCombo;
    private int lastPlaySeat = -1;
    private int handSize = 17;
    private int bottomCount = 3;
    private int totalCards = 54;
    private boolean bottomRevealed = false;

    // ------------------------------------------------------------------ 抢地主 / 明牌 / 加倍
    //
    // 三者都只影响「谁当地主」「这一局值多少」，不改变牌型规则，所以状态放在牌局里、判定仍走 RuleEngine。

    /** 抢地主模式下已经发生的「抢」次数（第一个抢的人不翻倍，之后每多一个抢的翻一倍）。 */
    private int grabCount = 0;

    /** 明牌 / 加倍阶段剩余 tick；归零后所有还没决定的人按「跳过」处理。 */
    private int declareTicks = 0;
    private int aiDeclareDelay = 0;
    /** 每个座位在明牌 / 加倍阶段是否已经决定过（决定过就不再自动跳）。 */
    private final boolean[] declared = new boolean[DoudizhuValues.MAX_PLAYERS];
    /** 每个座位是否明牌（手牌公开）。 */
    private final boolean[] revealed = new boolean[DoudizhuValues.MAX_PLAYERS];
    /** 每个座位是否加倍。 */
    private final boolean[] doubled = new boolean[DoudizhuValues.MAX_PLAYERS];

    // ------------------------------------------------------------------ 决定地主的过程（界面展示用）
    //
    // 叫分与抢地主都是「逐座位表态」，牌桌上的其他人需要看到「谁抢了 / 谁叫了几分」，
    // 否则只能看到一个总数，根本不知道当前局面。

    /** 每个座位在叫分模式下的叫分：-1 = 还没轮到/没表态，0 = 不叫，1..3 = 叫了几分。 */
    private final int[] bidValues = new int[DoudizhuValues.MAX_PLAYERS];
    /** 每个座位在抢地主模式下是否抢过。 */
    private final boolean[] grabbed = new boolean[DoudizhuValues.MAX_PLAYERS];
    /** 每个座位在抢/叫阶段是否已经表过态。 */
    private final boolean[] bidDecided = new boolean[DoudizhuValues.MAX_PLAYERS];

    // ------------------------------------------------------------------ 手牌顺序

    /** 每个座位是否用过手动牌序。
     *
     * <p>一旦拖过牌，这个座位的手牌就<b>不再自动排序</b>：出牌、选牌都只做「删掉打出去的那张」，
     * 相对顺序保持不变，所以牌在屏幕上的位置不会每出一手就整体跳一次。</p>
     */
    private final boolean[] manualOrder = new boolean[DoudizhuValues.MAX_PLAYERS];

    /** 本局的快捷键提示是否已经发过（每局只提一次，不刷屏）。 */
    private boolean hotkeyHintSent = false;
    /**
     * 底牌翻开时的点数快照（记牌器用）。
     *
     * <p>界面的底牌行在第一轮出牌结束后会被清空，而底牌是公开信息；AI 推算「还有哪些牌没露面」
     * 时必须把这几张算成已见，否则会把已经躺在某个人手里的牌当成还在别处的风险牌。</p>
     */
    private int[] revealedBottomValues = new int[0];
    /**
     * 翻开时的底牌<b>对象</b>（记牌器用）。
     *
     * <p>只记点数是不够的：底牌进了地主手牌，一旦被地主打出去，它就已经通过桌面/弃牌堆
     * 计过一次「已见」了，再按点数扣一遍就会重复扣减。所以这里保留对象引用，
     * 只有「还在地主手里」的底牌才算额外的公开信息（按对象身份判断，见 {@link #unseenCounts}）。</p>
     */
    private final List<Card> revealedBottomCards = new ArrayList<>();

    /** 牌桌槽位是否已经按实际摆位重排过（见 {@link #layoutTableSlots()}）。 */
    private boolean tableLaidOut = false;

    // ------------------------------------------------------------------ 牌局选项（走 Charta 原生的「选项」界面）

    /** 允许三带一 / 三带二。Pagat 记载的两副牌变体会关掉它。 */
    private final GameOption.Bool THREE_WITH_TWO = new GameOption.Bool(true,
            Component.translatable("rule.chartalandlords.three_with_two"),
            Component.translatable("rule.chartalandlords.three_with_two.description"));
    /** 允许四带二 / 四带两对。 */
    private final GameOption.Bool FOUR_WITH_TWO = new GameOption.Bool(true,
            Component.translatable("rule.chartalandlords.four_with_two"),
            Component.translatable("rule.chartalandlords.four_with_two.description"));
    /** 四带二的「二」可以是一对。 */
    private final GameOption.Bool FOUR_TWO_PAIR_KICKER = new GameOption.Bool(true,
            Component.translatable("rule.chartalandlords.four_two_pair_kicker"),
            Component.translatable("rule.chartalandlords.four_two_pair_kicker.description"));
    /** 允许大小王同时作为同一手牌的带牌。 */
    private final GameOption.Bool JOKERS_AS_WING_PAIR = new GameOption.Bool(false,
            Component.translatable("rule.chartalandlords.jokers_as_wing_pair"),
            Component.translatable("rule.chartalandlords.jokers_as_wing_pair.description"));
    /** 飞机最大三张数（同时约束带单与带对的最大数量）。 */
    private final GameOption.Number MAX_AIRPLANE = new GameOption.Number(6, 2, 6,
            Component.translatable("rule.chartalandlords.max_airplane"),
            Component.translatable("rule.chartalandlords.max_airplane.description"));
    /** 是否结算分数。 */
    private final GameOption.Bool SCORING = new GameOption.Bool(true,
            Component.translatable("rule.chartalandlords.scoring"),
            Component.translatable("rule.chartalandlords.scoring.description"));
    /**
     * 抢地主模式：叫分变成一轮「抢 / 不抢」，抢的人越多倍数越高。
     *
     * <p>默认开启——这是最主流的玩法，也让「底分」不再只是叫分高低的附属品。</p>
     */
    private final GameOption.Bool GRAB_LANDLORD = new GameOption.Bool(true,
            Component.translatable("rule.chartalandlords.grab_landlord"),
            Component.translatable("rule.chartalandlords.grab_landlord.description"));
    /** 明牌 / 加倍阶段：地主确定后每人选一次「明牌 / 加倍 / 跳过」，各自给个人倍数翻倍。 */
    private final GameOption.Bool DECLARE_BONUS = new GameOption.Bool(true,
            Component.translatable("rule.chartalandlords.declare_bonus"),
            Component.translatable("rule.chartalandlords.declare_bonus.description"));
    /** AI 难度档位：0 = 保守、1 = 均衡（默认）、2 = 激进。 */
    private final GameOption.Number AI_DIFFICULTY = new GameOption.Number(1, 0, AiProfile.OPTION_COUNT - 1,
            Component.translatable("rule.chartalandlords.ai_difficulty"),
            Component.translatable("rule.chartalandlords.ai_difficulty.description"));

    // ------------------------------------------------------------------ 计分状态

    /** 本局打出的炸弹 + 王炸数（每个翻一倍）。 */
    private int nukeCount = 0;
    /** 本局地主的出牌手数（反春天判定）。 */
    private int landlordPlays = 0;
    /** 本局农民合计出牌手数（春天判定）。 */
    private int farmersPlays = 0;

    // ------------------------------------------------------------------ 跨局记分板
    //
    // Charta 每开一局都会 `type.create(...)` 新建一个 Game 实例（CardTableBlockEntity.startGame 第 109 行），
    // 所以累计分数**不能**放在实例字段里；而且 getOrderedPlayers() 每次开局都会 Collections.shuffle
    // 决定起始座位，**座位号每局都会轮转**，所以也不能按座位号存。只能按「牌桌身份 + 玩家身份」存。

    /** 牌桌身份 → 玩家身份 → 累计分数。 */
    private static final Map<String, TableScores> TABLE_SCORES = new ConcurrentHashMap<>();
    /** 最多保留多少张牌桌的记分板，超出后淘汰最久没用过的。 */
    private static final int MAX_TRACKED_TABLES = 64;

    /** 本局所在牌桌的身份：座位所在椅子的集合（与座位轮转无关）。 */
    private String scoreboardTableKey;

    private static final class TableScores {

        private final Map<String, Integer> scores = new ConcurrentHashMap<>();
        private volatile long lastUsed = System.currentTimeMillis();
        private volatile int rounds;
    }

    /** 重新计算牌桌身份；每局开始时调用一次。 */
    private void refreshScoreboardKey() {
        List<String> seats = new ArrayList<>(players.size());
        for (CardPlayer player : players) {
            LivingEntity entity = player.getEntity();
            if (entity != null) {
                seats.add(entity.level().dimension().location() + "@" + entity.blockPosition().toShortString());
            } else {
                // 拿不到实体（测试用的假座位）时退回名字，同一批座位仍然等价
                seats.add("name:" + player.getName().getString());
            }
        }
        Collections.sort(seats);
        this.scoreboardTableKey = String.join("|", seats);
    }

    /** 玩家的稳定身份：实体 UUID；拿不到实体时退回名字。 */
    private static String scoreboardPlayerKey(CardPlayer player) {
        LivingEntity entity = player.getEntity();
        return entity != null ? entity.getUUID().toString() : "name:" + player.getName().getString();
    }

    private TableScores scoreboardForWrite() {
        TableScores table = TABLE_SCORES.computeIfAbsent(
                scoreboardTableKey == null ? "" : scoreboardTableKey, key -> new TableScores());
        table.lastUsed = System.currentTimeMillis();
        if (TABLE_SCORES.size() > MAX_TRACKED_TABLES) {
            evictStaleScoreboards();
        }
        return table;
    }

    /** 表太多时丢掉最久没动过的那一半，避免静态表无限增长。 */
    private static void evictStaleScoreboards() {
        List<Map.Entry<String, TableScores>> entries = new ArrayList<>(TABLE_SCORES.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getValue().lastUsed));
        for (int i = 0; i < entries.size() / 2; i++) {
            TABLE_SCORES.remove(entries.get(i).getKey());
        }
    }
    /** 最近一局结算；未结算为 null。 */
    private DoudizhuScore.Settlement settlement;

    /**
     * 桌心（槽位坐标，160 = 1 格）：扇形推出方向、底牌缩进方向、上一手牌的偏移全部以它为基准。
     *
     * <p><b>不能用 {@code playArea} 的当前位置当桌心</b>：上一手牌会往出牌者那一侧偏一点
     * （见 {@link #PLAY_DRIFT}），拿它当基准的话，扇形推出的方向和长度会跟着这手牌一起变，
     * 「轮到谁」的位移就会一次一个样。</p>
     */
    private static final float TABLE_CENTER_X = CardTableBlockEntity.TABLE_WIDTH / 2f - CardImage.WIDTH / 2f;
    private static final float TABLE_CENTER_Y = 62f;
    /**
     * 上一手牌从桌心往出牌者那一侧走多少（0 = 一直摆在桌心，1 = 贴到出牌者的扇形上）。
     *
     * <p>真实牌桌上出牌的人会把牌推到<b>自己面前</b>，所以这里取 **0.55**：过了中点就明确属于
     * 出牌者那一侧（「在他面前」），又不会贴到他的扇形上。早先用 0.26 只是「稍微偏一点」，
     * 玩家反馈那样看不出是谁的牌（「显示在出牌玩家面前而不是有所偏移」）。</p>
     */
    private static final float PLAY_DRIFT = 0.55f;

    public DoudizhuGame(List<CardPlayer> players, Deck deck) {
        super(players, deck);
        // 底牌摆在桌面上方，上一手牌摊在桌面中心。这两个是「会上桌」的槽，用 tableSlot 以便反查到牌桌。
        this.bottomCards = addSlot(tableSlot(TABLE_CENTER_X, 18f, Direction.EAST, 110f));
        this.playArea = addSlot(tableSlot(TABLE_CENTER_X, TABLE_CENTER_Y, Direction.EAST, 130f));
        // 明牌展示槽按座位固定分配，索引必须与 DoudizhuMenu.revealSlotOfSeat 一致
        for (int seat = 0; seat < DoudizhuValues.MAX_PLAYERS; seat++) {
            this.revealViews.add(displaySlot());
        }
    }

    /**
     * 会摆到桌面上的只读展示槽。
     *
     * <p>与 {@link #displaySlot} 唯一的区别是插不进也拿不走牌：这两条槽由牌局自己维护。
     * 它们照样会走框架的卡槽同步，所以牌桌上的每个人都能看到底牌与上一手牌。</p>
     */
    private GameSlot tableSlot(float x, float y, Direction direction, float maxStack) {
        return new GameSlot(new LinkedList<>(), x, y, 0f, 0f, direction, maxStack, true) {
            @Override
            public boolean canInsertCard(CardPlayer player, List<Card> cards, int index) {
                return false;
            }

            @Override
            public boolean canRemoveCard(CardPlayer player, int index) {
                return false;
            }
        };
    }

    /** 座位 {@code seat} 的明牌展示槽；越界时返回一个永远为空的槽。 */
    public GameSlot getRevealView(int seat) {
        if (seat < 0 || seat >= revealViews.size()) {
            return EMPTY_VIEW;
        }
        return revealViews.get(seat);
    }

    /** 越界座位用的空槽（永不为空会被误画，所以固定为空）。 */
    private static final GameSlot EMPTY_VIEW = displaySlot();

    // ------------------------------------------------------------------ 注册表接口

    @Override
    public DoudizhuMenu createMenu(int containerId, Inventory playerInventory, AbstractCardMenu.Definition definition) {
        return new DoudizhuMenu(containerId, playerInventory, definition);
    }

    @Override
    public Predicate<Deck> getDeckPredicate() {
        return deck -> {
            int size = deck.getCards().size();
            return (size == 54 || size == 108) && deck.getCards().stream().allMatch(getCardPredicate());
        };
    }

    @Override
    public Predicate<Card> getCardPredicate() {
        return card -> {
            boolean standard = Suits.STANDARD.contains(card.suit()) && Ranks.STANDARD.contains(card.rank());
            boolean joker = card.suit() == Suits.BLANK
                    && (card.rank() == JokerRanks.SMALL_JOKER.get() || card.rank() == JokerRanks.BIG_JOKER.get());
            return standard || joker;
        };
    }

    @Override
    public Optional<Component> playerPredicate(List<CardPlayer> players) {
        int size = deck.getCards().size();
        if (size != 54 && size != 108) {
            // 客户端选桌界面上临时牌局拿不到牌堆，交给服务端裁决
            return Optional.empty();
        }
        int required = size == 54 ? 3 : 4;
        if (players.size() != required) {
            return Optional.of(Component.translatable("message.chartalandlords.wrong_seat_count", required, size));
        }
        return Optional.empty();
    }

    @Override
    public int getMinPlayers() {
        return DoudizhuValues.MIN_PLAYERS;
    }

    @Override
    public int getMaxPlayers() {
        return DoudizhuValues.MAX_PLAYERS;
    }

    @Override
    public List<GameOption<?>> getOptions() {
        return List.of(THREE_WITH_TWO, FOUR_WITH_TWO, FOUR_TWO_PAIR_KICKER, JOKERS_AS_WING_PAIR,
                MAX_AIRPLANE, SCORING, GRAB_LANDLORD, DECLARE_BONUS, AI_DIFFICULTY);
    }

    /** AI 难度档位选项（测试与界面需要直接改它；下标会随选项增减而变，所以显式暴露）。 */
    public GameOption.Number aiDifficultyOption() {
        return AI_DIFFICULTY;
    }

    /** 抢地主模式选项。 */
    public GameOption.Bool grabLandlordOption() {
        return GRAB_LANDLORD;
    }

    /** 明牌 / 加倍选项。 */
    public GameOption.Bool declareBonusOption() {
        return DECLARE_BONUS;
    }

    /** 当前 AI 难度档位（保守 / 均衡 / 激进）。 */
    public AiProfile aiProfile() {
        return AiProfile.of(AI_DIFFICULTY.get());
    }

    /** 是否处于抢地主模式。 */
    public boolean isGrabMode() {
        return GRAB_LANDLORD.get();
    }

    /** 是否启用明牌 / 加倍阶段。 */
    public boolean isDeclareEnabled() {
        return DECLARE_BONUS.get();
    }

    /**
     * 把当前牌局选项翻译成纯规则开关（{@link RuleOptions}）。所有规则判定都走这里，
     * 绝不在别处直接读选项，避免同一个开关在两个地方被解释成不同含义。
     */
    public RuleOptions ruleOptions() {
        RuleOptions options = RuleOptions.of(isFourPlayer())
                .withThreeWithTwo(THREE_WITH_TWO.get())
                .withFourWithTwo(FOUR_WITH_TWO.get())
                .withFourTwoSinglesMayBePair(FOUR_TWO_PAIR_KICKER.get())
                .withJokersAsWingPair(JOKERS_AS_WING_PAIR.get())
                .withMaxAirplane(MAX_AIRPLANE.get())
                .withMaxAirplaneSingle(Math.max(2, MAX_AIRPLANE.get()))
                .withMaxAirplanePair(Math.max(2, Math.min(4, MAX_AIRPLANE.get())));
        return options;
    }

    /** 是否结算分数（关闭时只判定胜负，与 1.0.0 的行为一致）。 */
    public boolean isScoring() {
        return SCORING.get();
    }

    /** 最近一局的结算结果；尚未结束或未结算时为 null。 */
    public DoudizhuScore.Settlement settlement() {
        return settlement;
    }

    /** 每个座位累计的分数（跨局；按玩家身份取，与座位轮转无关）。 */
    public int totalScore(int seat) {
        if (scoreboardTableKey == null || seat < 0 || seat >= players.size()) {
            return 0;
        }
        TableScores table = TABLE_SCORES.get(scoreboardTableKey);
        if (table == null) {
            return 0;
        }
        table.lastUsed = System.currentTimeMillis();
        return table.scores.getOrDefault(scoreboardPlayerKey(players.get(seat)), 0);
    }

    /** 本局一共打到了第几局（同一张牌桌上的累计局数）。 */
    public int roundsPlayed() {
        if (scoreboardTableKey == null) {
            return 0;
        }
        TableScores table = TABLE_SCORES.get(scoreboardTableKey);
        return table == null ? 0 : table.rounds;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void startGame() {
        // 每局都是新实例（Charta 会 new Game），先按本局座位重新认领牌桌记分板
        refreshScoreboardKey();
        this.phase = Phase.BIDDING;
        this.isGameReady = false;
        this.isGameOver = false;
        this.winnerSeat = -1;
        this.landlordSeat = -1;
        this.highestBid = 0;
        this.highestBidSeat = -1;
        this.bidsMade = 0;
        this.bidRounds = 0;
        this.bidTicks = 0;
        this.grabCount = 0;
        this.declareTicks = 0;
        Arrays.fill(this.declared, false);
        Arrays.fill(this.revealed, false);
        Arrays.fill(this.doubled, false);
        Arrays.fill(this.bidValues, -1);
        Arrays.fill(this.grabbed, false);
        Arrays.fill(this.bidDecided, false);
        this.passCount = 0;
        this.lastCombo = null;
        this.lastPlaySeat = -1;
        this.bottomRevealed = false;
        prepareDeal();
        this.currentSeat = players.isEmpty() ? 0 : random.nextInt(players.size());
        if (!players.isEmpty()) {
            setCurrentPlayer(currentSeat);
        }
        table(Component.translatable("message.chartalandlords.dealing"));
        runGame();
    }

    /** 洗牌发牌（重新发牌时复用）。 */
    private void prepareDeal() {
        this.totalCards = gameDeck.size();
        this.bottomCount = DoudizhuValues.bottomCount(Math.max(1, players.size()));
        this.handSize = DoudizhuValues.handSize(totalCards, Math.max(1, players.size()));
        // 重新发牌时牌面状态必须复位：先把所有牌翻回背面
        for (Card card : gameDeck) {
            if (!card.flipped()) {
                card.flip();
            }
        }
        List<Card> shuffled = new ArrayList<>(gameDeck);
        Collections.shuffle(shuffled, RANDOM);
        this.stock = new GameSlot(shuffled);
        this.discard = new GameSlot(new LinkedList<>());
        this.bottomCards.setCards(new ArrayList<>());
        this.bottomView.setCards(new ArrayList<>());
        this.playArea.setCards(new ArrayList<>());
        this.bottomRevealed = false;
        this.tableLaidOut = false;
        // 计分状态按「局」重置（重新发牌也算新的一局）
        this.nukeCount = 0;
        this.landlordPlays = 0;
        this.farmersPlays = 0;
        this.settlement = null;
        this.hintCursor.clear();
        this.hintContext.clear();
        this.hintTotal.clear();
        this.revealedBottomCards.clear();
        this.revealedBottomValues = new int[0];
        // 重新发牌 = 新的一局：抢地主 / 明牌 / 加倍全部归零。
        // 手牌顺序也一并复位——手动牌序是「这一手牌」的整理结果，重新发牌后没有意义了。
        this.grabCount = 0;
        this.declareTicks = 0;
        Arrays.fill(this.declared, false);
        Arrays.fill(this.revealed, false);
        Arrays.fill(this.doubled, false);
        Arrays.fill(this.bidValues, -1);
        Arrays.fill(this.grabbed, false);
        Arrays.fill(this.bidDecided, false);
        Arrays.fill(this.manualOrder, false);
        this.hotkeyHintSent = false;
        refreshPlayViews();
        for (CardPlayer player : players) {
            getPlayerHand(player).clear();
            getCensoredHand(player).clear();
            getSelection(player).setCards(new ArrayList<>());
            player.resetPlay();
            refreshHandViews(player);
            refreshSelectionViews(player);
        }
        this.bidTicks = 0;
        int startSeat = players.isEmpty() ? 0 : RANDOM.nextInt(players.size());
        for (int i = 0; i < handSize; i++) {
            for (int step = 0; step < players.size(); step++) {
                CardPlayer player = players.get((startSeat + step) % players.size());
                scheduledActions.add(() -> {
                    player.playSound(ModSounds.CARD_DRAW.get());
                    dealOneRandomCard(player);
                });
                scheduledActions.add(() -> {});
            }
        }
        scheduledActions.add(() -> {
            for (CardPlayer player : players) {
                sortHand(player);
                syncCensoredHand(player);
                refreshHandViews(player);
            }
        });
        scheduledActions.add(() -> {
            for (int i = 0; i < bottomCount; i++) {
                if (!this.stock.isEmpty()) {
                    this.bottomCards.addLast(this.stock.remove(RANDOM.nextInt(this.stock.size())));
                }
            }
            mirrorBottomCardsToView();
        });
    }

    /** 从牌堆里随机抽一张发给玩家（发牌位置随机，避免「永远从牌尾抓牌」的机械感）。 */
    private void dealOneRandomCard(CardPlayer player) {
        if (this.stock.isEmpty()) {
            return;
        }
        Card card = this.stock.remove(RANDOM.nextInt(this.stock.size()));
        card.flip();
        getPlayerHand(player).add(card);
        // 牌桌上的背面扇形全程维护（1.7.0 起不再中途收走，见 layoutTableSlots）
        getCensoredHand(player).add(new Card());
    }

    /** 把当前底牌（未翻开时是背面）镜像一份到界面专用槽。 */
    private void mirrorBottomCardsToView() {
        List<Card> mirror = new ArrayList<>();
        bottomCards.forEach(card -> mirror.add(card.copy()));
        bottomView.setCards(mirror);
    }

    @Override
    public void runGame() {
        if (!isGameReady || isGameOver || players.isEmpty()) {
            return;
        }
        switch (phase) {
            case BIDDING -> armBid();
            case DECLARING -> armDeclare();
            case PLAYING -> armTurn();
            default -> {}
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!isGameReady || isGameOver || players.isEmpty()) {
            return;
        }
        if (!tableLaidOut) {
            // 牌桌是在 game.startGame() 之后才把各座位的扇形摆好的，所以第一次 tick 才轮到我们调整
            tableLaidOut = true;
            layoutTableSlots();
        }
        if (phase == Phase.BIDDING) {
            tickBidding();
        } else if (phase == Phase.DECLARING) {
            tickDeclaring();
        }
    }

    /** 叫分 / 抢地主的超时与 AI 决策。 */
    private void tickBidding() {
        CardPlayer bidder = players.get(currentSeat);
        bidTicks++;
        if (bidder.shouldCompute()) {
            if (bidTicks >= aiBidDelay) {
                if (isGrabMode()) {
                    // 抢地主：门槛随「已经有多少人抢过」抬高，自己是最后一个决定的人时降低
                    boolean lastToDecide = bidsMade >= players.size() - 1;
                    boolean grab = DoudizhuAi.chooseGrab(handOf(bidder), grabCount, lastToDecide, aiProfile());
                    applyBid(bidder, grab ? 1 : 0);
                } else {
                    applyBid(bidder, DoudizhuAi.chooseBid(handOf(bidder), highestBid, aiProfile()));
                }
            }
        } else if (bidTicks >= DoudizhuValues.BID_TIMEOUT_TICKS) {
            table(Component.translatable("message.chartalandlords.bid_timeout", bidder.getColoredName()));
            applyBid(bidder, 0);
        }
    }

    /**
     * 明牌 / 加倍阶段：真人等点按钮，AI 各带一点随机延迟自己决定。
     *
     * <p>阶段不会无限等：{@link #DECLARE_WINDOW_TICKS} 到了就把还没决定的人按「跳过」处理。
     * 这么设计是因为这一阶段没有「轮到谁」，只要有一个人挂机，逐位等待就会把整桌卡死。</p>
     */
    private void tickDeclaring() {
        declareTicks++;
        for (int seat = 0; seat < players.size(); seat++) {
            if (declared[seat]) {
                continue;
            }
            CardPlayer player = players.get(seat);
            if (!player.shouldCompute()) {
                continue;
            }
            if (declareTicks >= aiDeclareDelay + seat * 4) {
                aiDeclare(player);
            }
        }
        if (declareTicks >= DECLARE_WINDOW_TICKS || allDeclared()) {
            finishDeclaring();
        }
    }

    /**
     * AI 的明牌 / 加倍决定：两个开关各自独立判断，够强就都打开（个人倍数 ×4）。
     *
     * <p>门槛走「相对控场分基线」的口径（见 {@code AiPolicy.strengthBaseline}）。
     * 以前这两个门槛写的是绝对值（15 / 9），在 4 人局 25 张的牌里几乎人人都够——因为控场分的
     * 绝对值和手牌张数强相关，4 人局的均值就有 12。改成相对基线之后，两种人数下的触发概率才一致，
     * 而且三个难度档位会真正表现出「保守很少亮牌、激进动不动就加倍」的差别。</p>
     *
     * <p>按下即生效，所以这里先把两个开关按需要打开，最后再确认。</p>
     */
    private void aiDeclare(CardPlayer player) {
        AiProfile profile = aiProfile();
        int margin = DoudizhuAi.strengthMargin(handOf(player));
        if (margin >= profile.revealMargin()) {
            applyDeclare(player, DoudizhuAction.REVEAL_HAND);
        }
        if (margin >= profile.doubleMargin()) {
            applyDeclare(player, DoudizhuAction.DOUBLE_UP);
        }
        applyDeclare(player, DoudizhuAction.DECLARE_PASS);
    }

    private boolean allDeclared() {
        for (int seat = 0; seat < players.size(); seat++) {
            if (!declared[seat]) {
                return false;
            }
        }
        return true;
    }

    /** 进入明牌 / 加倍阶段（地主已经拿到并整理好底牌）。 */
    private void armDeclare() {
        if (players.isEmpty()) {
            return;
        }
        declareTicks = 0;
        aiDeclareDelay = AI_DECLARE_MIN_DELAY + random.nextInt(AI_DECLARE_EXTRA_DELAY);
        setCurrentPlayer(landlordSeat);
        markTurn(landlordSeat);
        table(Component.translatable("message.chartalandlords.declare_turn"));
        for (CardPlayer player : players) {
            player.sendMessage(Component.translatable("message.chartalandlords.declare_prompt"));
        }
    }

    private void finishDeclaring() {
        if (phase != Phase.DECLARING) {
            return;
        }
        phase = Phase.PLAYING;
        currentSeat = landlordSeat;
        passCount = 0;
        lastCombo = null;
        lastPlaySeat = -1;
        playArea.setCards(new ArrayList<>());
        refreshPlayViews();
        // 明牌者「看不见的手牌」已经换成正面副本，这里补一次广播让所有人看到
        for (CardPlayer player : players) {
            syncCensoredHand(player);
            refreshHandViews(player);
        }
        runGame();
    }

    @Override
    public void endGame() {
        if (isGameOver) {
            return;
        }
        phase = Phase.OVER;
        isGameOver = true;
        // 牌局结束了就没有「轮到谁」：把标记撤掉，桌上的牌回到常态位置
        markTurn(-1);
        if (winnerSeat < 0 || landlordSeat < 0 || players.isEmpty()) {
            table(Component.translatable("message.chartalandlords.game_interrupted").withStyle(ChatFormatting.RED));
            return;
        }
        boolean landlordWon = winnerSeat == landlordSeat;
        table(Component.translatable(landlordWon ? "message.chartalandlords.landlord_won" : "message.chartalandlords.farmers_won")
                .withStyle(landlordWon ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        settleScore(landlordWon);
        for (int seat = 0; seat < players.size(); seat++) {
            CardPlayer player = players.get(seat);
            boolean won = (seat == landlordSeat) == landlordWon;
            player.sendTitle(
                    Component.translatable(won ? "message.chartalandlords.you_won" : "message.chartalandlords.you_lost")
                            .withStyle(won ? ChatFormatting.GREEN : ChatFormatting.RED),
                    Component.translatable("message.chartalandlords.winner_is", players.get(winnerSeat).getColoredName()));
        }
    }

    /**
     * 结算并播报：底分 = 叫分，每个炸弹/王炸翻一倍，抢地主每多一个人抢翻一倍，
     * 春天与反春天各再翻一倍；明牌 / 加倍是<b>个人倍数</b>，只放大该玩家与地主之间的那一对。
     * 地主与每个农民逐家结算（零和）。关掉计分选项时只报「春天 / 反春天」，不动分数。
     */
    private void settleScore(boolean landlordWon) {
        int[] seatDoublings = new int[players.size()];
        int declaredSeats = 0;
        for (int seat = 0; seat < players.size(); seat++) {
            // 明牌 +1、加倍 +1：两个都选就是个人倍数 ×4
            int exponent = personalDoublings(seat);
            seatDoublings[seat] = exponent;
            if (exponent > 0) {
                declaredSeats++;
            }
        }
        DoudizhuScore.Settlement result = DoudizhuScore.settle(highestBid, players.size(), landlordSeat,
                landlordWon, nukeCount, landlordPlays, farmersPlays, grabDoublings(), seatDoublings);
        this.settlement = result;
        if (result.spring()) {
            table(Component.translatable("message.chartalandlords.spring", players.get(landlordSeat).getColoredName())
                    .withStyle(ChatFormatting.GOLD));
        } else if (result.antiSpring()) {
            table(Component.translatable("message.chartalandlords.anti_spring")
                    .withStyle(ChatFormatting.AQUA));
        }
        if (!isScoring()) {
            return;
        }
        TableScores table = scoreboardForWrite();
        table.rounds++;
        for (int seat = 0; seat < players.size(); seat++) {
            table.scores.merge(scoreboardPlayerKey(players.get(seat)), result.delta(seat), Integer::sum);
        }
        table(Component.translatable("message.chartalandlords.score_line", result.baseBid(), nukeCount,
                grabCount, result.multiplier()).withStyle(ChatFormatting.GRAY));
        if (declaredSeats > 0) {
            table(Component.translatable("message.chartalandlords.score_declares", declaredSeats)
                    .withStyle(ChatFormatting.GRAY));
        }
        for (int seat = 0; seat < players.size(); seat++) {
            int delta = result.delta(seat);
            table(Component.translatable("message.chartalandlords.score_seat", players.get(seat).getColoredName(),
                            Component.literal((delta > 0 ? "+" : "") + delta).withStyle(
                                    delta > 0 ? ChatFormatting.GREEN : delta < 0 ? ChatFormatting.RED : ChatFormatting.GRAY),
                            totalScore(seat))
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean canPlay(CardPlayer player, GamePlay play) {
        if (!isGameReady || isGameOver || phase != Phase.PLAYING || players.isEmpty()) {
            return false;
        }
        if (players.get(currentSeat) != player) {
            return false;
        }
        if (play == null || play.cards() == null || play.cards().isEmpty()) {
            return lastCombo != null;
        }
        Combo combo = DoudizhuRules.classify(play.cards(), isFourPlayer());
        return combo != null && DoudizhuRules.beats(combo, lastCombo);
    }

    @Override
    public GamePlay getBestPlay(CardPlayer player) {
        if (!isGameReady || isGameOver || phase != Phase.PLAYING || players.isEmpty()) {
            return null;
        }
        if (players.get(currentSeat) != player) {
            return null;
        }
        List<Card> hand = handOf(player);
        if (hand.isEmpty()) {
            return null;
        }
        boolean teammateLed = lastCombo != null && lastPlaySeat >= 0 && isTeammate(currentSeat, lastPlaySeat);
        List<Card> choice = DoudizhuAi.choosePlay(hand, contextFor(currentSeat, teammateLed), ruleOptions(),
                aiProfile());
        return choice == null ? null : new GamePlay(choice, PLAY_SLOT);
    }

    // ------------------------------------------------------------------ 叫分

    private void armBid() {
        if (players.isEmpty()) {
            return;
        }
        bidTicks = 0;
        aiBidDelay = AI_BID_MIN_DELAY + random.nextInt(AI_BID_EXTRA_DELAY);
        CardPlayer bidder = players.get(currentSeat);
        setCurrentPlayer(currentSeat);
        markTurn(currentSeat);
        if (isGrabMode()) {
            table(Component.translatable("message.chartalandlords.grab_turn", bidder.getColoredName(), grabCount));
            if (!bidder.shouldCompute()) {
                bidder.sendMessage(Component.translatable("message.chartalandlords.your_grab_turn"));
            }
            return;
        }
        table(Component.translatable("message.chartalandlords.bid_turn", bidder.getColoredName(), highestBid));
        if (!bidder.shouldCompute()) {
            bidder.sendMessage(Component.translatable("message.chartalandlords.your_bid_turn"));
        }
    }

    /**
     * 叫分：1..3 分，0 表示不叫。
     *
     * <p>抢地主模式下同一个入口的含义变成「抢 / 不抢」：任何大于 0 的分数都算一次抢，
     * 底分固定 1，倍数由抢的人数决定。两种模式共用入口是为了<b>不必新增网络动作</b>——
     * 界面在抢地主模式下只显示「抢地主 / 不抢」两颗按钮，客户端发的还是同样的动作 id。</p>
     */
    public void bid(CardPlayer player, int score) {
        if (phase != Phase.BIDDING || isGameOver || players.isEmpty() || players.get(currentSeat) != player) {
            return;
        }
        applyBid(player, score);
    }

    private void applyBid(CardPlayer player, int score) {
        if (phase != Phase.BIDDING || isGameOver) {
            return;
        }
        if (isGrabMode()) {
            applyGrab(player, score > 0);
            return;
        }
        int value = Math.max(0, Math.min(DoudizhuValues.MAX_BID, score));
        if (value != 0 && value <= highestBid) {
            value = 0;
        }
        bidValues[currentSeat] = value;
        bidDecided[currentSeat] = true;
        if (value > 0) {
            highestBid = value;
            highestBidSeat = currentSeat;
            table(Component.translatable("message.chartalandlords.bid", player.getColoredName(), value)
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            table(Component.translatable("message.chartalandlords.bid_pass", player.getColoredName()));
        }
        bidsMade++;
        if (highestBid >= DoudizhuValues.MAX_BID || bidsMade >= players.size()) {
            finishBidding();
            return;
        }
        currentSeat = (currentSeat + 1) % players.size();
        armBid();
    }

    /**
     * 抢地主模式的一次决定。
     *
     * <p>规则（本模组采用的简化版，写进 README）：一圈之内每人各决定一次「抢 / 不抢」；
     * 每次抢都把倍数翻一倍，<b>最后一个抢的人</b>当地主。一个人都没有抢就跟叫分模式全不叫一样，
     * 先重新发牌、连续 {@link #MAX_BID_ROUNDS} 轮还没人抢就强制手牌最强者当地主。</p>
     *
     * <p>「第一个抢的人不翻倍」——他相当于普通叫地主，底分已经是 1，再翻倍就成了白送 2 倍。
     * 所以局倍数指数是 {@code max(0, 抢的人数 - 1)}。</p>
     */
    private void applyGrab(CardPlayer player, boolean grab) {
        grabbed[currentSeat] = grab;
        bidDecided[currentSeat] = true;
        if (grab) {
            grabCount++;
            highestBid = 1;
            highestBidSeat = currentSeat;
            table(Component.translatable("message.chartalandlords.grab", player.getColoredName(), grabCount)
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            table(Component.translatable("message.chartalandlords.grab_pass", player.getColoredName()));
        }
        bidsMade++;
        if (bidsMade >= players.size()) {
            finishBidding();
            return;
        }
        currentSeat = (currentSeat + 1) % players.size();
        armBid();
    }

    /** 抢地主带来的局倍数指数：抢 n 次记 {@code n - 1}（见 {@link #applyGrab}）。 */
    public int grabDoublings() {
        return isGrabMode() ? Math.max(0, grabCount - 1) : 0;
    }

    /** 本局已经发生的抢次数（界面与日志用）。 */
    public int grabCount() {
        return grabCount;
    }

    // ------------------------------------------------------------------ 明牌 / 加倍

    /**
     * 明牌 / 加倍阶段的一次操作。
     *
     * <p>1.5.0 起这一阶段是「两个开关 + 确认」，所以同一个座位可以发多次：</p>
     * <ul>
     *   <li>{@link DoudizhuAction#REVEAL_HAND}：打开明牌。<b>单向</b>——牌已经摊在别人眼前了，
     *       不允许撤回（否则就成了「先亮牌吓人再收回去」）；</li>
     *   <li>{@link DoudizhuAction#DOUBLE_UP}：切换加倍，可以反复来回；</li>
     *   <li>{@link DoudizhuAction#DECLARE_PASS}：结束我的决定（两个开关都是按下即生效，所以它不带内容）。</li>
     * </ul>
     *
     * <p>两个开关都打开时个人倍数是 ×4，这正是 {@code DoudizhuScore.settle} 里
     * {@code seatDoublings} 指数为 2 的情形。</p>
     */
    public void declare(CardPlayer player, DoudizhuAction choice) {
        applyDeclare(player, choice);
    }

    private void applyDeclare(CardPlayer player, DoudizhuAction choice) {
        if (phase != Phase.DECLARING || isGameOver || player == null) {
            return;
        }
        int seat = seatOf(player);
        if (seat < 0 || seat >= players.size() || declared[seat]) {
            // 已经确认过的座位不再接受任何操作
            return;
        }
        switch (choice) {
            case REVEAL_HAND -> {
                if (revealed[seat]) {
                    // 明牌不可撤销：牌已经公开过了，再按一次就是空操作
                    return;
                }
                revealed[seat] = true;
                syncCensoredHand(player);
                refreshHandViews(player);
                table(Component.translatable("message.chartalandlords.declare_reveal", player.getColoredName(),
                        personalFactor(seat)).withStyle(ChatFormatting.GOLD));
                // 再单独发一条「明牌明细」：界面里那一条可能因为窗口太矮画不下，
                // 聊天栏与历史记录这一份一定看得见，而且不会随界面滚掉。
                table(Component.translatable("message.chartalandlords.reveal_cards", player.getColoredName(),
                        cardsComponent(getPlayerHand(player))).withStyle(ChatFormatting.GOLD));
                player.playSound(ModSounds.CARD_PLAY.get());
            }
            case DOUBLE_UP -> {
                doubled[seat] = !doubled[seat];
                table(Component.translatable(doubled[seat]
                                ? "message.chartalandlords.declare_double"
                                : "message.chartalandlords.declare_undouble",
                        player.getColoredName(), personalFactor(seat)).withStyle(ChatFormatting.YELLOW));
            }
            case DECLARE_PASS -> {
                declared[seat] = true;
                table(Component.translatable("message.chartalandlords.declare_done", player.getColoredName(),
                        personalFactor(seat)).withStyle(ChatFormatting.GRAY));
            }
            default -> {}
        }
        if (allDeclared()) {
            finishDeclaring();
        }
    }

    /**
     * 某个座位当前的<b>个人倍数</b>：明牌与加倍各翻一倍，所以可能是 1 / 2 / 4。
     *
     * <p>这是「按下即生效」的直接后果——玩家在确认之前就能看到自己现在押了多少，
     * 而不是确认之后才知道。</p>
     */
    public int personalFactor(int seat) {
        if (seat < 0 || seat >= players.size()) {
            return 1;
        }
        return 1 << ((revealed[seat] ? 1 : 0) + (doubled[seat] ? 1 : 0));
    }

    /** 某个座位的个人倍数指数（0..2），界面与结算都用它。 */
    public int personalDoublings(int seat) {
        if (seat < 0 || seat >= players.size()) {
            return 0;
        }
        return (revealed[seat] ? 1 : 0) + (doubled[seat] ? 1 : 0);
    }

    /** 某个座位是否已经结束明牌 / 加倍阶段里的决定。 */
    public boolean isDeclared(int seat) {
        return seat >= 0 && seat < players.size() && declared[seat];
    }

    /** 某个座位是否还可以操作明牌 / 加倍开关。 */
    public boolean canDeclare(int seat) {
        return phase == Phase.DECLARING && !isGameOver && seat >= 0 && seat < players.size() && !declared[seat];
    }

    /** 某个座位现在还能不能按「明牌」（已经明牌过的座位不能撤回）。 */
    public boolean canReveal(int seat) {
        return canDeclare(seat) && !revealed[seat];
    }

    /**
     * 座位标记位：bit0 = 明牌、bit1 = 加倍、bit2 = 明牌/加倍已确认、bit3 = 已经抢过地主、
     * bit4 = 叫分 / 抢地主阶段已经表过态。
     *
     * <p>界面与数据槽共用这一套位。叫分的具体分值走 {@code DATA_SEAT_BID_START}，
     * 因为「抢了没有」是一个布尔、而「叫了几分」是一个数，硬塞进位里只会让两边都难读。</p>
     */
    public int seatMarks(int seat) {
        if (seat < 0 || seat >= players.size()) {
            return 0;
        }
        int marks = 0;
        if (revealed[seat]) {
            marks |= 1;
        }
        if (doubled[seat]) {
            marks |= 2;
        }
        if (declared[seat]) {
            marks |= 4;
        }
        if (grabbed[seat]) {
            marks |= 8;
        }
        if (bidDecided[seat]) {
            marks |= 16;
        }
        return marks;
    }

    /** 某个座位在叫分模式下叫了几分（-1 = 还没表态，0 = 不叫，1..3 = 分值）。 */
    public int bidValue(int seat) {
        return seat >= 0 && seat < players.size() ? bidValues[seat] : -1;
    }

    /** 某个座位在抢地主模式下是否抢过。 */
    public boolean hasGrabbed(int seat) {
        return seat >= 0 && seat < players.size() && grabbed[seat];
    }

    /** 某个座位在叫分 / 抢地主阶段是否已经表过态。 */
    public boolean hasBidDecided(int seat) {
        return seat >= 0 && seat < players.size() && bidDecided[seat];
    }

    /**
     * 当前「谁最可能当地主」：抢地主模式下是最后一个抢的人，叫分模式下是当前最高分的持有者。
     *
     * <p>叫分 / 抢地主还没结束之前 {@link #landlordSeat()} 还是 -1，界面就只能看到「已抢 N 次」，
     * 根本不知道现在是谁的。这个查询把那个信息补上。</p>
     *
     * @return 座位号；还没有人表态时返回 -1
     */
    public int leadingSeat() {
        return highestBidSeat;
    }

    /** 某个座位是否已经明牌（手牌对所有人可见）。 */
    public boolean isRevealed(int seat) {
        return seat >= 0 && seat < players.size() && revealed[seat];
    }

    /** 某个座位是否已经加倍。 */
    public boolean isDoubled(int seat) {
        return seat >= 0 && seat < players.size() && doubled[seat];
    }

    /** 明牌 / 加倍阶段还剩多少 tick（界面倒计时用）。 */
    public int declareTicksLeft() {
        return Math.max(0, DECLARE_WINDOW_TICKS - declareTicks);
    }

    /** 游戏规则里明牌 / 加倍阶段的窗口长度，供界面画倒计时（与 {@link #declareTicksLeft()} 配对）。 */
    public static int declareWindowTicks() {
        return DECLARE_WINDOW_TICKS;
    }

    private void finishBidding() {
        bidRounds++;
        if (highestBidSeat < 0) {
            if (bidRounds >= MAX_BID_ROUNDS) {
                highestBidSeat = strongestSeat();
                highestBid = 1;
                table(Component.translatable("message.chartalandlords.bid_forced", players.get(highestBidSeat).getColoredName()));
            } else {
                table(Component.translatable("message.chartalandlords.bid_all_pass").withStyle(ChatFormatting.GRAY));
                highestBid = 0;
                bidsMade = 0;
                isGameReady = false;
                prepareDeal();
                runGame();
                return;
            }
        }
        landlordSeat = highestBidSeat;
        CardPlayer landlord = players.get(landlordSeat);
        revealBottomCards();
        sortHand(landlord);
        syncCensoredHand(landlord);
        refreshHandViews(landlord);
        // 底牌标记摆到地主面前：世界里「谁是地主」就靠这张牌
        placeLandlordMarker();
        if (isManualOrder(landlordSeat)) {
            // 手动牌序不能被底牌打乱：三张新牌统一接在手牌末尾（好找），要不要挪走由玩家决定
            landlord.sendMessage(Component.translatable("message.chartalandlords.bottom_appended"));
        }
        // 明牌 / 加倍阶段开着就先停在这里：底牌已经发到地主手里、手牌也整理好了，谁都能看见自己该不该亮牌
        phase = isDeclareEnabled() ? Phase.DECLARING : Phase.PLAYING;
        currentSeat = landlordSeat;
        passCount = 0;
        lastCombo = null;
        lastPlaySeat = -1;
        playArea.setCards(new ArrayList<>());
        refreshPlayViews();
        table(Component.translatable("message.chartalandlords.landlord_decided", landlord.getColoredName())
                .withStyle(ChatFormatting.GOLD));
        // 播报用**界面槽**（整副底牌的正面副本）：桌面槽现在只剩一张扣着的标记牌
        table(Component.translatable("message.chartalandlords.bottom_cards", cardsComponent(bottomView)));
        for (CardPlayer player : players) {
            player.sendMessage(Component.translatable("message.chartalandlords.landlord_is", landlord.getColoredName()));
        }
        runGame();
    }

    private int strongestSeat() {
        int best = 0;
        int bestStrength = Integer.MIN_VALUE;
        for (int seat = 0; seat < players.size(); seat++) {
            int strength = DoudizhuAi.strength(handOf(players.get(seat))) * 100 + random.nextInt(100);
            if (strength > bestStrength) {
                bestStrength = strength;
                best = seat;
            }
        }
        return best;
    }

    /**
     * 地主收底牌：真牌进地主手牌，界面的底牌预览留下正面副本，<b>桌面上只留一张扣着的牌当地主标记</b>。
     *
     * <p>底牌是公开信息，界面里（面板右上角的底牌预览 + 「看牌」页）一直查得到；但把 3 张（4 人局 8 张）
     * 正面牌摊在牌桌上，观战的人很难分辨那是底牌还是谁打出的牌，还会和出牌区抢那块地方
     * （玩家反馈过「底牌和出牌重合了」）。所以桌面上改成一张扣着的「地主牌」：
     * 一张牌背放在地主面前 = 这位是地主，信息量与「摊开底牌」等价，占地却小得多。</p>
     */
    private void revealBottomCards() {
        List<Card> bottom = new ArrayList<>();
        bottomCards.forEach(bottom::add);
        revealedBottomCards.clear();
        revealedBottomCards.addAll(bottom);
        revealedBottomValues = DoudizhuValues.valuesOf(bottom);
        List<Card> display = new ArrayList<>();
        for (Card card : bottomCards.getCards()) {
            Card copy = card.copy();
            copy.flip();
            display.add(copy);
        }
        // 真牌翻到正面进地主手牌
        CardPlayer landlord = players.get(landlordSeat);
        for (Card card : new ArrayList<>(bottomCards.stream().toList())) {
            card.flip();
            getPlayerHand(landlord).add(card);
            getCensoredHand(landlord).add(new Card());
        }
        // 界面：整副底牌的正面副本（底牌预览与「看牌」页读的就是它）
        bottomView.setCards(new ArrayList<>(display));
        // 桌面：只留一张扣着的牌当地主标记（牌背 = new Card() 的默认朝向）
        bottomCards.setCards(new ArrayList<>(List.of(new Card())));
        bottomRevealed = true;
    }

    // ------------------------------------------------------------------ 牌桌摆位（世界里的那张桌子）

    /** 把座位的扇形往椅子方向再推出去多少（0 = 就用框架摆好的位置）。 */
    private static final float FAN_PUSH_OUT = 0f;
    /**
     * 底牌与<b>同一名玩家的出牌区</b>之间必须留出的最小距离。
     *
     * <p>两者都摆在「地主面前」：底牌贴着他那侧，他出的牌在更靠桌心的位置。早先两边各自用一套
     * 独立数值（底牌固定缩进 84、出牌区偏扇形距离的 55%），扇形离桌心近的时候两条落点会撞在一起
     * ——玩家截图上就是「底牌和出牌重合了」。现在底牌的落点由<b>出牌区的落点减去这个间隔</b>得到，
     * 于是无论椅子远近都错得开。</p>
     */
    private static final float BOTTOM_PLAY_GAP = 80f;
    /** 底牌离地主扇形至少还有多远（别贴到扇形上；160 = 1 格）。 */
    private static final float BOTTOM_MIN_DRIFT = 0.12f;
    /** 轮到某个座位时，把它的扇形往桌心推出去多少（世界里的「轮到谁」提示）。 */
    private static final float FAN_TURN_PUSH = 30f;

    /**
     * 记下各座位扇形的<b>基准位置</b>（各家自己的牌槽里）。
     *
     * <p>框架已经把扇形摆在每个座位对应的牌槽上了，所以这里<b>不再动它</b>：早先版本又往外推了
     * 38 单位（0.24 格），结果只有「轮到谁」那一家看着像在牌槽里，其余三家都偏到桌外去了
     * （玩家反馈原话：「只有正在出牌的一方手牌在桌子的牌槽中，其他时候是偏向桌外的」）。
     * 现在 {@link #FAN_PUSH_OUT} = 0，四家都规规矩矩待在自己的槽位上。</p>
     *
     * <p>基准位置进 {@link #fanRest}，之后「轮到谁」的标记都从基准往桌心偏一点，
     * 所以这个方法跑一次就够了，跟标记的先后顺序无关。</p>
     */
    private void layoutTableSlots() {
        if (players.isEmpty()) {
            return;
        }
        for (CardPlayer player : players) {
            if (fanRest.containsKey(player)) {
                // 重新发牌会再走一遍这里：基准已经有了，别再往外推一次
                continue;
            }
            GameSlot fan = getCensoredHand(player);
            float dx = fan.getX() - TABLE_CENTER_X;
            float dy = fan.getY() - TABLE_CENTER_Y;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 1f) {
                continue;
            }
            fanRest.put(player, new float[]{
                    fan.getX() + dx / length * FAN_PUSH_OUT,
                    fan.getY() + dy / length * FAN_PUSH_OUT});
        }
        applyFanMarker();
    }

    /**
     * 标记「现在轮到哪个座位」。
     *
     * <p>世界里的那张桌子上没有地方写文字（一张牌才 0.156 格宽，任何悬浮字都比牌还大，
     * 视角一动就糊），所以改用<b>牌本身的位置</b>说话：轮到谁，谁那圈牌就往桌心推出来一点；
     * 换人时上一家自动退回。客户端的位置是插值的，所以看上去是滑过去而不是瞬移。</p>
     */
    private void markTurn(int seat) {
        if (markedFanSeat == seat) {
            return;
        }
        markedFanSeat = seat;
        applyFanMarker();
    }

    /** 按 {@link #markedFanSeat} 把各座位的扇形摆到「基准 ± 标记偏移」。 */
    private void applyFanMarker() {
        if (!tableLaidOut) {
            // 牌桌还没摆位：基准要等 layoutTableSlots 把扇形往外推完才成立。
            // 现在就偏移会把这个「还没推出去」的位置记成基准，于是那一刻的标记会被
            // layoutTableSlots 当成「基准已存在」而跳过，扇形最后少推一截。
            // 标记本身已经记在 markedFanSeat 里，摆位那一步会重新应用一次。
            return;
        }
        for (int seat = 0; seat < players.size(); seat++) {
            CardPlayer player = players.get(seat);
            float[] rest = fanRest.get(player);
            if (rest == null) {
                // 没有基准（理论上不会发生）就不动它，宁可少一个提示也不要把牌桌摆错
                continue;
            }
            GameSlot fan = getCensoredHand(player);
            float x = rest[0];
            float y = rest[1];
            if (seat == markedFanSeat) {
                float dx = TABLE_CENTER_X - x;
                float dy = TABLE_CENTER_Y - y;
                float length = (float) Math.sqrt(dx * dx + dy * dy);
                if (length >= 1f) {
                    x += dx / length * FAN_TURN_PUSH;
                    y += dy / length * FAN_TURN_PUSH;
                }
            }
            // 位置没变就别动槽位：setX/setY 会把槽标记成脏的，白跑一趟网络同步
            if (Math.abs(fan.getX() - x) > 0.01f || Math.abs(fan.getY() - y) > 0.01f) {
                fan.setX(x);
                fan.setY(y);
            }
        }
    }

    /**
     * 把<b>地主标记</b>（桌面槽里那一张扣着的牌）摆到地主面前。
     *
     * <p>世界里不摊开底牌（3 人局 3 张、4 人局 8 张的正面牌会跟出牌区抢地方），只留一张牌背：
     * 一张扣着的牌摆在地主面前 = 这位是地主，信息量等价，占地小得多。所以这个方法只负责摆位与朝向。</p>
     *
     * <p>用真实槽位坐标算方向，不猜椅子几何：沿「地主扇形 → 桌心」这条线，落点取
     * <b>他自己出牌区落点再往地主这边退 {@link #BOTTOM_PLAY_GAP}</b>（至少退到
     * {@link #BOTTOM_MIN_DRIFT}）。这样标记永远夹在「地主的扇形」与「地主打出的牌」之间，
     * 两者不会重合成一堆——玩家截图上那个「底牌和出牌重合了」就是这么来的。</p>
     *
     * <p>基准位置优先取 {@link #fanRest}：地主自己也可能正好是「轮到他」而被推出去的那一家，
     * 标记不该跟着一起前后晃。</p>
     */
    private void placeLandlordMarker() {
        if (landlordSeat < 0 || landlordSeat >= players.size()) {
            return;
        }
        CardPlayer landlord = players.get(landlordSeat);
        GameSlot fan = getCensoredHand(landlord);
        float[] rest = fanRest.get(landlord);
        float fanX = rest == null ? fan.getX() : rest[0];
        float fanY = rest == null ? fan.getY() : rest[1];
        float dx = TABLE_CENTER_X - fanX;
        float dy = TABLE_CENTER_Y - fanY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) {
            return;
        }
        // 出牌区的落点在这根线上、离扇形 length*(1-PLAY_DRIFT)；标记退到它后面 BOTTOM_PLAY_GAP。
        // 注意两个距离的**参照系**：出牌区的偏移是从桌心量的，这里要的是「离扇形多远」。
        float playFromFan = length * (1f - PLAY_DRIFT);
        float setback = Math.max(length * BOTTOM_MIN_DRIFT, playFromFan - BOTTOM_PLAY_GAP);
        bottomCards.setX(fanX + dx / length * setback);
        bottomCards.setY(fanY + dy / length * setback);
        // 标记牌**横放**（比地主的扇形多转 90°）：一张牌背单独横在他面前，和扇形/出牌区的
        // 朝向都不一样，一眼就能看出「这是标记，不是某人打出的牌」。
        // 单张牌没有堆叠偏移，所以转 90° 不会改变它自己的位置，也不会和出牌区抢地方。
        bottomCards.setAngle(fan.getAngle() + 90f);
        bottomCards.setStackDirection(fan.getStackDirection());
    }

    /**
     * 第一轮出牌结束。
     *
     * <p>1.7.0 起这里<b>什么都不做</b>：以前会把各座位的扇形与桌面底牌一起收走，
     * 于是牌局进行到一半时桌面上只剩一张上一手牌，看上去像没人打牌。
     * 现在扇形往外推到椅子那侧（见 {@link #layoutTableSlots()}）不再挡桌面，所以整局都留着，
     * 随时能看出每家还剩几张；底牌也留在桌上（公开信息，同时是地主的标记）。</p>
     */
    private void onFirstRoundEnded() {
        // 保留扇形与底牌：见方法注释。留一个显式的空实现，免得以后有人以为这里漏了清理。
    }

    // ------------------------------------------------------------------ 出牌

    private void armTurn() {
        if (players.isEmpty() || isGameOver) {
            return;
        }
        CardPlayer actor = players.get(currentSeat);
        setCurrentPlayer(currentSeat);
        markTurn(currentSeat);
        actor.resetPlay();
        actor.afterPlay(play -> handlePlay(actor, play));
        if (!hotkeyHintSent) {
            // 每局只提一次：告诉人类玩家有回车 / 空格和拖拽排序，不用去翻说明
            hotkeyHintSent = true;
            for (CardPlayer player : players) {
                if (!player.shouldCompute()) {
                    player.sendMessage(Component.translatable("message.chartalandlords.hotkeys"));
                }
            }
        }
        table(Component.translatable("message.chartalandlords.turn", actor.getColoredName(),
                getPlayerHand(actor).size()));
        if (!actor.shouldCompute()) {
            actor.sendMessage(Component.translatable("message.chartalandlords.your_turn"));
        }
    }

    /** 出牌 / 不出：由玩家（或 AI）通过 play(...) 送达。 */
    private void handlePlay(CardPlayer player, GamePlay play) {
        if (isGameOver || phase != Phase.PLAYING || players.isEmpty() || players.get(currentSeat) != player) {
            return;
        }
        if (play == null || play.cards() == null || play.cards().isEmpty()) {
            handlePass(player);
            return;
        }
        List<Card> cards = new ArrayList<>(play.cards());
        Combo combo = DoudizhuRules.classify(cards, isFourPlayer());
        if (combo == null || !DoudizhuRules.beats(combo, lastCombo)) {
            if (player.shouldCompute()) {
                // 机器座位不该出非法牌：这一定是规则/AI 的 bug，留一条日志便于回溯
                Doudizhu.LOGGER.warn("Rejected illegal play from {}: {} vs previous {}",
                        player.getName().getString(), cardsComponent(cards).getString(),
                        lastCombo == null ? "<lead>" : lastCombo.toString());
                // 机器座位把牌退回去，否则它会抱着这手非法牌反复重试
                returnSelectionToHand(player);
            }
            table(Component.translatable("message.chartalandlords.invalid_play", player.getColoredName())
                    .withStyle(ChatFormatting.RED));
            player.sendMessage(Component.translatable("message.chartalandlords.invalid_play_hint"));
            // 人类玩家**保留**当前选牌：出错了还能少点一张直接改，不用从头再选一遍。
            // （以前这里会把选牌全部退回手牌，是「出牌手感」最差的一处。）
            // 但无论谁都必须重新武装 play 回调——Charta 的 play future 是一次性的，
            // 不重新武装这个座位就再也出不了牌了。
            player.resetPlay();
            player.afterPlay(next -> handlePlay(player, next));
            return;
        }
        removePlayedCards(player, cards);
        if (getPlayerHand(player).isEmpty()) {
            winnerSeat = currentSeat;
        }
        // 先记下出牌者，再摆牌：上一手牌要往他那一侧偏（见 placeLastPlayForSeat）
        lastPlaySeat = currentSeat;
        pushPlayArea(cards);
        playRevision++;
        lastCombo = combo;
        passCount = 0;
        // 计分统计：炸弹/王炸翻倍，并按身份记出牌手数（春天 / 反春天判定）
        if (combo.isNuke()) {
            nukeCount++;
        }
        if (currentSeat == landlordSeat) {
            landlordPlays++;
        } else {
            farmersPlays++;
        }
        player.playSound(ModSounds.CARD_PLAY.get());
        table(Component.translatable("message.chartalandlords.played", player.getColoredName(), cardsComponent(playArea),
                Component.translatable(combo.type().descriptionKey())));
        // 出牌之后**不重排**手牌：牌只是少了几张，位置全部保持不动，下一手还能照着记忆点。
        syncCensoredHand(player);
        refreshHandViews(player);
        refreshSelectionViews(player);
        if (winnerSeat >= 0) {
            endGame();
            return;
        }
        nextTurn();
    }

    private void handlePass(CardPlayer player) {
        if (lastCombo == null) {
            // 首出不能不出：用最小牌型代替（人类超时/误触也不至于卡死）
            List<Card> lead = DoudizhuAi.chooseLead(handOf(player), ruleOptions(), null, aiProfile());
            if (lead != null && !lead.isEmpty()) {
                player.play(new GamePlay(lead, PLAY_SLOT));
            }
            return;
        }
        table(Component.translatable("message.chartalandlords.passed", player.getColoredName()));
        passCount++;
        returnSelectionToHand(player);
        if (passCount >= players.size() - 1) {
            // 其余人都不出：上一手牌收走，由最后出牌者重新领出
            int leader = lastPlaySeat;
            lastCombo = null;
            lastPlaySeat = -1;
            passCount = 0;
            pushPlayArea(new ArrayList<>());
            onFirstRoundEnded();
            table(Component.translatable("message.chartalandlords.new_round"));
            if (leader >= 0) {
                currentSeat = leader;
            }
            armTurn();
            return;
        }
        nextTurn();
    }

    private void nextTurn() {
        returnSelectionToHand(players.get(currentSeat));
        currentSeat = (currentSeat + 1) % players.size();
        armTurn();
    }

    // ------------------------------------------------------------------ 玩家操作

    /** 手牌 → 出牌区。参数是「段」（0-3 = 第一行左/右、第二行左/右）。 */
    public void selectFromHand(CardPlayer player, int segment, int indexInSegment) {
        if (!isSelectionAllowed(player)) {
            return;
        }
        GameSlot hand = getPlayerHand(player);
        int index = segment * SEGMENT_CAPACITY + indexInSegment;
        if (index < 0 || index >= hand.size()) {
            return;
        }
        Card card = hand.remove(index);
        getSelection(player).addLast(card);
        selectionRevision++;
        afterSelectionChanged(player);
    }

    /** 出牌区 → 手牌（参数是选择区的行）。 */
    public void deselectToHand(CardPlayer player, int row, int indexInRow) {
        if (phase != Phase.PLAYING || isGameOver) {
            return;
        }
        GameSlot selection = getSelection(player);
        int index = row * ROW_CAPACITY + indexInRow;
        if (index < 0 || index >= selection.size()) {
            return;
        }
        Card card = selection.remove(index);
        getPlayerHand(player).addLast(card);
        selectionRevision++;
        afterSelectionChanged(player);
    }

    /** 右键撤回：把出牌区里选中的牌全部退回手牌。 */
    public void retractSelection(CardPlayer player) {
        if (isGameOver || phase != Phase.PLAYING || getSelection(player).isEmpty()) {
            return;
        }
        returnSelectionToHand(player);
        selectionRevision++;
    }

    /** 出牌。 */
    public void playSelection(CardPlayer player) {
        if (!isSelectionAllowed(player)) {
            return;
        }
        GameSlot selection = getSelection(player);
        if (selection.isEmpty()) {
            player.sendMessage(Component.translatable("message.chartalandlords.select_first"));
            return;
        }
        List<Card> cards = new ArrayList<>();
        selection.forEach(cards::add);
        player.play(new GamePlay(cards, PLAY_SLOT));
    }

    /** 不出。 */
    public void passTurn(CardPlayer player) {
        if (!isSelectionAllowed(player)) {
            return;
        }
        returnSelectionToHand(player);
        player.play(null);
    }

    /** 提示：把最小可压牌型选进出牌区；再按一次轮换到下一个候选。 */
    public void hint(CardPlayer player) {
        if (!isSelectionAllowed(player)) {
            return;
        }
        List<Card> hand = handOf(player);
        RuleOptions options = ruleOptions();
        List<Card> choice;
        if (lastCombo == null) {
            // 首出没有「轮换」的概念，直接给 AI 认为最好的一手
            clearHintCursor(player);
            choice = DoudizhuAi.chooseLead(hand, options, null, aiProfile());
        } else {
            List<int[]> candidates = RuleEngine.hints(DoudizhuValues.sortedValues(hand), lastCombo, options);
            if (candidates.isEmpty()) {
                clearHintCursor(player);
                player.sendMessage(Component.translatable("message.chartalandlords.hint_none"));
                return;
            }
            // 连按「提示」在候选之间轮换（廉价 → 昂贵）；手动改过选牌或上一手变了就回到第一个
            int context = hintContextKey();
            int cursor = 0;
            if (hintContext.getOrDefault(player, Integer.MIN_VALUE) == context) {
                cursor = (hintCursor.getOrDefault(player, -1) + 1) % candidates.size();
            }
            hintContext.put(player, context);
            hintCursor.put(player, cursor);
            hintTotal.put(player, candidates.size());
            choice = DoudizhuRules.cardsFor(hand, candidates.get(cursor));
            // 轮换进度不刷聊天栏（连按十次会刷屏），改由界面状态行显示「提示 2/6」
        }
        if (choice == null || choice.isEmpty()) {
            player.sendMessage(Component.translatable("message.chartalandlords.hint_none"));
            return;
        }
        returnSelectionToHand(player);
        for (Card card : choice) {
            int index = indexOfIdentity(getPlayerHand(player), card);
            if (index >= 0) {
                getSelection(player).addLast(getPlayerHand(player).remove(index));
            }
        }
        afterSelectionChanged(player);
    }

    /**
     * 「提示」轮换的上下文键：上一手牌 + 玩家手动改过选牌的次数。
     *
     * <p>不能把手牌张数算进去——提示本身会把选中的牌移出手牌，那样每次按提示都会误判成「情况变了」
     * 而永远停在第一个候选。</p>
     */
    private int hintContextKey() {
        int combo = lastCombo == null ? 0
                : (lastCombo.type().ordinal() * 31 + lastCombo.key()) * 31 + lastCombo.size();
        return combo * 31 + selectionRevision;
    }

    private void clearHintCursor(CardPlayer player) {
        hintCursor.remove(player);
        hintContext.remove(player);
        hintTotal.remove(player);
    }

    /** 界面用：当前提示轮换到第几个候选（1 起；没提示过或局面已变则为 0）。 */
    public int hintIndex(CardPlayer player) {
        Integer context = hintContext.get(player);
        Integer cursor = hintCursor.get(player);
        if (context == null || cursor == null || context != hintContextKey()) {
            return 0;
        }
        return cursor + 1;
    }

    /** 界面用：当前提示一共有几个候选（没提示过或局面已变则为 0）。 */
    public int hintTotal(CardPlayer player) {
        Integer context = hintContext.get(player);
        if (context == null || context != hintContextKey()) {
            return 0;
        }
        return hintTotal.getOrDefault(player, 0);
    }

    /** 界面用：本局已经打出的炸弹 + 王炸数（倍数 = 2 的它次方）。 */
    public int nukeCount() {
        return nukeCount;
    }

    /**
     * 界面用：当前的<b>局倍数</b> = 炸弹倍数 × 抢地主倍数。
     *
     * <p>不含明牌 / 加倍（那是逐座位的个人倍数）也不含春天 / 反春天（要终局才知道），
     * 所以它只是「现在这一局至少值多少」的下界，结算时才会写进 {@link DoudizhuScore.Settlement#multiplier()}。</p>
     */
    public int baseMultiplier() {
        return DoudizhuScore.nukeMultiplier(nukeCount) << Math.min(16, grabDoublings());
    }

    // ------------------------------------------------------------------ 菜单查询

    public boolean isSelectionAllowed(CardPlayer player) {
        return isGameReady && !isGameOver && phase == Phase.PLAYING && !players.isEmpty()
                && currentSeat >= 0 && currentSeat < players.size() && players.get(currentSeat) == player;
    }

    public boolean canPass(CardPlayer player) {
        return isSelectionAllowed(player) && lastCombo != null;
    }

    public boolean canPlaySelection(CardPlayer player) {
        return isSelectionAllowed(player) && !getSelection(player).isEmpty();
    }

    public int phaseOrdinal() {
        return phase.ordinal();
    }

    public Phase phase() {
        return phase;
    }

    public int currentSeat() {
        return currentSeat;
    }

    public int highestBid() {
        return highestBid;
    }

    public int landlordSeat() {
        return landlordSeat;
    }

    public int lastPlaySeat() {
        return lastPlaySeat;
    }

    public int lastComboTypeOrdinal() {
        return lastCombo == null ? -1 : lastCombo.type().ordinal();
    }

    public int lastComboKey() {
        return lastCombo == null ? -1 : lastCombo.key();
    }

    public int lastPlaySize() {
        return lastCombo == null ? 0 : lastCombo.size();
    }

    public boolean isBottomRevealed() {
        return bottomRevealed;
    }

    /**
     * 出牌序号：每成功打出一手 +1。界面用它判断「桌上换牌了」，从而给最新一手加高亮闪烁。
     *
     * <p>不拿 {@code lastCombo} 代替，是因为两手完全相同的牌（跟同样的牌型）也必须算新的一手。</p>
     */
    public int playRevision() {
        return playRevision;
    }

    public int seatCount() {
        return players.size();
    }

    public int seatOf(CardPlayer player) {
        return players.indexOf(player);
    }

    public CardPlayer seatPlayer(int seat) {
        return seat >= 0 && seat < players.size() ? players.get(seat) : null;
    }

    public int handSizeAt(int seat) {
        CardPlayer player = seatPlayer(seat);
        return player == null ? 0 : getPlayerHand(player).size();
    }

    public boolean isFourPlayer() {
        return players.size() >= 4;
    }

    public GameSlot getBottomSlot() {
        return bottomCards;
    }

    public GameSlot getPlayArea() {
        return playArea;
    }

    public GameSlot getPlayRow(int row) {
        return row == 0 ? playRow0 : playRow1;
    }

    /** 未发出的牌堆剩余张数（供测试做牌数守恒校验）。 */
    public int remainingStockSize() {
        return stock.size();
    }

    /** 已打出（进入弃牌堆）的牌数（供测试做牌数守恒校验）。 */
    public int discardedCardCount() {
        return discard.size();
    }

    /** 弃牌堆本体（供测试与记牌器守恒校验逐张查看）。 */
    public GameSlot getDiscardSlot() {
        return discard;
    }

    /** 翻开时的底牌点数快照（供记牌器守恒校验逐张核对）。 */
    public int[] revealedBottomValues() {
        return revealedBottomValues.clone();
    }

    /** 翻开时的底牌对象（按身份使用；供测试核对「哪几张底牌还在桌上」）。 */
    public List<Card> revealedBottomCards() {
        return List.copyOf(revealedBottomCards);
    }

    public GameSlot getSelection(CardPlayer player) {
        return selections.computeIfAbsent(player, key -> displaySlot());
    }

    public List<GameSlot> getHandSegments(CardPlayer player) {
        return handViewMap.computeIfAbsent(player, key ->
                rows(DoudizhuValues.HAND_ROWS * DoudizhuValues.HAND_SEGMENTS_PER_ROW));
    }

    public GameSlot getHandSegment(CardPlayer player, int segment) {
        List<GameSlot> segments = getHandSegments(player);
        return segment >= 0 && segment < segments.size() ? segments.get(segment) : segments.get(0);
    }

    /** 界面专用底牌槽（不加入世界桌面槽位）：桌面底牌清理后仍可随时查看。 */
    public GameSlot getBottomViewSlot() {
        return bottomView;
    }

    /**
     * 桌面是否在绘制各座位的手牌扇形。
     *
     * <p>1.7.0 起<b>整局都是 true</b>：扇形往外推到椅子那侧之后不再挡桌面，
     * 所以不再像以前那样在第一轮结束时收走（那时桌面上会只剩一张上一手牌）。</p>
     */
    public boolean isTableHandsVisible() {
        return true;
    }

    public List<GameSlot> getSelectionRows(CardPlayer player) {
        return selectViewMap.computeIfAbsent(player, key -> rows(DoudizhuValues.SELECT_ROWS));
    }

    public GameSlot getSelectionRow(CardPlayer player, int row) {
        List<GameSlot> rows = getSelectionRows(player);
        return row >= 0 && row < rows.size() ? rows.get(row) : rows.get(0);
    }

    public int selectionSize(CardPlayer player) {
        return getSelection(player).size();
    }

    // ------------------------------------------------------------------ 内部工具

    private List<Card> handOf(CardPlayer player) {
        List<Card> hand = new ArrayList<>();
        getPlayerHand(player).forEach(hand::add);
        return hand;
    }

    private void afterSelectionChanged(CardPlayer player) {
        // 选牌不再重排手牌：牌在屏幕上的位置要稳住，否则每点一张就找不到下一张了。
        // 出牌区仍然排序——那是一片「这一手是什么牌型」的展示区，排序之后顺子/连对一眼可读。
        sortSlot(getSelection(player));
        syncCensoredHand(player);
        refreshHandViews(player);
        refreshSelectionViews(player);
    }

    /** 把当前出牌区的牌收进弃牌堆，再摆上新的牌。 */
    private void pushPlayArea(List<Card> cards) {
        List<Card> previous = new ArrayList<>();
        playArea.forEach(previous::add);
        if (!previous.isEmpty()) {
            discard.addAll(previous);
        }
        playArea.setCards(new ArrayList<>(cards));
        // 上一手牌挪向出牌者那一侧；收桌（cards 为空）时回到桌心
        placeLastPlayForSeat(cards.isEmpty() ? -1 : lastPlaySeat);
        refreshPlayViews();
    }

    /**
     * 把上一手牌从桌心往座位 {@code seat} 的方向偏 {@link #PLAY_DRIFT}，并摆成那个座位的朝向。
     *
     * <p>朝向直接抄这个座位<b>扇形自己的</b> {@code angle} 与 {@code stackDirection}：
     * 那两项是 Charta 按椅子朝向设好的，扇形看着对，出牌区照抄就一定对。
     * 于是「牌从谁那一侧推出来、正面朝向谁」这件事在桌上看得出来，而不是所有人出的牌都朝同一个方向。</p>
     *
     * <p>偏移是从「扇形基准位置」算出来的，所以跟「轮到谁」的推牌互不干扰：
     * 一个人既是上一手的出牌者、又正好轮到他时，上一手牌不会跟着他的扇形一起晃。</p>
     */
    private void placeLastPlayForSeat(int seat) {
        float x = TABLE_CENTER_X;
        float y = TABLE_CENTER_Y;
        if (seat >= 0 && seat < players.size()) {
            CardPlayer player = players.get(seat);
            float[] rest = fanRest.get(player);
            if (rest != null) {
                x += (rest[0] - TABLE_CENTER_X) * PLAY_DRIFT;
                y += (rest[1] - TABLE_CENTER_Y) * PLAY_DRIFT;
            }
            GameSlot fan = getCensoredHand(player);
            playArea.setAngle(fan.getAngle());
            playArea.setStackDirection(fan.getStackDirection());
        }
        if (Math.abs(playArea.getX() - x) > 0.01f || Math.abs(playArea.getY() - y) > 0.01f) {
            playArea.setX(x);
            playArea.setY(y);
        }
    }

    private void returnSelectionToHand(CardPlayer player) {
        GameSlot selection = getSelection(player);
        if (selection.isEmpty()) {
            return;
        }
        List<Card> back = new ArrayList<>();
        selection.forEach(back::add);
        selection.setCards(new ArrayList<>());
        for (Card card : back) {
            insertByRank(getPlayerHand(player), card);
        }
        syncCensoredHand(player);
        refreshHandViews(player);
        refreshSelectionViews(player);
    }

    /**
     * 把一张牌按点数插回手牌里它该在的位置。
     *
     * <p>「撤回」用它而不是重排整只手：把刚选出去的牌插回原处，手牌看上去<b>一点都没动</b>。
     * 手动牌序下也没有统一的可排序依据，插到同点数的邻居旁边就是最符合直觉的落点。</p>
     */
    private static void insertByRank(GameSlot hand, Card card) {
        List<Card> cards = new ArrayList<>();
        hand.forEach(cards::add);
        Comparator<Card> order = DoudizhuValues.order();
        int index = 0;
        while (index < cards.size() && order.compare(cards.get(index), card) <= 0) {
            index++;
        }
        cards.add(index, card);
        hand.setCards(cards);
    }

    /** 从手牌/出牌区里移除已经打出的牌（按对象身份，避免两张相同牌混淆）。 */
    private void removePlayedCards(CardPlayer player, List<Card> cards) {
        for (Card card : cards) {
            int handIndex = indexOfIdentity(getPlayerHand(player), card);
            if (handIndex >= 0) {
                getPlayerHand(player).remove(handIndex);
                continue;
            }
            int selectionIndex = indexOfIdentity(getSelection(player), card);
            if (selectionIndex >= 0) {
                getSelection(player).remove(selectionIndex);
            }
        }
        refreshSelectionViews(player);
    }

    private static int indexOfIdentity(GameSlot slot, Card card) {
        List<Card> cards = new ArrayList<>();
        slot.forEach(cards::add);
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i) == card) {
                return i;
            }
        }
        return cards.indexOf(card);
    }

    /**
     * 整理这个座位的手牌。
     *
     * <p><b>只在需要的时候排</b>：发牌、以及手动牌序被复位之后。出牌与选牌<b>不再</b>重排，
     * 因为「每出一手整只手牌就重排一次」是最伤手感的一件事——牌在屏幕上的位置全变，
     * 下一手要点哪儿得重新找。删牌本身不会破坏已排好的顺序，所以保持不动既稳定又整齐。</p>
     *
     * <p>已经用过手动牌序的座位<b>不排</b>：那是玩家自己摆的位置。</p>
     */
    private void sortHand(CardPlayer player) {
        int seat = seatOf(player);
        if (seat >= 0 && seat < manualOrder.length && manualOrder[seat]) {
            return;
        }
        GameSlot hand = getPlayerHand(player);
        List<Card> sorted = new ArrayList<>();
        hand.forEach(sorted::add);
        sorted.sort(DoudizhuValues.order());
        hand.clear();
        hand.addAll(sorted);
    }

    /**
     * 强制整理（无视手动牌序）：界面的「整理」按钮走这里。
     *
     * @return 这个座位当前是否有手牌可整理
     */
    public boolean tidyHand(CardPlayer player) {
        int seat = seatOf(player);
        if (seat < 0 || seat >= players.size()) {
            return false;
        }
        manualOrder[seat] = false;
        sortHand(player);
        refreshHandViews(player);
        refreshSelectionViews(player);
        return true;
    }

    /** 这个座位是否正在使用手动牌序（界面据此回显「整理」按钮的可用性）。 */
    public boolean isManualOrder(int seat) {
        return seat >= 0 && seat < players.size() && manualOrder[seat];
    }

    /**
     * 把手牌里第 {@code from} 张拖到第 {@code to} 个位置（手动调整牌序）。
     *
     * <p>{@code to} 是「插入位置」而不是「交换目标」：把一张牌拖到另一张牌上，
     * 它会插到那张牌<b>原来所在的位置</b>，其余牌依次让位——这跟玩家把牌插进手牌里的直觉一致。
     * 两个下标都会被夹到合法范围内，越界不是错误（拖到空白处就等于拖到末尾）。</p>
     *
     * @return 是否真的动了牌
     */
    public boolean moveHandCard(CardPlayer player, int from, int to) {
        int seat = seatOf(player);
        if (seat < 0 || seat >= players.size() || isGameOver) {
            return false;
        }
        GameSlot hand = getPlayerHand(player);
        int size = hand.size();
        if (size < 2) {
            return false;
        }
        int source = Math.max(0, Math.min(size - 1, from));
        int target = Math.max(0, Math.min(size - 1, to));
        if (source == target) {
            return false;
        }
        List<Card> cards = new ArrayList<>();
        hand.forEach(cards::add);
        Card moved = cards.remove(source);
        cards.add(target, moved);
        hand.setCards(cards);
        manualOrder[seat] = true;
        refreshHandViews(player);
        return true;
    }

    private static void sortSlot(GameSlot slot) {
        List<Card> sorted = new ArrayList<>();
        slot.forEach(sorted::add);
        sorted.sort(DoudizhuValues.order());
        slot.clear();
        slot.addAll(sorted);
    }

    /**
     * 让「看不见的手牌」数量与真实手牌一致；明牌过的座位放<b>正面副本</b>。
     *
     * <p>明牌就是靠这一个槽实现的：{@code Game.getCensoredHand(viewer, player)} 在
     * {@code viewer != player} 时返回的就是这个共享槽，所以往里面放正面牌，别人立刻就看见了。
     * 用 {@code copy()} 而不是真牌本身，是为了绝不把地主手里的真对象暴露出去
     * （真牌一旦被别处 remove，就会把明牌者的手牌偷走）。</p>
     */
    private void syncCensoredHand(CardPlayer player) {
        int seat = seatOf(player);
        boolean revealedSeat = seat >= 0 && seat < revealed.length && revealed[seat];
        GameSlot censored = getCensoredHand(player);
        List<Card> faceUp = revealedSeat ? faceUpCopies(player) : null;
        // 界面里的明牌展示行也要跟着更新：牌桌那一份在牌局界面里基本被面板挡住，
        // 界面里必须再有一份，否则玩家看到的就是「明牌了但看不见牌」。
        syncRevealView(seat, faceUp);
        List<Card> wanted;
        if (revealedSeat) {
            wanted = faceUp;
        } else {
            wanted = new ArrayList<>();
            for (int i = getPlayerHand(player).size(); i > 0; i--) {
                wanted.add(new Card());
            }
        }
        // 只在张数或身份真的变了时才重建，避免每次同步都分配一整手牌
        if (sameCensoredContent(censored, wanted, revealedSeat)) {
            return;
        }
        censored.setCards(wanted);
    }

    /** 整手牌的正面副本（点数与顺序都和真手牌一致，但对象是新的，拿走也影响不到真手牌）。 */
    private List<Card> faceUpCopies(CardPlayer player) {
        List<Card> copies = new ArrayList<>();
        getPlayerHand(player).forEach(card -> {
            Card copy = card.copy();
            if (copy.flipped()) {
                copy.flip();
            }
            copies.add(copy);
        });
        return copies;
    }

    /**
     * 同步界面里的明牌展示槽：明牌过的座位放整手正面牌，其余座位保持为空。
     *
     * <p>空槽是「不显示」的关键——{@code GameScreen} 对空卡槽根本不创建控件，
     * 所以没人明牌时这几行不占任何画面。</p>
     */
    private void syncRevealView(int seat, List<Card> faceUp) {
        if (seat < 0 || seat >= revealViews.size()) {
            return;
        }
        GameSlot view = revealViews.get(seat);
        if (faceUp == null) {
            if (!view.isEmpty()) {
                view.setCards(new ArrayList<>());
            }
            return;
        }
        if (sameCensoredContent(view, faceUp, true)) {
            return;
        }
        view.setCards(faceUp);
    }

    /** 背面牌只要张数一样就等价；正面牌要逐张比点数与花色。 */
    private static boolean sameCensoredContent(GameSlot censored, List<Card> wanted, boolean faceUp) {
        if (censored.size() != wanted.size()) {
            return false;
        }
        if (!faceUp) {
            return true;
        }
        List<Card> current = new ArrayList<>();
        censored.forEach(current::add);
        for (int i = 0; i < current.size(); i++) {
            Card a = current.get(i);
            Card b = wanted.get(i);
            if (a.flipped() || !a.suit().equals(b.suit()) || !a.rank().equals(b.rank())) {
                return false;
            }
        }
        return true;
    }

    private void refreshHandViews(CardPlayer player) {
        spliceRows(getHandSegments(player), getPlayerHand(player), SEGMENT_CAPACITY);
    }

    private void refreshSelectionViews(CardPlayer player) {
        spliceRows(getSelectionRows(player), getSelection(player), ROW_CAPACITY);
    }

    private void refreshPlayViews() {
        List<Card> cards = new ArrayList<>();
        playArea.forEach(cards::add);
        spliceInto(playRow0, cards, 0, ROW_CAPACITY);
        spliceInto(playRow1, cards, ROW_CAPACITY, ROW_CAPACITY);
    }

    private void spliceRows(List<GameSlot> rowSlots, GameSlot source, int capacity) {
        List<Card> cards = new ArrayList<>();
        source.forEach(cards::add);
        for (int row = 0; row < rowSlots.size(); row++) {
            spliceInto(rowSlots.get(row), cards, row * capacity, capacity);
        }
    }

    private static void spliceInto(GameSlot row, List<Card> cards, int from, int capacity) {
        int to = Math.min(cards.size(), from + capacity);
        row.setCards(to > from ? new ArrayList<>(cards.subList(from, to)) : new ArrayList<>());
    }

    private static List<GameSlot> rows(int count) {
        List<GameSlot> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(displaySlot());
        }
        return rows;
    }

    /** 展示/私有容器：框架的「拿起牌」流程不能动它，只能由本模组直接操作。 */
    private static GameSlot displaySlot() {
        return displaySlot(0f, 0f, Direction.EAST, 100f);
    }

    private static GameSlot displaySlot(float x, float y, Direction direction, float maxStack) {
        return new GameSlot(new LinkedList<>(), x, y, 0f, 0f, direction, maxStack, true) {
            @Override
            public boolean canInsertCard(CardPlayer player, List<Card> cards, int index) {
                return false;
            }

            @Override
            public boolean canRemoveCard(CardPlayer player, int index) {
                return false;
            }
        };
    }

    private boolean isTeammate(int seatA, int seatB) {
        if (seatA == seatB || seatA < 0 || seatB < 0) {
            return false;
        }
        return seatA != landlordSeat && seatB != landlordSeat;
    }

    private int smallestOpponentHandSize(int seat) {
        int smallest = -1;
        for (int i = 0; i < players.size(); i++) {
            if (i == seat) {
                continue;
            }
            // 对手 = 阵营不同的人：自己是地主时是所有农民，自己是农民时只有地主（队友不算对手）
            boolean opponent = (seat == landlordSeat) != (i == landlordSeat);
            if (!opponent) {
                continue;
            }
            int size = getPlayerHand(players.get(i)).size();
            if (smallest < 0 || size < smallest) {
                smallest = size;
            }
        }
        return smallest;
    }

    /** 组一手 AI 决策上下文：自己是什么身份、谁快走完了、还有哪些牌没露面。 */
    private AiContext contextFor(int seat, boolean teammateLed) {
        boolean landlord = seat == landlordSeat;
        int landlordHandSize = landlordSeat >= 0 && landlordSeat < players.size()
                ? getPlayerHand(players.get(landlordSeat)).size()
                : -1;
        int teammateHandSize = lastPlaySeat >= 0 && lastPlaySeat < players.size()
                ? getPlayerHand(players.get(lastPlaySeat)).size()
                : -1;
        int minOpponentHandSize = smallestOpponentHandSize(seat);
        if (lastCombo == null) {
            return AiContext.lead(landlord, minOpponentHandSize, unseenCounts(seat));
        }
        return AiContext.following(lastCombo, landlord, teammateLed, teammateHandSize, landlordHandSize,
                minOpponentHandSize, unseenCounts(seat));
    }

    /**
     * 记牌器视角：每个点数还有几张「没露面」。
     *
     * <p>起点是整副牌的点数分布，再扣掉自己看得见的部分：自己的手牌、<b>已经选进出牌区的牌</b>、
     * 桌面上的上一手牌、弃牌堆，以及<b>还捏在地主手里的底牌</b>（界面底牌行会在第一轮后被清空，
     * 所以按对象身份回查，见 {@link #revealedBottomCards}）。这张表既是界面记牌器的数据源，
     * 也让 AI 能判断「这手牌是不是没人压得住」。</p>
     *
     * <p>两处容易错的地方：选牌区必须算作已见，否则玩家每点一张牌记牌器就凭空多一张「未露面」；
     * 已经打出去的底牌也不能再按底牌扣一遍，因为它已经通过桌面/弃牌堆计过一次了。</p>
     */
    public int[] unseenCounts(int seat) {
        boolean four = isFourPlayer();
        int[] remaining = new int[DoudizhuValues.MAX_VALUE + 1];
        for (int value = DoudizhuValues.MIN_VALUE; value <= DoudizhuValues.TWO_VALUE; value++) {
            remaining[value] = four ? 8 : 4;
        }
        remaining[DoudizhuValues.SMALL_JOKER_VALUE] = four ? 2 : 1;
        remaining[DoudizhuValues.BIG_JOKER_VALUE] = four ? 2 : 1;
        CardPlayer viewer = players.get(seat);
        markSeen(remaining, getPlayerHand(viewer));
        markSeen(remaining, getSelection(viewer));
        markSeen(remaining, playArea);
        markSeen(remaining, discard);
        // 明牌的手牌也是公开信息：别人的明牌同样算「已见」，否则记牌器会虚报未露面张数，
        // 而 AI 的「这手牌外面压不动」判断也会因为漏算而过于乐观。
        boolean landlordRevealed = landlordSeat >= 0 && landlordSeat < players.size() && revealed[landlordSeat];
        for (int other = 0; other < players.size(); other++) {
            if (other != seat && revealed[other]) {
                markSeen(remaining, getPlayerHand(players.get(other)));
            }
        }
        // 底牌是公开信息：只对「还在地主手里」的那几张额外扣一次；已经打出去的由桌面/弃牌堆覆盖。
        // 地主本人不用扣——那几张牌就在他自己手里，markSeen 已经算过。
        //
        // 地主明牌时必须整段跳过：上面那一步已经把地主整手牌（含底牌）算成已见了，
        // 再按底牌扣一遍就是同一张牌扣两次，记牌器会凭空少牌、AI 会误判「外面没牌了」。
        if (bottomRevealed && !landlordRevealed && seat != landlordSeat
                && landlordSeat >= 0 && landlordSeat < players.size()) {
            GameSlot landlordHand = getPlayerHand(players.get(landlordSeat));
            for (Card card : revealedBottomCards) {
                if (indexOfIdentity(landlordHand, card) < 0) {
                    continue;
                }
                int value = DoudizhuValues.valueOf(card);
                if (value >= 0 && value < remaining.length && remaining[value] > 0) {
                    remaining[value]--;
                }
            }
        }
        return remaining;
    }

    private static void markSeen(int[] remaining, GameSlot slot) {
        slot.forEach(card -> {
            int value = DoudizhuValues.valueOf(card);
            if (value >= 0 && value < remaining.length && remaining[value] > 0) {
                remaining[value]--;
            }
        });
    }

    private static Component cardsComponent(GameSlot slot) {
        List<Card> cards = new ArrayList<>();
        slot.forEach(cards::add);
        return cardsComponent(cards);
    }

    private static Component cardsComponent(List<Card> cards) {
        return cards.stream()
                .map(card -> Component.literal(cardName(card)))
                .reduce((a, b) -> a.copy().append(" ").append(b))
                .orElse(Component.empty());
    }

    private static String cardName(Card card) {
        int value = DoudizhuValues.valueOf(card);
        return switch (value) {
            case DoudizhuValues.SMALL_JOKER_VALUE -> "小王";
            case DoudizhuValues.BIG_JOKER_VALUE -> "大王";
            case DoudizhuValues.TWO_VALUE -> "2";
            case DoudizhuValues.A_VALUE -> "A";
            case 13 -> "K";
            case 12 -> "Q";
            case 11 -> "J";
            default -> Integer.toString(value);
        };
    }

    // ------------------------------------------------------------------ 界面反馈（追加接口）

    /**
     * 出牌区当前选牌的牌型序号（-1 = 空选或牌型不合法）。
     *
     * <p>界面要给出「当前选择 = 什么牌型、能不能压」的即时反馈，但不允许自己调用规则类，
     * 于是把判定收在牌局内部，经 {@code DoudizhuMenu} 的数据槽同步给客户端。</p>
     */
    public int selectionComboTypeOrdinal(CardPlayer player) {
        Combo combo = classifySelection(player);
        return combo == null ? -1 : combo.type().ordinal();
    }

    /** 出牌区当前选牌能否压过上一手（首出时只要有合法牌型就算能出）。 */
    public boolean selectionBeatsLast(CardPlayer player) {
        Combo combo = classifySelection(player);
        return combo != null && DoudizhuRules.beats(combo, lastCombo);
    }

    /** 把选牌区里的牌拿出来判定牌型；空选、非出牌阶段或牌型不合法都返回 null。 */
    private Combo classifySelection(CardPlayer player) {
        if (player == null || isGameOver || phase != Phase.PLAYING) {
            return null;
        }
        GameSlot selection = getSelection(player);
        if (selection.isEmpty()) {
            return null;
        }
        List<Card> cards = new ArrayList<>();
        selection.forEach(cards::add);
        return DoudizhuRules.classify(cards, isFourPlayer());
    }
}
