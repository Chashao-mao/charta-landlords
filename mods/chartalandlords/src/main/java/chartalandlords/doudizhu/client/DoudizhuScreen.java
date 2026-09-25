package chartalandlords.doudizhu.client;

import chartalandlords.doudizhu.game.engine.RuleEngine;
import chartalandlords.doudizhu.game.engine.ComboType;
import chartalandlords.doudizhu.game.DoudizhuAction;
import chartalandlords.doudizhu.game.DoudizhuGame;
import chartalandlords.doudizhu.game.DoudizhuMenu;
import chartalandlords.doudizhu.game.DoudizhuValues;
import chartalandlords.doudizhu.network.GameActionPayload;
import chartalandlords.doudizhu.network.MoveCardPayload;
import chartalandlords.doudizhu.network.SelectCardPayload;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.menu.CardSlot;
import dev.lucaargolo.charta.common.utils.CardPlayerHead;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * 斗地主界面：顶部座位条 + 状态行、中右侧动作按钮列、底部四个牌带（手牌 / 出牌区 / 上一手 / 底牌）。
 *
 * <h2>布局模型</h2>
 * <p>所有几何都来自 {@link DoudizhuMenu}：面板宽高、每行的 slot.y、行距、带的位置。屏幕只按
 * <b>width / height</b> 推导——面板水平居中（{@code panelLeft(width)}，与 Charta 的 leftPos 相同），
 * 牌行按屏幕底部锚定。不再出现「状态条按 width 铺满、面板按 leftPos 摆」这种两套坐标。</p>
 *
 * <p>Charta 的 {@code GameScreen.init} 会在固定位置加四个按钮：玩法 (5,35,20,20)、选项 (27,35,20,20)、
 * 牌堆 (width-25,35,20,20)、记牌器 (width-47,35,20,20)。界面的任何自绘内容都不会压到这条横带上：
 * 状态行收进座位条（y 24..33），动作按钮列在牌带右侧的空白列里。</p>
 *
 * <p>手牌 / 出牌区行的点击走本模组自己的 {@link SelectCardPayload}，而不是 Charta 默认的「拿起一张牌」，
 * 这样才能一次选中多张牌组成对子、顺子、飞机等牌型。</p>
 */
@OnlyIn(Dist.CLIENT)
public class DoudizhuScreen extends GameScreen<DoudizhuGame, DoudizhuMenu> {

    // ------------------------------------------------------------------ 座位条

    /** 座位条总高：上面 23 是座位盒，下面一行是状态行。 */
    private static final int SEAT_BAR_HEIGHT = 34;
    private static final int SEAT_BOX_HEIGHT = 23;
    private static final int SEAT_MARGIN = 4;
    private static final int SEAT_GAP = 2;
    /** 状态行（座位条最下面那一行）的 y。 */
    private static final int STATUS_Y = SEAT_BOX_HEIGHT + 1;
    private static final int SEAT_HEAD_SIZE = 19;

    // ------------------------------------------------------------------ 动作按钮列

    private static final int ACTION_BUTTON_WIDTH = 64;
    private static final int ACTION_BUTTON_HEIGHT = 18;
    private static final int ACTION_BUTTON_GAP = 4;
    /** 按钮列与行区之间的水平间隙（行区右边缘到按钮左边缘）。 */
    private static final int ACTION_GAP_X = 8;
    /** 按钮列底边与手牌带上沿之间的垂直间隙。 */
    private static final int ACTION_GAP_Y = 6;
    /** Charta 那四个按钮所在横带的下沿（GameScreen.init 固定为 35..55），自绘内容绝不越界。 */
    private static final int CHARTA_BUTTON_BOTTOM = 55;
    private static final int BID_BUTTON_COUNT = 4;
    private static final int PLAY_BUTTON_COUNT = 5;
    /** 明牌 / 加倍阶段的三颗按钮：明牌 / 加倍 / 跳过。 */
    private static final int DECLARE_BUTTON_COUNT = 3;

    // ------------------------------------------------------------------ 记牌器

    /** 记牌器单元格高度（比牌带行距大，5 列 3 行才看得清）。 */
    private static final int COUNTER_CELL_HEIGHT = 22;
    private static final int COUNTER_PADDING = 8;
    private static final int COUNTER_RANK = 0xFFE8E8E8;
    private static final int COUNTER_RANK_EMPTY = 0xFF6A6A6A;
    private static final int COUNTER_COUNT = 0xFFFFD54F;
    private static final int COUNTER_COUNT_EMPTY = 0xFF5A5A5A;

    // ------------------------------------------------------------------ 牌带底衬

    private static final int BAND_PADDING_X = 3;
    private static final int BAND_LABEL_X = 6;
    private static final int BAND_LABEL_HEIGHT = 9;
    /** 分组框的标题条高度。 */
    private static final int HEADER_HEIGHT = 11;

    // ------------------------------------------------------------------ 配色与绘制原语

    /** 毡面自上而下的 4 段微渐变：避免一整块死板的纯色。 */
    private static final int[] FELT = {0xF01C3522, 0xF01A301E, 0xF0182B1A, 0xF0162617};
    /** 桌沿（毡面外侧的深色围边）。 */
    private static final int PANEL_RAIL = 0xF00B120D;
    /** 内凹面板的四要素：深色描边 + 左上内侧高光。 */
    private static final int FRAME_EDGE = 0xB0000000;
    private static final int FRAME_HIGHLIGHT = 0x2AFFFFFF;
    private static final int HEADER_BG = 0xC80F1711;

    private static final int BAND_HAND = 0x46FFD54F;
    private static final int BAND_SELECT = 0x4691CAFF;
    private static final int BAND_PLAY = 0x46A5D6A7;
    private static final int BAND_BOTTOM = 0x46FFAB91;
    private static final int STATUS_BG = 0xD2080C08;
    private static final int TURN_EDGE = 0xFFFFD54F;
    private static final int TURN_EDGE_DIM = 0xFFFFF3C4;
    private static final int SCORE_POSITIVE = 0xFF9AE59A;
    private static final int SCORE_NEGATIVE = 0xFFFFB060;
    private static final int SCORE_ZERO = 0xFF8A8A8A;
    private static final int LABEL_TEXT = 0xFFDCE8DC;
    /** 明牌徽章：亮金，表示「手牌是公开的」。 */
    private static final int MARK_REVEAL_COLOR = 0xFFFFD54F;
    /** 加倍徽章：青蓝，与明牌一眼可分。 */
    private static final int MARK_DOUBLE_COLOR = 0xFF7FE3FF;
    /** 明牌 + 加倍叠满时的 ×4 徽章：亮橙，是这一档里最贵的选择。 */
    private static final int MARK_FACTOR_COLOR = 0xFFFF9A4D;
    /** 表态牌子：抢了 / 叫了分 = 亮黄，不抢 / 不叫 = 灰。 */
    private static final int DECISION_GRAB_COLOR = 0xFFFFD54F;
    private static final int DECISION_BID_COLOR = 0xFF9AE59A;
    private static final int DECISION_PASS_COLOR = 0xFF7A7A7A;

    /**
     * 内凹面板：填充 + 深色外描边 + 左上内侧高光。
     * 所有 chrome（面板、牌带、座位盒、记牌器）都走这一支，视觉语言保持统一。
     */
    private static void frame(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int fill) {
        if (x1 - x0 < 3 || y1 - y0 < 3) {
            return;
        }
        guiGraphics.fill(x0, y0, x1, y1, fill);
        guiGraphics.fill(x0, y0, x1, y0 + 1, FRAME_EDGE);
        guiGraphics.fill(x0, y1 - 1, x1, y1, FRAME_EDGE);
        guiGraphics.fill(x0, y0, x0 + 1, y1, FRAME_EDGE);
        guiGraphics.fill(x1 - 1, y0, x1, y1, FRAME_EDGE);
        guiGraphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, FRAME_HIGHLIGHT);
        guiGraphics.fill(x0 + 1, y0 + 1, x0 + 2, y1 - 1, FRAME_HIGHLIGHT);
    }

    /** 带标题条的分组框：顶部 {@link #HEADER_HEIGHT} 行留给标题，标题下压一条 accent 线。 */
    private static void framedGroup(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int fill, int accent) {
        frame(guiGraphics, x0, y0, x1, y1, fill);
        guiGraphics.fill(x0 + 1, y0 + 1, x1 - 1, y0 + HEADER_HEIGHT, HEADER_BG);
        guiGraphics.fill(x0 + 1, y0 + HEADER_HEIGHT, x1 - 1, y0 + HEADER_HEIGHT + 1, accent);
    }

    /** 把强调色提亮成描边用的不透明版本（保留 RGB，抬高 alpha）。 */
    private static int accented(int color) {
        return 0xC0000000 | (color & 0x00FFFFFF);
    }

    private final List<Button> bidButtons = new ArrayList<>();
    private final List<Button> playButtons = new ArrayList<>();
    private final List<Button> declareButtons = new ArrayList<>();
    /** 上一次看到的出牌序号；变化时给最新一手加高亮。 */
    private int lastPlayRevision = -1;
    /** 背景音乐调度器（纯客户端，见 {@link DoudizhuBgm}）。 */
    private final DoudizhuBgm bgm = new DoudizhuBgm();

    // ------------------------------------------------------------------ 拖拽排序
    //
    // 手牌上的左键有<b>两种</b>意图：「选进出牌区」与「拖到别的位置」。两者共用一个按键，
    // 只能靠按下到松开之间的移动距离区分——这是所有拖放界面的标准做法，
    // 正常点击的按下与松开只隔几十毫秒，玩家感觉不到「延迟到松手才选中」。

    /**
     * 移动超过这么多像素才算拖拽（GUI 缩放前）。
     *
     * <p>1.8.0 起从 4 提到 6：4 像素在 1080p 下还不到一次手抖的量，结果「想点牌却变成拖牌」，
     * 点击选牌就时灵时不灵。6 像素仍然远低于「故意拖动」的距离，但明显更抗抖。</p>
     */
    private static final double DRAG_THRESHOLD = 6.0;
    /** 正在按住的手牌在整副手牌里的下标；-1 = 没在拖。 */
    private int dragHandIndex = -1;
    private double dragStartX;
    private double dragStartY;
    private boolean dragMoved;
    /** 拖拽期间是否已经把牌放进了框架的「手上牌」槽（松手时要清掉）。 */
    private boolean dragCarried;

    // ------------------------------------------------------------------ 看牌（独立界面）

    /** 「看牌」徽章的热区（{@code [x, y, w, h]}）；未绘制时为 null。 */
    private int[] handViewBadgeBounds;
    /**
     * 打开子界面前置位：{@link #removed()} 会在这个瞬间被调用，但此时 BGM 不该停
     * （看牌界面自己会继续 tick 它）。
     */
    private boolean keepMusicOnRemoval;

    public DoudizhuScreen(DoudizhuMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = DoudizhuMenu.PANEL_WIDTH;
        this.imageHeight = DoudizhuMenu.PANEL_HEIGHT;
    }

    // ------------------------------------------------------------------ 布局

    /** 面板左边界；与 AbstractContainerScreen 居中后的 leftPos 完全一致。 */
    private int panelLeft() {
        return DoudizhuMenu.panelLeft(this.width);
    }

    /** 手牌带的上沿（动作按钮列以此为锚点向下对齐）。 */
    private int handBandTop() {
        return (int) DoudizhuMenu.visualRowScreenY(DoudizhuMenu.VISUAL_ROW_HAND_BACK, this.height);
    }

    /**
     * 动作按钮列的底边：正常贴着手牌带上沿；如果界面被压得太矮、按钮会顶进 Charta 那条
     * 35..55 的按钮横带，就整列下移，保证永远不压住玩法 / 选项 / 牌堆 / 记牌器四个按钮。
     */
    private int actionColumnBottom() {
        int step = ACTION_BUTTON_HEIGHT + ACTION_BUTTON_GAP;
        int tallest = Math.max(BID_BUTTON_COUNT, Math.max(PLAY_BUTTON_COUNT, DECLARE_BUTTON_COUNT)) * step
                - ACTION_BUTTON_GAP;
        int lowest = CHARTA_BUTTON_BOTTOM + 2 + tallest;
        return Math.max(handBandTop() - ACTION_GAP_Y, lowest);
    }

    @Override
    protected void init() {
        super.init();
        this.bidButtons.clear();
        this.playButtons.clear();
        this.declareButtons.clear();

        int right = panelLeft() + DoudizhuMenu.PANEL_WIDTH - ACTION_GAP_X - ACTION_BUTTON_WIDTH;
        int bottom = actionColumnBottom();
        int step = ACTION_BUTTON_HEIGHT + ACTION_BUTTON_GAP;

        // 叫分按钮：一列四颗，自下而上与出牌按钮列完全重合（两者互斥显示，不会同时出现）。
        // 抢地主模式下只留「抢地主 / 不抢」两颗（标签在 containerTick 里改写）。
        String[] bidTooltips = {
                "message.chartalandlords.tooltip.bid",
                "message.chartalandlords.tooltip.bid",
                "message.chartalandlords.tooltip.bid",
                "message.chartalandlords.tooltip.bid_pass"
        };
        DoudizhuAction[] bidActions = {DoudizhuAction.BID_1, DoudizhuAction.BID_2, DoudizhuAction.BID_3,
                DoudizhuAction.BID_PASS};
        int bidTop = bottom - (BID_BUTTON_COUNT * step - ACTION_BUTTON_GAP);
        for (int i = 0; i < BID_BUTTON_COUNT; i++) {
            Component label = i < 3
                    ? Component.translatable("message.chartalandlords.button.bid", i + 1)
                    : Component.translatable("message.chartalandlords.button.bid_pass");
            bidButtons.add(addRenderableWidget(button(label, bidTooltips[i], bidActions[i], right, bidTop + i * step)));
        }

        // 出牌按钮：一列五颗，与叫分列同一条基线，按钮之间用 step 明确分开，不再叠在同一原点。
        String[] playTooltips = {
                "message.chartalandlords.tooltip.play",
                "message.chartalandlords.tooltip.pass",
                "message.chartalandlords.tooltip.hint",
                "message.chartalandlords.tooltip.retract",
                "message.chartalandlords.tooltip.tidy"
        };
        Component[] playLabels = {
                Component.translatable("message.chartalandlords.button.play"),
                Component.translatable("message.chartalandlords.button.pass"),
                Component.translatable("message.chartalandlords.button.hint"),
                Component.translatable("message.chartalandlords.button.retract"),
                Component.translatable("message.chartalandlords.button.tidy")
        };
        DoudizhuAction[] playActions = {DoudizhuAction.PLAY, DoudizhuAction.PASS, DoudizhuAction.HINT,
                DoudizhuAction.RETRACT, DoudizhuAction.TIDY_HAND};
        int playTop = bottom - (PLAY_BUTTON_COUNT * step - ACTION_BUTTON_GAP);
        for (int i = 0; i < PLAY_BUTTON_COUNT; i++) {
            playButtons.add(addRenderableWidget(
                    button(playLabels[i], playTooltips[i], playActions[i], right, playTop + i * step)));
        }

        // 明牌 / 加倍按钮：三颗，与出牌列同样的底部基线，居中贴齐。
        // 明牌 / 加倍按钮：明牌、加倍是两个<b>开关</b>（可以同时打开 → 个人倍数 ×4），
        // 第三颗是「确认并结束我的决定」。标签与可用性在 containerTick 里按同步下来的标记位刷新。
        String[] declareTooltips = {
                "message.chartalandlords.tooltip.reveal",
                "message.chartalandlords.tooltip.double",
                "message.chartalandlords.tooltip.declare_done"
        };
        Component[] declareLabels = {
                Component.translatable("message.chartalandlords.button.reveal"),
                Component.translatable("message.chartalandlords.button.double"),
                Component.translatable("message.chartalandlords.button.declare_done")
        };
        DoudizhuAction[] declareActions = {DoudizhuAction.REVEAL_HAND, DoudizhuAction.DOUBLE_UP,
                DoudizhuAction.DECLARE_PASS};
        int declareTop = bottom - (DECLARE_BUTTON_COUNT * step - ACTION_BUTTON_GAP);
        for (int i = 0; i < DECLARE_BUTTON_COUNT; i++) {
            declareButtons.add(addRenderableWidget(button(declareLabels[i], declareTooltips[i], declareActions[i],
                    right, declareTop + i * step)));
        }

        containerTick();
    }

    private Button button(Component label, String tooltipKey, DoudizhuAction action, int x, int y) {
        return Button.builder(label, b -> sendAction(action))
                .bounds(x, y, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                .build();
    }

    private void sendAction(DoudizhuAction action) {
        PacketDistributor.sendToServer(GameActionPayload.of(this.menu.containerId, action));
    }

    @Override
    public void containerTick() {
        super.containerTick();
        DoudizhuGame.Phase phase = this.menu.phase();
        boolean bidding = phase == DoudizhuGame.Phase.BIDDING;
        boolean declaring = phase == DoudizhuGame.Phase.DECLARING;
        boolean playing = phase == DoudizhuGame.Phase.PLAYING;
        boolean myBidTurn = this.menu.isBidding();
        boolean myTurn = this.menu.isMyTurn();
        boolean grabMode = this.menu.isGrabMode();
        int highestBid = this.menu.highestBid();

        for (int i = 0; i < bidButtons.size(); i++) {
            Button button = bidButtons.get(i);
            if (grabMode) {
                // 抢地主：只留「抢地主 / 不抢」，其余两颗隐藏（动作 id 复用 BID_1 / BID_PASS）
                if (i == 0) {
                    button.setMessage(Component.translatable("message.chartalandlords.button.grab"));
                } else if (i == 3) {
                    button.setMessage(Component.translatable("message.chartalandlords.button.grab_pass"));
                }
                button.visible = bidding && (i == 0 || i == 3);
                button.active = myBidTurn;
            } else {
                button.visible = bidding;
                button.active = myBidTurn && (i >= 3 || (i + 1) > highestBid);
            }
        }

        boolean hasSelection = this.menu.selectionCount() > 0;
        for (int i = 0; i < playButtons.size(); i++) {
            Button button = playButtons.get(i);
            button.visible = playing;
            button.active = switch (i) {
                case 0 -> myTurn && this.menu.canPlayNow();
                case 1 -> myTurn && this.menu.canPassNow();
                case 2 -> myTurn;
                default -> myTurn && hasSelection;
            };
        }

        boolean canDeclare = this.menu.canDeclareNow();
        int myMarks = this.menu.myMarks();
        boolean revealedMine = (myMarks & 1) != 0;
        boolean doubledMine = (myMarks & 2) != 0;
        // 开关按钮的文案要回显当前状态：已经打开的开关写「已明牌 / 已加倍」并变灰
        declareButtons.get(0).setMessage(Component.translatable(revealedMine
                ? "message.chartalandlords.button.revealed"
                : "message.chartalandlords.button.reveal"));
        declareButtons.get(1).setMessage(Component.translatable(doubledMine
                ? "message.chartalandlords.button.doubled"
                : "message.chartalandlords.button.double"));
        declareButtons.get(2).setMessage(Component.translatable("message.chartalandlords.button.declare_done"));
        declareButtons.get(0).visible = declaring;
        declareButtons.get(0).active = this.menu.canRevealNow();
        declareButtons.get(1).visible = declaring;
        declareButtons.get(1).active = this.menu.canDoubleNow();
        declareButtons.get(2).visible = declaring;
        declareButtons.get(2).active = canDeclare;

        // 桌上换牌了：给最新一手加一次高亮闪烁（Charta 的 CardSlotWidget 会自己倒计时）
        int revision = this.menu.playRevision();
        if (revision != lastPlayRevision) {
            lastPlayRevision = revision;
            if (revision > 0) {
                this.menu.highlightLastPlay();
            }
        }
        // 背景音乐：只在斗地主界面里放，关闭界面立刻停
        this.bgm.tick();
    }

    // ------------------------------------------------------------------ 顶部座位条 + 状态行

    @Override
    public void renderTopBar(@NotNull GuiGraphics guiGraphics) {
        int seats = Math.max(1, Math.min(DoudizhuValues.MAX_PLAYERS, this.menu.seatCount()));
        int boxWidth = Math.max(52, (width - SEAT_MARGIN * 2 - SEAT_GAP * (seats - 1)) / seats);
        int totalWidth = boxWidth * seats + SEAT_GAP * (seats - 1);
        int x0 = Math.max(SEAT_MARGIN, (width - totalWidth) / 2);
        for (int seat = 0; seat < seats; seat++) {
            drawSeat(guiGraphics, seat, x0 + seat * (boxWidth + SEAT_GAP), boxWidth);
        }
        drawStatusLine(guiGraphics);
    }

    /**
     * 名字后面的「表态」小牌子：抢地主模式下是 `抢` / `不抢`，叫分模式下是 `N分` / `不叫`。
     *
     * <p>没表态（还没轮到）就不画，所以一块牌子出现就说明这个人已经表过态了——
     * 这比只显示「已抢 2 次」有用得多，玩家能一眼看出谁抢了、谁在等。</p>
     *
     * <p>只在叫分 / 抢地主与明牌加倍阶段画：进入出牌阶段后，说明「谁抢过」的价值已经转移到
     * 座位盒右侧的 `地主 / 农民` 与 `明 / 倍 / ×4` 徽章上，再挂一块牌子只是噪音。</p>
     */
    private void drawDecisionChip(GuiGraphics guiGraphics, int seat, int x, int y) {
        DoudizhuGame.Phase phase = this.menu.phase();
        if (phase != DoudizhuGame.Phase.BIDDING && phase != DoudizhuGame.Phase.DECLARING) {
            return;
        }
        if (!this.menu.isSeatBidDecided(seat)) {
            return;
        }
        Component label;
        int color;
        if (this.menu.isGrabMode()) {
            boolean grabbed = this.menu.isSeatGrabbed(seat);
            label = Component.translatable(grabbed
                    ? "message.chartalandlords.chip.grab"
                    : "message.chartalandlords.chip.no_grab");
            color = grabbed ? DECISION_GRAB_COLOR : DECISION_PASS_COLOR;
        } else {
            int bid = this.menu.seatBid(seat);
            label = bid > 0
                    ? Component.translatable("message.chartalandlords.chip.bid", bid)
                    : Component.translatable("message.chartalandlords.chip.no_bid");
            color = bid > 0 ? DECISION_BID_COLOR : DECISION_PASS_COLOR;
        }
        int width = font.width(label) + 4;
        if (x + width > panelLeft() + DoudizhuMenu.PANEL_WIDTH) {
            return;
        }
        guiGraphics.fill(x, y, x + width, y + 9, 0xB0000000 | (color & 0x00FFFFFF));
        guiGraphics.fill(x, y, x + width, y + 1, 0xC0000000 | (color & 0x00FFFFFF));
        guiGraphics.drawString(font, label, x + 2, y + 1, 0xFFFFFFFF, true);
    }

    /** 单个座位盒：Charta 座位条的配色 + 方块头 + 名字 + 地主/农民徽章 + 剩余张数 + 累计分数。 */
    private void drawSeat(GuiGraphics guiGraphics, int seat, int x, int boxWidth) {
        CardPlayer player = this.menu.seatPlayer(seat);
        DoudizhuGame.Phase phase = this.menu.phase();
        // 明牌 / 加倍阶段没有「轮到谁」：把还没决定的人点亮，比死盯着地主更有用
        boolean turn = phase == DoudizhuGame.Phase.DECLARING
                ? !this.menu.isSeatDeclared(seat)
                : seat == this.menu.currentSeat() && phase != DoudizhuGame.Phase.OVER;
        boolean me = seat == this.menu.mySeat();
        boolean landlord = seat == this.menu.landlordSeat();
        int color = player == null ? 0xFFFFFF : player.getColor().getTextureDiffuseColor() & 0xFFFFFF;

        // 底色：座位染色 + 内凹描边；当前行动方的描边按 250ms 呼吸
        frame(guiGraphics, x, 0, x + boxWidth, SEAT_BOX_HEIGHT,
                (turn ? 0xD0000000 : me ? 0xB0000000 : 0x98000000) | color);
        guiGraphics.fill(x + 1, 1, x + 3, SEAT_BOX_HEIGHT - 1, 0xFF000000 | color);
        if (turn) {
            int edge = ((Util.getMillis() / 250) & 1L) == 0L ? TURN_EDGE : TURN_EDGE_DIM;
            guiGraphics.fill(x, 0, x + boxWidth, 1, edge);
            guiGraphics.fill(x, SEAT_BOX_HEIGHT - 1, x + boxWidth, SEAT_BOX_HEIGHT, edge);
            guiGraphics.fill(x, 0, x + 1, SEAT_BOX_HEIGHT, edge);
            guiGraphics.fill(x + boxWidth - 1, 0, x + boxWidth, SEAT_BOX_HEIGHT, edge);
        }
        if (player != null) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(x + 4, 2f, 0f);
            guiGraphics.pose().scale(0.66f, 0.66f, 1f);
            CardPlayerHead.renderHead(guiGraphics, 0, 0, player);
            guiGraphics.pose().popPose();
        }

        Component badge = null;
        if (landlord) {
            badge = Component.translatable("message.chartalandlords.landlord_tag");
        } else if (this.menu.landlordSeat() >= 0) {
            badge = Component.translatable("message.chartalandlords.farmer_tag");
        }
        // 明牌 / 加倍徽章紧贴阵营徽章左侧：这两个标记直接决定这一局值多少，必须一眼看得到。
        // 两个都打开时个人倍数是 ×4，所以直接写「明×4」比只写两个字母更清楚。
        boolean revealedSeat = this.menu.isSeatRevealed(seat);
        boolean doubledSeat = this.menu.isSeatDoubled(seat);
        Component revealMark = revealedSeat
                ? Component.translatable("message.chartalandlords.mark.reveal") : null;
        Component doubleMark = doubledSeat
                ? Component.translatable("message.chartalandlords.mark.double") : null;
        Component factorMark = revealedSeat && doubledSeat
                ? Component.translatable("message.chartalandlords.mark.factor",
                        this.menu.seatPersonalFactor(seat)) : null;
        int markWidth = (revealMark == null ? 0 : font.width(revealMark) + 2)
                + (doubleMark == null ? 0 : font.width(doubleMark) + 2)
                + (factorMark == null ? 0 : font.width(factorMark) + 2);
        int badgeWidth = (badge == null ? 0 : font.width(badge) + 3) + markWidth;
        int textX = x + SEAT_HEAD_SIZE;
        int textWidth = Math.max(12, boxWidth - SEAT_HEAD_SIZE - badgeWidth - 3);

        String name = player == null ? "?" : player.getName().getString();
        if (turn) {
            name = "> " + name;
        }
        // 文字一律带阴影：座位盒是半透明的（会透出世界），单靠前景色无法保证对比度。
        int nameWidth = guiGraphics.drawString(font, font.plainSubstrByWidth(name, textWidth), textX, 2,
                nameColor(color), true);
        // 「抢 / 不抢 / N 分 / 不叫」紧跟在名字后面：叫分与抢地主都是逐座位表态的，
        // 只显示一个总数根本看不出当前局面，所以每个人表过什么态必须写在各自的格子里。
        drawDecisionChip(guiGraphics, seat, textX + nameWidth + 3, 1);
        int cursor = x + boxWidth - badgeWidth;
        if (revealMark != null) {
            guiGraphics.drawString(font, revealMark, cursor, 2, MARK_REVEAL_COLOR, true);
            cursor += font.width(revealMark) + 2;
        }
        if (doubleMark != null) {
            guiGraphics.drawString(font, doubleMark, cursor, 2, MARK_DOUBLE_COLOR, true);
            cursor += font.width(doubleMark) + 2;
        }
        if (factorMark != null) {
            guiGraphics.drawString(font, factorMark, cursor, 2, MARK_FACTOR_COLOR, true);
        }
        if (badge != null) {
            guiGraphics.drawString(font, badge, x + boxWidth - font.width(badge) - 1, 2,
                    landlord ? TURN_EDGE : 0xFFB0BEC5, true);
        }

        StringBuilder second = new StringBuilder(
                Component.translatable("message.chartalandlords.seat_cards", this.menu.seatHandSize(seat)).getString());
        int comboType = this.menu.lastComboType();
        if (seat == this.menu.lastPlaySeat() && comboType >= 0 && comboType < ComboType.values().length) {
            second.append(" · ").append(Component.translatable("message.chartalandlords.seat_last_play",
                    Component.translatable(ComboType.values()[comboType].descriptionKey())).getString());
        }
        // 累计分数：**总是**画出来（0 也画），否则开局到第一次结算之间玩家看不到任何分数
        int score = this.menu.seatScore(seat);
        Component scoreText = Component.literal((score > 0 ? "+" : "") + score);
        int scoreWidth = font.width(scoreText);
        guiGraphics.drawString(font,
                font.plainSubstrByWidth(second.toString(),
                        Math.max(12, boxWidth - SEAT_HEAD_SIZE - scoreWidth - 6)),
                textX, 12, 0xFFE0E0E0, true);
        guiGraphics.fill(x + boxWidth - scoreWidth - 6, 11, x + boxWidth - 3, 21, 0x88000000);
        guiGraphics.drawString(font, scoreText, x + boxWidth - scoreWidth - 4, 12,
                score > 0 ? SCORE_POSITIVE : score < 0 ? SCORE_NEGATIVE : SCORE_ZERO, true);
    }

    /** 座位条最下面那一行：左边局面状态 + 底分/倍数，右边当前选牌反馈（含提示轮换进度）。 */
    private void drawStatusLine(GuiGraphics guiGraphics) {
        int barLeft = SEAT_MARGIN;
        int barRight = width - SEAT_MARGIN;
        frame(guiGraphics, 0, SEAT_BOX_HEIGHT, width, SEAT_BAR_HEIGHT, STATUS_BG);

        Component selection = selectionText();
        int selectionWidth = selection.getString().isEmpty() ? 0 : font.width(selection);
        int available = barRight - barLeft - selectionWidth - 8;
        Component status = statusText();
        if (available > 16) {
            guiGraphics.drawString(font, font.plainSubstrByWidth(status.getString(), available),
                    barLeft, STATUS_Y, 0xFFF0F0F0, true);
        }
        if (selectionWidth > 0) {
            guiGraphics.drawString(font, selection, barRight - selectionWidth, STATUS_Y, selectionColor(), true);
        }
    }

    private Component statusText() {
        DoudizhuGame.Phase phase = this.menu.phase();
        Component text;
        if (phase == DoudizhuGame.Phase.BIDDING) {
            if (this.menu.isGrabMode()) {
                text = Component.translatable("message.chartalandlords.label.grabbing", seatName(this.menu.currentSeat()),
                        this.menu.grabCount());
                // 「已抢 N 次」只说了一半：谁现在占着地主位才是关键
                int leading = this.menu.leadingSeat();
                text = text.copy().append("  ").append(leading >= 0
                        ? Component.translatable("message.chartalandlords.label.grab_leading", seatName(leading))
                        : Component.translatable("message.chartalandlords.label.grab_none"));
            } else {
                text = Component.translatable("message.chartalandlords.label.bidding", seatName(this.menu.currentSeat()),
                        this.menu.highestBid());
            }
        } else if (phase == DoudizhuGame.Phase.DECLARING) {
            int seconds = (this.menu.declareTicksLeft() + 19) / 20;
            text = Component.translatable("message.chartalandlords.label.declaring", seconds);
            // 个人倍数是「按下即生效」的，所以确认之前就要把当前押注写在眼前
            text = text.copy().append("  ").append(Component.translatable(
                    "message.chartalandlords.label.my_factor", this.menu.myPersonalFactor()));
        } else if (phase == DoudizhuGame.Phase.PLAYING) {
            text = this.menu.isMyTurn()
                    ? Component.translatable("message.chartalandlords.label.your_turn")
                    : Component.translatable("message.chartalandlords.label.other_turn", seatName(this.menu.currentSeat()));
        } else {
            text = Component.translatable("message.chartalandlords.label.over");
        }
        if (this.menu.landlordSeat() >= 0) {
            text = text.copy().append("  ").append(
                    Component.translatable("message.chartalandlords.label.landlord", seatName(this.menu.landlordSeat())));
        }
        // 底分 / 炸弹 / 抢地主 / 局倍数：让「这一局值多少」全程可见
        int bid = this.menu.highestBid();
        if (bid > 0) {
            text = text.copy().append("  ").append(Component.translatable("message.chartalandlords.label.stake",
                    bid, this.menu.nukeCount(), this.menu.grabCount(), this.menu.baseMultiplier()));
        }
        // AI 档位：多人局里每个人看到的都是同一档，写出来省得猜
        int difficulty = this.menu.aiDifficulty();
        if (difficulty >= 0 && difficulty < DoudizhuMenu.AI_DIFFICULTY_LABELS.length) {
            text = text.copy().append("  ").append(Component.translatable("message.chartalandlords.label.ai_level",
                    Component.translatable(DoudizhuMenu.AI_DIFFICULTY_LABELS[difficulty])));
        }
        return text;
    }

    /** 「当前选择 = 牌型（张数 · 可压/压不过）」，全部来自菜单同步下来的数据槽。 */
    private Component selectionText() {
        if (this.menu.phase() != DoudizhuGame.Phase.PLAYING) {
            return Component.empty();
        }
        int count = this.menu.selectionCount();
        if (count <= 0) {
            return Component.translatable("message.chartalandlords.label.selection_none");
        }
        int type = this.menu.selectionComboType();
        if (type < 0 || type >= ComboType.values().length) {
            return Component.translatable("message.chartalandlords.label.selection_invalid");
        }
        Component verdict = Component.translatable(this.menu.selectionBeatsLast()
                ? "message.chartalandlords.label.beatable"
                : "message.chartalandlords.label.not_beatable");
        Component text = Component.translatable("message.chartalandlords.label.selection",
                Component.translatable(ComboType.values()[type].descriptionKey()), count, verdict);
        // 提示轮换进度放在这里，而不是刷聊天栏（连按十次会刷屏）
        int hintTotal = this.menu.hintTotal();
        if (hintTotal > 1) {
            text = text.copy().append("  ").append(
                    Component.translatable("message.chartalandlords.label.hint_progress",
                            this.menu.hintIndex(), hintTotal));
        }
        return text;
    }

    private int selectionColor() {
        if (this.menu.selectionCount() <= 0) {
            return 0xFFA0A0A0;
        }
        int type = this.menu.selectionComboType();
        if (type < 0 || type >= ComboType.values().length) {
            return 0xFFFF8080;
        }
        return this.menu.selectionBeatsLast() ? 0xFF9AE59A : 0xFFFFB060;
    }

    private Component seatName(int seat) {
        CardPlayer player = this.menu.seatPlayer(seat);
        return player == null ? Component.literal("?") : player.getColoredName();
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 拖拽指示线画在最后，并且抬到与「手上牌」同一层（框架自己那套拿牌就是这么抬的），
        // 否则会被手牌盖住。拖拽时跟着鼠标走的那张牌由框架画（见 showDragCard）。
        drawInsertMarker(guiGraphics, mouseX, mouseY);
    }

    /**
     * 拖拽时的插入指示线。
     *
     * <p>分成两截画：手牌带<b>上面</b>那一截（箭头）落在空白的桌面上，一定看得见；
     * 带内那一截是竖线，画在 {@code z + 100}（和框架的「手上牌」同一层）。
     * 落点用 {@link DoudizhuMenu#cardLeftX} 算，和松手后的插入位置是同一个数。</p>
     */
    private void drawInsertMarker(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (dragHandIndex < 0 || !dragMoved) {
            return;
        }
        int target = handIndexAt(mouseX, mouseY);
        if (target < 0) {
            return;
        }
        int segment = target / DoudizhuValues.SEGMENT_CAPACITY;
        int indexInSegment = target % DoudizhuValues.SEGMENT_CAPACITY;
        if (segment < 0 || segment >= DoudizhuMenu.HAND_SEGMENT_COUNT) {
            return;
        }
        CardSlot<DoudizhuGame, DoudizhuMenu> slot =
                this.menu.cardSlots.get(DoudizhuMenu.handSlotOfSegment(segment));
        float x = DoudizhuMenu.cardLeftX(slot, height, panelLeft(), topPos, indexInSegment);
        if (indexInSegment > 0) {
            x -= 8f;
        }
        float[] bounds = DoudizhuMenu.slotBounds(slot, height, panelLeft(), topPos);
        int bandTop = (int) bounds[1];
        int bandBottom = (int) Math.min(height, bounds[1] + bounds[3]);
        int head = Math.max(SEAT_BAR_HEIGHT + 1, bandTop - 11);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0f, 0f, 100f);
        // 带内竖线
        guiGraphics.fill((int) x - 1, bandTop, (int) x + 1, bandBottom, 0xFFFFD54F);
        // 带外箭头：一眼看出松手会插到哪里
        int cx = (int) x;
        for (int step = 0; step < 6 && head + step < bandTop + 1; step++) {
            guiGraphics.fill(cx - (6 - step), head + step, cx + (6 - step), head + step + 1, 0xFFFFD54F);
        }
        guiGraphics.pose().popPose();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void renderBottomBar(@NotNull GuiGraphics guiGraphics) {
        // 自己的座位底色画在 renderBg 里（renderBottomBar 先于 renderBg，画在这里会被底衬盖掉）。
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int left = panelLeft();
        int right = left + DoudizhuMenu.PANEL_WIDTH;
        drawFelt(guiGraphics, left, right);

        drawBottomPreview(guiGraphics);
        drawCardCounter(guiGraphics);
        drawHandTint(guiGraphics);
        drawHandBand(guiGraphics);
        drawBand(guiGraphics, DoudizhuMenu.VISUAL_ROW_SELECT_FIRST, DoudizhuMenu.SELECT_ROW_COUNT,
                BAND_SELECT, Component.translatable("message.chartalandlords.band.select"),
                selectionDetail(), this.menu.selectionHasCards());
        drawBand(guiGraphics, DoudizhuMenu.VISUAL_ROW_PLAY_FIRST, DoudizhuMenu.PLAY_ROW_COUNT,
                BAND_PLAY, Component.translatable("message.chartalandlords.band.play"),
                lastPlayDetail(), this.menu.playBandHasCards());
        drawPlayOverflow(guiGraphics);
    }

    /**
     * 上一手到底是什么牌型、几张 —— 直接写在牌带标签下面。
     *
     * <p>牌带只露出牌的窄条，四张 10 的炸弹和一张 10 在缩略图上几乎一样；玩家反馈「AI 用 10 压 10」
     * 多半就是这么看出来的。把牌型与张数写在旁边，就不可能再误读。</p>
     */
    private Component lastPlayDetail() {
        int size = this.menu.lastPlaySize();
        int type = this.menu.lastComboType();
        if (size <= 0 || type < 0 || type >= ComboType.values().length) {
            return null;
        }
        return Component.translatable("message.chartalandlords.band.detail",
                Component.translatable(ComboType.values()[type].descriptionKey()), size);
    }

    /** 出牌区当前选牌是什么牌型、几张。 */
    private Component selectionDetail() {
        if (this.menu.phase() != DoudizhuGame.Phase.PLAYING) {
            return null;
        }
        int count = this.menu.selectionCount();
        int type = this.menu.selectionComboType();
        if (count <= 0 || type < 0 || type >= ComboType.values().length) {
            return null;
        }
        return Component.translatable("message.chartalandlords.band.detail",
                Component.translatable(ComboType.values()[type].descriptionKey()), count);
    }

    /**
     * 底牌：面板顶部右上角的一小块<b>小牌预览</b>（框架的 {@code PREVIEW} 卡槽画 3 张小牌），
     * 这里只负责给它铺底衬、写标签。
     *
     * <p>1.8.0 之前底牌独占一条整行牌带（52.5px + 11px 行距），而它整局只在开局看过一眼。
     * 改成小预览之后这 63px 全还给牌桌主界面——记牌器和明牌展示行都宽裕了，牌带也不必再挤。</p>
     */
    private void drawBottomPreview(GuiGraphics guiGraphics) {
        if (!this.menu.bottomBandHasCards()) {
            return;
        }
        int left = panelLeft() + DoudizhuMenu.BOTTOM_PREVIEW_X;
        int top = DoudizhuMenu.BOTTOM_PREVIEW_Y;
        // 标签写在预览左边，和预览同一行
        Component label = Component.translatable("message.chartalandlords.band.bottom");
        int labelWidth = font.width(label);
        int x0 = left - labelWidth - 6;
        if (x0 < panelLeft() + 4) {
            return;
        }
        frame(guiGraphics, x0 - 3, top - 2, left + PREVIEW_WIDTH + 3, top + PREVIEW_HEIGHT + 2, BAND_BOTTOM);
        guiGraphics.drawString(font, label, x0, top + 4, LABEL_TEXT, true);
    }

    /** 底牌小预览的尺寸（与 {@code CardSlot.Type.PREVIEW} 一致）。 */
    private static final int PREVIEW_WIDTH = 41;
    private static final int PREVIEW_HEIGHT = 18;

    /**
     * 记牌器的顶边：底牌预览那一行的正下方。
     *
     * <p>底牌预览是右上角的一小块，占的是本来就要留白的那条横带，所以「底牌改成小预览」等于
     * 纯赚——记牌器只需要从它下面开始，整条 52.5px 的牌带高度都还给了牌桌。</p>
     */
    private static int counterTop() {
        return DoudizhuMenu.BOTTOM_PREVIEW_Y + PREVIEW_HEIGHT + 2;
    }

    /**
     * 桌面本体：深色桌沿 + 自上而下 4 段微渐变的毡面。
     *
     * <p>比一整块纯色更像一张牌桌，成本只有 4 个 {@code fill}；左右桌沿给面板一个明确边界，
     * 深色世界里也不会糊成一片。</p>
     */
    private void drawFelt(GuiGraphics guiGraphics, int left, int right) {
        guiGraphics.fill(left - 4, SEAT_BAR_HEIGHT, right + 4, height, PANEL_RAIL);
        guiGraphics.fill(left - 4, SEAT_BAR_HEIGHT, left - 3, height, FRAME_HIGHLIGHT);
        guiGraphics.fill(right + 3, SEAT_BAR_HEIGHT, right + 4, height, FRAME_HIGHLIGHT);
        int top = SEAT_BAR_HEIGHT + 1;
        int bottom = height;
        int band = Math.max(1, (bottom - top) / FELT.length);
        for (int index = 0; index < FELT.length; index++) {
            int y0 = top + index * band;
            int y1 = index == FELT.length - 1 ? bottom : y0 + band;
            guiGraphics.fill(left, y0, right, y1, FELT[index]);
        }
        // 左右内侧高光：把毡面和桌沿分开
        guiGraphics.fill(left, top, left + 1, bottom, FRAME_HIGHLIGHT);
        guiGraphics.fill(right - 1, top, right, bottom, FRAME_HIGHLIGHT);
    }

    /**
     * 记牌器：每个点数还有几张没露面。
     *
     * <p>横向铺满面板、纵向从「底牌预览」下面开始，因此既填掉了面板上方的空地，
     * 也永远不会和那四个按钮抢位置。行数按可用高度自适应：空间够就 3 行 × 5 列（格子大、好读），
     * 不够就退化成 1 行 15 格，再不够才整块跳过（牌是 Charta 画的、跳不掉，底衬必须让路）。</p>
     */
    private void drawCardCounter(GuiGraphics guiGraphics) {
        int x0 = panelLeft() + COUNTER_PADDING;
        int x1 = panelLeft() + DoudizhuMenu.PANEL_WIDTH - COUNTER_PADDING;
        int y0 = counterTop();
        int stackTop = (int) DoudizhuMenu.visualRowScreenY(DoudizhuMenu.VISUAL_ROW_TOPMOST, height);
        int room = stackTop - 4 - y0 - HEADER_HEIGHT - 4;
        int rows = Math.min(3, room / COUNTER_CELL_HEIGHT);
        if (rows < 1) {
            return;
        }
        int columns = (DoudizhuMenu.DATA_UNSEEN_COUNT + rows - 1) / rows;
        int y1 = y0 + HEADER_HEIGHT + rows * COUNTER_CELL_HEIGHT + 4;
        framedGroup(guiGraphics, x0, y0, x1, y1, 0xE0101810, 0xFFB0BEC5);
        Component title = Component.translatable("message.chartalandlords.counter.title");
        guiGraphics.drawString(font, title, x0 + 4, y0 + 2, LABEL_TEXT, true);
        drawBgmBadge(guiGraphics, x1);
        drawHandViewBadge(guiGraphics, x1);

        int gridLeft = x0 + 3;
        int gridTop = y0 + HEADER_HEIGHT + 3;
        int cellWidth = Math.max(10, (x1 - 3 - gridLeft) / columns);
        for (int index = 0; index < DoudizhuMenu.DATA_UNSEEN_COUNT; index++) {
            int value = RuleEngine.MIN_VALUE + index;
            int count = this.menu.unseenCount(value);
            boolean alive = count > 0;
            int cx = gridLeft + (index % columns) * cellWidth;
            int cy = gridTop + (index / columns) * COUNTER_CELL_HEIGHT;
            guiGraphics.fill(cx, cy, cx + cellWidth - 2, cy + COUNTER_CELL_HEIGHT - 2,
                    alive ? 0xA8182418 : 0x68101410);
            guiGraphics.fill(cx, cy, cx + cellWidth - 2, cy + 1, alive ? 0x40FFFFFF : 0x20FFFFFF);
            String rank = rankLabel(value);
            String amount = Integer.toString(count);
            guiGraphics.drawString(font, rank, cx + 3, cy + 3, alive ? COUNTER_RANK : COUNTER_RANK_EMPTY, true);
            guiGraphics.drawString(font, amount, cx + cellWidth - 5 - font.width(amount), cy + 3,
                    alive ? COUNTER_COUNT : COUNTER_COUNT_EMPTY, true);
        }
    }

    /**
     * 记牌器标题行右侧的 ♪ 徽章：显示 BGM 开关状态，点一下就切换。
     *
     * <p>做成屏幕内的可点徽章而不是快捷键，是为了不占用玩家的按键、也不需要注册 keybind；
     * 它画在 Charta 那四个按钮横带<b>下方</b>的面板里，与牌带、按钮都不重叠。</p>
     */
    private void drawBgmBadge(GuiGraphics guiGraphics, int counterRight) {
        bgmBadgeBounds = null;
        int y0 = counterTop();
        Component badge = Component.translatable(DoudizhuBgm.isEnabled()
                ? "message.chartalandlords.bgm.on"
                : "message.chartalandlords.bgm.off");
        int badgeWidth = font.width(badge) + 6;
        int badgeX = counterRight - 4 - badgeWidth;
        if (badgeX < panelLeft() + 4) {
            return;
        }
        int badgeY = y0 + 1;
        guiGraphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 9,
                DoudizhuBgm.isEnabled() ? 0xB0203A22 : 0x90101010);
        guiGraphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 1,
                DoudizhuBgm.isEnabled() ? 0x60FFD54F : 0x30FFFFFF);
        guiGraphics.drawString(font, badge, badgeX + 3, badgeY + 1,
                DoudizhuBgm.isEnabled() ? TURN_EDGE : SCORE_ZERO, true);
        bgmBadgeBounds = new int[]{badgeX, badgeY, badgeWidth, 9};
    }

    /** ♪ 徽章的热区（{@code [x, y, w, h]}）；未绘制时为 null。 */
    private int[] bgmBadgeBounds;

    /** 鼠标是否点在 ♪ 徽章上。 */
    private boolean isOverBgmBadge(double mouseX, double mouseY) {
        int[] bounds = bgmBadgeBounds;
        return bounds != null && mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
    }

    /**
     * 记牌器标题行右侧的「看牌」徽章：点开每家的手牌界面（键盘 V 等效）。
     *
     * <p>放在 ♪ 徽章左边，和它同一行——这一条横带是面板上方本来就空着的地方，
     * 不占牌带、也不压 Charta 那四个按钮。</p>
     */
    private void drawHandViewBadge(GuiGraphics guiGraphics, int counterRight) {
        handViewBadgeBounds = null;
        int y0 = counterTop();
        Component badge = Component.translatable("message.chartalandlords.hand_view.open");
        int badgeWidth = font.width(badge) + 6;
        int right = bgmBadgeBounds != null ? bgmBadgeBounds[0] - 4 : counterRight - 4;
        int badgeX = right - badgeWidth;
        if (badgeX < panelLeft() + 4) {
            return;
        }
        int badgeY = y0 + 1;
        guiGraphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 9, 0xB0202A3A);
        guiGraphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + 1, 0x40FFFFFF);
        guiGraphics.drawString(font, badge, badgeX + 3, badgeY + 1, 0xFFD8E0E8, true);
        handViewBadgeBounds = new int[]{badgeX, badgeY, badgeWidth, 9};
    }

    /** 鼠标是否点在「看牌」徽章上。 */
    private boolean isOverHandViewBadge(double mouseX, double mouseY) {
        int[] bounds = handViewBadgeBounds;
        return bounds != null && mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
    }

    /** 记牌器/日志用的短点数标签：只用 1~2 个字符，保证 15 格能排进面板宽度。 */
    private static String rankLabel(int value) {
        return switch (value) {
            case RuleEngine.SMALL_JOKER_VALUE -> "小";
            case RuleEngine.BIG_JOKER_VALUE -> "大";
            case RuleEngine.TWO_VALUE -> "2";
            case RuleEngine.A_VALUE -> "A";
            case 13 -> "K";
            case 12 -> "Q";
            case 11 -> "J";
            default -> Integer.toString(value);
        };
    }

    /** 自己座位的底色（对齐 Charta 的 renderBottomBar：两侧压暗、中间是自己的染色）。 */
    private void drawHandTint(GuiGraphics guiGraphics) {
        CardPlayer player = this.menu.getCardPlayer();
        int color = player == null ? 0xFFFFFF : player.getColor().getTextureDiffuseColor() & 0xFFFFFF;
        int top = (int) DoudizhuMenu.visualRowScreenY(DoudizhuMenu.VISUAL_ROW_HAND_BACK, height);
        int left = panelLeft();
        guiGraphics.fill(0, top, left, height, 0x88000000);
        guiGraphics.fill(left, top, left + DoudizhuMenu.PANEL_WIDTH, height, 0x88000000 | color);
        guiGraphics.fill(left + DoudizhuMenu.PANEL_WIDTH, top, width, height, 0x88000000);
    }

    /** 手牌带：两行两段连成一块，前排（下面那行）序号更大、画得更晚，压住后排底部。 */
    private void drawHandBand(GuiGraphics guiGraphics) {
        int top = handBandTop();
        int frontTop = (int) DoudizhuMenu.visualRowScreenY(DoudizhuMenu.VISUAL_ROW_HAND_FRONT, height);
        if (top < SEAT_BAR_HEIGHT + 2) {
            return;
        }
        boolean active = this.menu.handHasCards();
        int left = panelLeft();
        int x0 = left + (int) DoudizhuMenu.SEGMENT_LEFT_X - BAND_PADDING_X;
        int x1 = left + (int) DoudizhuMenu.SEGMENT_RIGHT_X + (int) DoudizhuMenu.ROW_WIDTH + BAND_PADDING_X;
        int bottom = Math.min((int) (frontTop + DoudizhuMenu.ROW_HEIGHT), height);
        frame(guiGraphics, x0, top, x1, bottom, active ? BAND_HAND : dim(BAND_HAND));
        // 两行之间的分界，让「两行」一眼可辨
        guiGraphics.fill(x0 + 1, frontTop - 1, x1 - 1, frontTop, 0x60FFFFFF);
        drawBandLabel(guiGraphics, Component.translatable("message.chartalandlords.band.hand"),
                left + BAND_LABEL_X, top - BAND_LABEL_HEIGHT - 2, active, BAND_HAND);
    }

    /**
     * 画一条单段带（出牌区 / 上一手 / 底牌）：行区连成一块内凹底衬 + 左侧标签（可带一行牌型明细）。
     * 带被挤出面板（撞到座位条）时整条跳过，不画像素垃圾。
     */
    private void drawBand(GuiGraphics guiGraphics, int firstVisualRow, int rowCount, int color, Component label,
                          Component detail, boolean active) {
        int top = (int) DoudizhuMenu.visualRowScreenY(firstVisualRow + rowCount - 1, height);
        int bottom = (int) (DoudizhuMenu.visualRowScreenY(firstVisualRow, height) + DoudizhuMenu.ROW_HEIGHT);
        if (top < SEAT_BAR_HEIGHT + 2 || bottom <= SEAT_BAR_HEIGHT) {
            return;
        }
        int x = panelLeft() + (int) DoudizhuMenu.ROW_X - BAND_PADDING_X;
        int width = (int) DoudizhuMenu.ROW_WIDTH + BAND_PADDING_X * 2;
        int clippedBottom = Math.min(bottom, height);
        frame(guiGraphics, x, top, x + width, clippedBottom, active ? color : dim(color));
        // 空带画一条虚线式的浅色提示框，说明「这里可以放牌」
        if (!active) {
            int inner = x + 2;
            for (int px = inner; px < x + width - 2; px += 4) {
                guiGraphics.fill(px, clippedBottom - 2, Math.min(px + 2, x + width - 2), clippedBottom - 1,
                        0x30FFFFFF);
            }
        }
        int labelY = (int) DoudizhuMenu.visualRowScreenY(firstVisualRow, height) + 4;
        drawBandLabel(guiGraphics, label, panelLeft() + BAND_LABEL_X, labelY, active, color);
        if (detail != null) {
            // 明细写在标签正下方：左侧空白栏够宽（牌从 ROW_X 才开始），不会压到牌
            guiGraphics.drawString(font, detail, panelLeft() + BAND_LABEL_X, labelY + BAND_LABEL_HEIGHT + 2,
                    active ? 0xFFC8DCC8 : 0xFF7F8F7F, true);
        }
    }

    /** 带标签：内凹小牌子 + 左侧强调色竖条，有牌时用强调色点亮。 */
    private void drawBandLabel(GuiGraphics guiGraphics, Component label, int x, int y, boolean active, int color) {
        if (y < SEAT_BAR_HEIGHT + 1 || y + BAND_LABEL_HEIGHT > height) {
            return;
        }
        int textWidth = font.width(label);
        frame(guiGraphics, x - 3, y - 1, x + textWidth + 3, y + BAND_LABEL_HEIGHT, active ? 0xD8121A12 : 0x98101410);
        guiGraphics.fill(x - 2, y, x - 1, y + BAND_LABEL_HEIGHT - 1,
                active ? accented(color) : 0x60FFFFFF);
        guiGraphics.drawString(font, label, x, y, active ? LABEL_TEXT : 0xFF8A9A8A, true);
    }

    /** 超过展示容量的上一手牌：明确写出还有几张没画出来，绝不静默丢牌。 */
    private void drawPlayOverflow(GuiGraphics guiGraphics) {
        int hidden = this.menu.lastPlaySize() - DoudizhuMenu.PLAY_BAND_CAPACITY;
        if (hidden <= 0) {
            return;
        }
        Component badge = Component.translatable("message.chartalandlords.label.play_overflow", hidden);
        int badgeWidth = font.width(badge);
        int y = (int) DoudizhuMenu.visualRowScreenY(DoudizhuMenu.VISUAL_ROW_PLAY_FIRST, height) + 3;
        if (y < SEAT_BAR_HEIGHT + 1 || y + 9 > height) {
            return;
        }
        int x = panelLeft() + (int) (DoudizhuMenu.ROW_X + DoudizhuMenu.ROW_WIDTH) - badgeWidth - 4;
        guiGraphics.fill(x - 2, y - 1, x + badgeWidth + 2, y + 9, 0xCC7F0000);
        guiGraphics.drawString(font, badge, x, y, TURN_EDGE, true);
    }

    /** 带内没有牌时把底衬压暗，有牌时才显眼。 */
    private static int dim(int color) {
        int alpha = (color >>> 24) * 2 / 3;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /**
     * 座位名颜色：座位盒是半透明的，底色可能很亮（白/黄羊毛）也可能很暗（黑羊毛）。
     * 亮的底色用深色字，暗的底色用亮色字，保证两边都能看清。
     */
    private static int nameColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int luminance = (r * 299 + g * 587 + b * 114) / 1000;
        return luminance < 128 ? 0xFFFFFFFF : 0xFF101010;
    }

    // ------------------------------------------------------------------ 看牌界面

    /**
     * 打开「看牌」界面。
     *
     * <p>是<b>换一个 Screen</b>，不是在本界面上盖一层：Charta 自己的牌堆 / 历史界面就是这么做的。
     * 本界面在子界面打开期间根本不渲染，所以框架画的卡牌与按钮不可能压到它上面去，
     * 顺带还白拿了卡牌 tooltip、悬停立体感与满屏空间。</p>
     */
    private void openHandsScreen() {
        if (this.minecraft == null) {
            return;
        }
        // removed() 会在这一刻被调用：BGM 交给子界面继续 tick，不要在这里停掉
        keepMusicOnRemoval = true;
        this.minecraft.setScreen(new DoudizhuHandsScreen(this, this.menu));
    }

    /** 子界面（看牌）开着的时候也要继续推 BGM：牌局界面不 tick，但音乐不该断。 */
    void tickMusic() {
        this.bgm.tick();
    }

    @Override
    public void removed() {
        // 关界面（或切到看牌界面）时把拖拽状态与「手上牌」一起清掉，
        // 否则下次打开界面会有一张牌粘在鼠标上。
        clearDrag();
        if (!keepMusicOnRemoval) {
            this.bgm.stop();
        }
        keepMusicOnRemoval = false;
        super.removed();
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isOverButton(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // ♪ 徽章：切换 BGM（不占用按键，也不用注册 keybind）
        if (button == 0 && isOverBgmBadge(mouseX, mouseY)) {
            DoudizhuBgm.toggle();
            return true;
        }
        // 「看牌」徽章：打开每家的手牌界面（等效快捷键：V）
        if (button == 0 && isOverHandViewBadge(mouseX, mouseY)) {
            openHandsScreen();
            return true;
        }
        // 右键：把出牌区里选中的牌一次性撤回手牌
        if (button == 1) {
            if (this.menu.selectionCount() > 0) {
                PacketDistributor.sendToServer(GameActionPayload.of(this.menu.containerId, DoudizhuAction.RETRACT));
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 左键：自己算命中槽位（跳过空槽、取最上层非空槽），避免空行抢走点击导致下标错位
        int left = panelLeft();
        int slotId = DoudizhuMenu.resolveTopSlotIndex(this.menu.cardSlots, height, left, topPos, mouseX, mouseY);
        if (DoudizhuMenu.isSelectableSlot(slotId)) {
            // 注意：这里**不**用框架记录的 hoveredCardId 兜底。那个牌号属于「框架认为鼠标悬停的那个槽」，
            // 在上下行叠压的牌带里可能正好是**另一行**的槽，拼起来就会变成
            // 「点第二行的牌、第一行的牌进了出牌区」。resolveCardIndex 现在保证落在行内就一定有下标。
            int cardId = DoudizhuMenu.resolveCardIndex(this.menu.cardSlots.get(slotId), height, left, topPos,
                    mouseX, mouseY);
            if (cardId >= 0) {
                if (DoudizhuMenu.isHandSlot(slotId)) {
                    // 手牌：先不急着选，等松手再决定这是「点击（选牌）」还是「拖拽（排序）」。
                    // 出手牌的两种意图共用同一个按键，只能靠移动距离区分——这也是所有
                    // 拖放界面的做法，正常点击的按下与松开间隔只有几十毫秒，感觉不到延迟。
                    dragHandIndex = DoudizhuMenu.handIndex(DoudizhuMenu.handSegmentOfSlot(slotId), cardId);
                    dragStartX = mouseX;
                    dragStartY = mouseY;
                    dragMoved = false;
                    return true;
                }
                PacketDistributor.sendToServer(new SelectCardPayload(this.menu.containerId, slotId, cardId));
                return true;
            }
        }
        if (slotId >= 0) {
            // 点在自己画的牌带上（上一手 / 底牌 / 明牌展示行）：这些带是只读的，
            // 吃掉这一下，别让框架的「拿起牌」流程插手。
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragHandIndex >= 0) {
            if (!dragMoved
                    && (Math.abs(mouseX - dragStartX) > DRAG_THRESHOLD
                    || Math.abs(mouseY - dragStartY) > DRAG_THRESHOLD)) {
                dragMoved = true;
                // 判定成拖拽的那一刻，把这张牌放进框架的「手上牌」槽：那一条的绘制在牌局界面的最上层
                // （z+100），所以牌是真的跟着鼠标走、并且压在手牌之上——自己画的那一版会被手牌盖住。
                showDragCard();
            }
            // 拖拽期间自己吃掉事件，别让上游的「拿起牌」流程插手
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /**
     * 把正在拖的那张牌放进框架的「手上牌」槽（只在本客户端，不会发给服务端）。
     *
     * <p>{@code GameScreen.render} 在最后用 {@code z=100} 画这个槽，且画的就是鼠标位置、
     * 牌心对光标——正是「拿着一张牌在手上」的观感。放进去的是 {@code copy()}，
     * 免得框架的任何流程动到真手牌。</p>
     */
    private void showDragCard() {
        Card card = this.menu.handCard(dragHandIndex);
        if (card == null) {
            return;
        }
        this.menu.getCarriedCards().setCards(new ArrayList<>(List.of(card.copy())));
        dragCarried = true;
    }

    /** 拖拽结束（成功、取消、或当成点击）时把「手上牌」清掉。 */
    private void hideDragCard() {
        if (dragCarried) {
            this.menu.getCarriedCards().setCards(new ArrayList<>());
            dragCarried = false;
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragHandIndex >= 0) {
            int source = dragHandIndex;
            boolean moved = dragMoved;
            clearDrag();
            if (!moved) {
                // 没怎么移动 = 一次普通点击：当作选牌
                selectHandIndex(source);
                return true;
            }
            int target = handIndexAt(mouseX, mouseY);
            if (target >= 0 && target != source) {
                PacketDistributor.sendToServer(
                        new MoveCardPayload(this.menu.containerId, source, target));
            } else if (target < 0) {
                // 拖到了手牌以外的空白处：当作一次普通点击，别把这次操作吞掉
                selectHandIndex(source);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 鼠标松开时落在手牌的哪个位置（-1 = 没落在手牌上）。 */
    private int handIndexAt(double mouseX, double mouseY) {
        int left = panelLeft();
        int slotId = DoudizhuMenu.resolveTopSlotIndex(this.menu.cardSlots, height, left, topPos, mouseX, mouseY);
        if (DoudizhuMenu.isHandSlot(slotId)) {
            int segment = DoudizhuMenu.handSegmentOfSlot(slotId);
            int cardId = DoudizhuMenu.resolveCardIndex(this.menu.cardSlots.get(slotId), height, left, topPos,
                    mouseX, mouseY);
            // 落在空段上就插到那一段的开头，这样「拖到空白处」也有确定含义（不是无事发生）
            return cardId < 0 ? DoudizhuMenu.handIndex(segment, 0)
                    : DoudizhuMenu.handIndex(segment, cardId);
        }
        // 落在别的段里：取该段第一个非空槽，插到那一段的开头
        for (int segment = 0; segment < DoudizhuMenu.HAND_SEGMENT_COUNT; segment++) {
            if (DoudizhuMenu.within(DoudizhuMenu.slotBounds(
                            this.menu.cardSlots.get(DoudizhuMenu.handSlotOfSegment(segment)), height, left, topPos),
                    mouseX, mouseY)) {
                return DoudizhuMenu.handIndex(segment, 0);
            }
        }
        return -1;
    }

    /** 把整副手牌里第 {@code handIndex} 张选进出牌区（把整副下标换算回「段 + 段内下标」）。 */
    private void selectHandIndex(int handIndex) {
        int segment = handIndex / DoudizhuValues.SEGMENT_CAPACITY;
        int indexInSegment = handIndex % DoudizhuValues.SEGMENT_CAPACITY;
        if (segment < 0 || segment >= DoudizhuMenu.HAND_SEGMENT_COUNT) {
            return;
        }
        if (handIndex >= this.menu.seatHandSize(this.menu.mySeat())) {
            return;
        }
        PacketDistributor.sendToServer(new SelectCardPayload(this.menu.containerId,
                DoudizhuMenu.handSlotOfSegment(segment), indexInSegment));
    }

    private void clearDrag() {
        dragHandIndex = -1;
        dragMoved = false;
        hideDragCard();
    }

    /**
     * 键盘快捷出牌：回车 = 出牌、空格 = 不出、V = 看牌。
     *
     * <p>按钮列在屏幕右侧，每次出牌都要把鼠标横跨半个屏幕，是「出牌手感」里最累的一环。
     * 这些键覆盖了绝大多数操作，而且不用注册 keybind（屏幕自己处理即可）。</p>
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // V：打开看牌界面（小窗口下记牌器可能整块画不出来，徽章就没了，所以留一个键）
        if (keyCode == GLFW.GLFW_KEY_V) {
            openHandsScreen();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.menu.canPlayNow()) {
                sendAction(DoudizhuAction.PLAY);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE && this.menu.canPassNow()) {
            sendAction(DoudizhuAction.PASS);
            return true;
        }
        return false;
    }

    private boolean isOverButton(double mouseX, double mouseY) {
        for (Button button : bidButtons) {
            if (button.visible && isOver(button, mouseX, mouseY)) {
                return true;
            }
        }
        for (Button button : playButtons) {
            if (button.visible && isOver(button, mouseX, mouseY)) {
                return true;
            }
        }
        for (Button button : declareButtons) {
            if (button.visible && isOver(button, mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOver(AbstractWidget widget, double mouseX, double mouseY) {
        return mouseX >= widget.getX() && mouseX < widget.getX() + widget.getWidth()
                && mouseY >= widget.getY() && mouseY < widget.getY() + widget.getHeight();
    }
}
