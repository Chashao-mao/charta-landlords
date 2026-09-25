package chartalandlords.doudizhu.game;

import chartalandlords.doudizhu.game.engine.RuleEngine;
import chartalandlords.doudizhu.registry.Menus;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 斗地主界面容器：整个界面的几何布局都定义在这里，{@code DoudizhuScreen} 只消费这些常量。
 *
 * <h2>视觉行与绘制顺序</h2>
 * <p>Charta 的 {@code GameScreen.render} 按 {@code menu.cardSlots} 的下标顺序绘制，下标越大画得越晚、
 * 越靠上层。所以「注册顺序 = 绘制顺序」，而槽位序号也就是注册顺序。牌行高 52.5 而行距只有 11~14，
 * 相邻行必然互相叠压，于是唯一的规则是：</p>
 *
 * <blockquote><b>屏幕位置越靠下的行，序号越大、画得越晚。</b></blockquote>
 *
 * <p>这样每一行被上面那行压住后，露出的都是<b>左上角点数</b>，而不是一条底部花色：</p>
 * <ul>
 *   <li><b>手牌</b>：后排（段 2、3）先画，前沿行（段 0、1）后画 → 后排露出点数，最下面一行完全可见。</li>
 *   <li><b>上一手</b>：底牌位置最高、最先画，压不到上一手 → 上一手最低行 52.5px 全部可见。</li>
 *   <li><b>长牌型</b>：出牌区 / 上一手需要 2~4 行时（&gt;10 张），带内也是下面的行后画，逐行露出点数。</li>
 * </ul>
 *
 * <p>因为注册顺序不再等于「带的自下而上顺序」，槽位序号与「手牌段 / 出牌区行 / 上一手行」的对应关系
 * 由 {@link #handSegmentOfSlot}、{@link #selectRowOfSlot}、{@link #playRowOfSlot} 显式给出，
 * 绝不在别处用算术推。全屏绘制序列见 {@link #SLOT_BOTTOM} 的说明。</p>
 */
public class DoudizhuMenu extends AbstractCardMenu<DoudizhuGame, DoudizhuMenu> {

    // ------------------------------------------------------------------ 面板

    /** 面板宽度：两段各 150 宽 + 12 间距 + 左右各 4 的内边距。 */
    public static final int PANEL_WIDTH = 320;
    /** 面板高度：只用于 {@code AbstractContainerScreen} 的水平居中；行是按屏幕底部锚定的，与它无关。 */
    public static final int PANEL_HEIGHT = 210;

    // ------------------------------------------------------------------ 行的几何

    /** 单行（HORIZONTAL 卡槽）的宽与高：150 × 52.5。 */
    public static final float ROW_WIDTH = CardSlot.getWidth(CardSlot.Type.HORIZONTAL);
    public static final float ROW_HEIGHT = CardSlot.getHeight(CardSlot.Type.HORIZONTAL);

    /** 手牌左段（段 0 = 前行左半、段 2 = 后排左半）的 x，面板内坐标。 */
    public static final float SEGMENT_LEFT_X = 4f;
    /** 手牌左右两段之间的间隙。 */
    public static final float HAND_SEGMENT_GAP = 12f;
    /** 手牌右段的 x。 */
    public static final float SEGMENT_RIGHT_X = SEGMENT_LEFT_X + ROW_WIDTH + HAND_SEGMENT_GAP;
    /** 单段行（出牌区 / 上一手 / 底牌）在面板内居中。 */
    public static final float ROW_X = (PANEL_WIDTH - ROW_WIDTH) / 2f;

    /** 最下面一行（手牌前行）距屏幕底部的高度。 */
    public static final float ROW_BOTTOM_MARGIN = 2f;
    /** 手牌两行之间的间距：后排手牌露出的顶部高度，必须容得下左上角的点数。 */
    public static final float HAND_ROW_GAP = 14f;
    /** 手牌上沿到出牌区最低行的距离：正好一行高，出牌区有牌时也盖不到手牌点数。 */
    public static final float HAND_SELECT_GAP = ROW_HEIGHT;
    /** 出牌区内部行距（第 2、3 行只在超过 10 张时才用到）。 */
    public static final float SELECT_ROW_GAP = 14f;
    /** 出牌区最高行到上一手最低行的距离。 */
    public static final float SELECT_PLAY_GAP = 14f;
    /** 上一手内部行距。 */
    public static final float PLAY_ROW_GAP = 13f;
    /** 自下而上的行距表：{@code ROW_GAPS[i]} = 视觉行 i+1 到视觉行 i 的高度差。 */
    private static final float[] ROW_GAPS = {
            HAND_ROW_GAP,
            HAND_SELECT_GAP,
            SELECT_ROW_GAP,
            SELECT_ROW_GAP,
            SELECT_PLAY_GAP,
            PLAY_ROW_GAP,
            PLAY_ROW_GAP
    };

    /** 手牌槽位数量：2 行 × 每行左右 2 段。 */
    public static final int HAND_SEGMENT_COUNT = DoudizhuValues.HAND_ROWS * DoudizhuValues.HAND_SEGMENTS_PER_ROW;
    public static final int SELECT_ROW_COUNT = DoudizhuValues.SELECT_ROWS;
    /** 单次出牌最长牌型张数：4 人局「连对十二连」= 12 个点数 × 2 张。 */
    public static final int MAX_PLAY_SIZE = 24;
    /**
     * 上一手牌展示行数：按最长牌型与单行容量取整，至少保留 {@link DoudizhuValues#PLAY_ROWS} 行。
     * 24 张需要 3 行（3 × {@link DoudizhuValues#ROW_CAPACITY} = 30），不会再像 2 行那样静默丢牌。
     */
    public static final int PLAY_ROW_COUNT = Math.max(DoudizhuValues.PLAY_ROWS,
            (MAX_PLAY_SIZE + DoudizhuValues.ROW_CAPACITY - 1) / DoudizhuValues.ROW_CAPACITY);
    /** 上一手牌最多能显示的张数。 */
    public static final int PLAY_BAND_CAPACITY = PLAY_ROW_COUNT * DoudizhuValues.ROW_CAPACITY;

    /** 视觉行总数（自下而上）：手牌 2 + 出牌区 3 + 上一手 3。 */
    public static final int VISUAL_ROWS = ROW_GAPS.length + 1;

    /** 各视觉行（0 = 最下面一行）相对最下面一行的 y 偏移。 */
    private static final float[] ROW_OFFSETS = buildRowOffsets();

    /** 视觉行序号：手牌前沿行（完全可见）。 */
    public static final int VISUAL_ROW_HAND_FRONT = 0;
    /** 视觉行序号：手牌后排（被前沿行压住底部，露出顶部）。 */
    public static final int VISUAL_ROW_HAND_BACK = VISUAL_ROW_HAND_FRONT + 1;
    /** 视觉行序号：出牌区第 1 行（最下面那一行）。 */
    public static final int VISUAL_ROW_SELECT_FIRST = VISUAL_ROW_HAND_BACK + 1;
    /** 视觉行序号：上一手第 1 行（最下面那一行）。 */
    public static final int VISUAL_ROW_PLAY_FIRST = VISUAL_ROW_SELECT_FIRST + SELECT_ROW_COUNT;
    /** 牌带区最上面那一行（上一手的最高行）。 */
    public static final int VISUAL_ROW_TOPMOST = VISUAL_ROW_PLAY_FIRST + PLAY_ROW_COUNT - 1;

    private static float[] buildRowOffsets() {
        float[] offsets = new float[VISUAL_ROWS];
        float accumulated = 0f;
        for (int row = 1; row < VISUAL_ROWS; row++) {
            accumulated += ROW_GAPS[row - 1];
            offsets[row] = accumulated;
        }
        return offsets;
    }

    /** 视觉行（0 = 最下面一行）对应的 slot.y。 */
    public static float visualRowY(int visualRow) {
        int row = Math.max(0, Math.min(VISUAL_ROWS - 1, visualRow));
        return -ROW_BOTTOM_MARGIN - ROW_OFFSETS[row];
    }

    /** HORIZONTAL 行的 slot.y 换算成屏幕 y（底部锚定，与 Charta 的 isHoveringPrecise 同一套）。 */
    public static float slotScreenY(float slotY, int screenHeight) {
        return slotY + screenHeight - ROW_HEIGHT;
    }

    /** 视觉行在屏幕上的 y（行顶边）。绘制与命中判定共用，保证两边不会漂移。 */
    public static float visualRowScreenY(int visualRow, int screenHeight) {
        return slotScreenY(visualRowY(visualRow), screenHeight);
    }

    /** 面板左边界（屏幕坐标）：与 {@code AbstractContainerScreen} 居中的 leftPos 完全一致。 */
    public static int panelLeft(int screenWidth) {
        return (screenWidth - PANEL_WIDTH) / 2;
    }

    /** 出牌区第 row 行所在的视觉行。 */
    public static int selectVisualRow(int row) {
        return VISUAL_ROW_SELECT_FIRST + Math.max(0, Math.min(SELECT_ROW_COUNT - 1, row));
    }

    /** 上一手第 row 行所在的视觉行。 */
    public static int playVisualRow(int row) {
        return VISUAL_ROW_PLAY_FIRST + Math.max(0, Math.min(PLAY_ROW_COUNT - 1, row));
    }

    /** 手牌第 segment 段的水平位置。 */
    public static float handSegmentX(int segment) {
        return segment % DoudizhuValues.HAND_SEGMENTS_PER_ROW == 0 ? SEGMENT_LEFT_X : SEGMENT_RIGHT_X;
    }

    /** 手牌第 segment 段所在的视觉行：0 = 前沿行（完全可见），1 = 后排（露出顶部）。 */
    public static int handSegmentRow(int segment) {
        return segment / DoudizhuValues.HAND_SEGMENTS_PER_ROW;
    }

    // ------------------------------------------------------------------ 槽位序号与「注册顺序 = 绘制顺序」

    /**
     * 全局绘制顺序 = 注册顺序 = 槽位序号，规则是 <b>屏幕位置越靠下，序号越大、画得越晚</b>。
     *
     * <p>牌行高 52.5，而行距只有 11~14，所以相邻行必然互相叠压。谁后画谁就盖住对方的哪一半：
     * 让下面那行后画，上面那行就露出<b>左上角点数</b>；反过来就只剩一条底部花色。</p>
     *
     * <p>这同时解决两件事：</p>
     * <ol>
     *   <li><b>手牌</b>：后排（段 2、3）先画，前沿行（段 0、1）后画，后排露出左上角点数，最下面一行完全可见。</li>
     *   <li><b>上一手不被底牌压住</b>：底牌位置最高、最先画，于是它只影响画面最上方，
     *       上一手最低行 52.5px 全部可见（原先被底牌带压掉顶部 15.5px）。</li>
     * </ol>
     *
     * <p>各带内部同样「下面的行后画」：出牌区 / 上一手在需要 2~4 行时（&gt;10 张的长牌型），
     * 每一行都露出左上角点数，而不是只露一条底部花色。</p>
     *
     * <p>全屏自上而下的绘制序列：<b>明牌展示 4 行（高→低）</b> → 上一手第 3/2/1 行 →
     * 出牌区第 3/2/1 行 → 手牌后排 → 手牌前沿行。</p>
     *
     * <p>底牌不再占一条整行：1.8.0 起它和记牌器一样画成面板顶部的<b>小牌预览</b>
     * （{@link CardSlot.Type#PREVIEW}，41×17.5），把那 63px 的整行高度还给牌桌主界面。</p>
     */
    /**
     * 明牌展示槽数量：每个座位一个，只有该座位明牌之后才有牌。
     *
     * <p>它们<b>不画在界面上</b>：只当数据通道用，给「看牌」界面读别人的手牌
     * （摆在 {@link #OFF_SCREEN_Y}，看不见也点不到）。</p>
     */
    public static final int REVEAL_ROW_COUNT = DoudizhuValues.MAX_PLAYERS;
    public static final int SLOT_PLAY_START = 0;
    public static final int SLOT_SELECT_START = SLOT_PLAY_START + PLAY_ROW_COUNT;
    public static final int SLOT_HAND_START = SLOT_SELECT_START + SELECT_ROW_COUNT;
    public static final int SLOT_COUNT = SLOT_HAND_START + HAND_SEGMENT_COUNT;

    /**
     * 底牌预览槽的序号。
     *
     * <p>它是唯一一个 {@code PREVIEW} 类型的槽，坐标是<b>屏幕绝对 y</b>（框架对 PREVIEW 就是这么摆的），
     * 所以不参与「槽位序号越大越靠下」那条不变量，单独放在牌带之后。</p>
     */
    public static final int SLOT_BOTTOM_PREVIEW = SLOT_COUNT;
    /** 明牌展示槽（数据通道）的起始序号。 */
    public static final int SLOT_REVEAL_START = SLOT_BOTTOM_PREVIEW + 1;
    /** 全部卡槽数量（含底牌预览与明牌数据槽）。 */
    public static final int SLOT_COUNT_TOTAL = SLOT_REVEAL_START + REVEAL_ROW_COUNT;

    /** 数据通道槽位摆的 y：屏幕高只有几百，十万一定在屏幕外，永远看不见也点不到。 */
    public static final int OFF_SCREEN_Y = 100_000;

    /** 底牌预览在面板内的 x（右对齐）与屏幕绝对 y。 */
    public static final int BOTTOM_PREVIEW_X = PANEL_WIDTH - 8 - (int) CardSlot.getWidth(CardSlot.Type.PREVIEW);
    public static final int BOTTOM_PREVIEW_Y = 56;

    /** 座位 {@code seat} 的明牌数据槽序号（数据通道，不在界面上）。 */
    public static int revealSlotOfSeat(int seat) {
        return SLOT_REVEAL_START + Math.max(0, Math.min(REVEAL_ROW_COUNT - 1, seat));
    }

    /** 明牌数据槽序号对应的座位。 */
    public static int revealSeatOfSlot(int slotId) {
        return Math.max(0, Math.min(REVEAL_ROW_COUNT - 1, slotId - SLOT_REVEAL_START));
    }

    public static boolean isRevealSlot(int slotId) {
        return slotId >= SLOT_REVEAL_START && slotId < SLOT_REVEAL_START + REVEAL_ROW_COUNT;
    }

    /**
     * 手牌注册顺序 → 手牌段。先后排（段 2、3）再前沿行（段 0、1）：前沿行序号更大、画得更晚，
     * 于是它压住后排的底部，后排露出左上角点数。
     */
    private static final int[] HAND_SLOT_SEGMENTS = buildHandSlotSegments();

    /**
     * 带内注册顺序 → 带内的行号（0 = 贴近下方那一行）。
     *
     * <p>必须<b>倒序</b>：行号越大位置越高，只有让它先注册（序号小、先画），下面那一行才能后画并压住它，
     * 于是它露出左上角点数。出牌区、上一手共用这张表。</p>
     */
    private static int[] buildRowRegisterOrder(int rowCount) {
        int[] order = new int[rowCount];
        for (int index = 0; index < rowCount; index++) {
            order[index] = rowCount - 1 - index;
        }
        return order;
    }

    private static final int[] SELECT_SLOT_ROWS = buildRowRegisterOrder(SELECT_ROW_COUNT);
    private static final int[] PLAY_SLOT_ROWS = buildRowRegisterOrder(PLAY_ROW_COUNT);

    private static int[] buildHandSlotSegments() {
        int[] order = new int[HAND_SEGMENT_COUNT];
        int index = 0;
        for (int row = DoudizhuValues.HAND_ROWS - 1; row >= 0; row--) {
            for (int column = 0; column < DoudizhuValues.HAND_SEGMENTS_PER_ROW; column++) {
                order[index++] = row * DoudizhuValues.HAND_SEGMENTS_PER_ROW + column;
            }
        }
        return order;
    }

    /** 手牌段 segment 对应的卡槽序号。 */
    public static int handSlotOfSegment(int segment) {
        for (int index = 0; index < HAND_SLOT_SEGMENTS.length; index++) {
            if (HAND_SLOT_SEGMENTS[index] == segment) {
                return SLOT_HAND_START + index;
            }
        }
        return SLOT_HAND_START;
    }

    /** 卡槽序号对应的手牌段（参数必须是手牌槽，见 {@link #SLOT_HAND_START}）。 */
    public static int handSegmentOfSlot(int slotId) {
        int index = Math.max(0, Math.min(HAND_SLOT_SEGMENTS.length - 1, slotId - SLOT_HAND_START));
        return HAND_SLOT_SEGMENTS[index];
    }

    /** 卡槽序号对应的出牌区行（参数必须是出牌区槽）。 */
    public static int selectRowOfSlot(int slotId) {
        int index = Math.max(0, Math.min(SELECT_SLOT_ROWS.length - 1, slotId - SLOT_SELECT_START));
        return SELECT_SLOT_ROWS[index];
    }

    /** 卡槽序号对应的上一手行（参数必须是上一手槽）。 */
    public static int playRowOfSlot(int slotId) {
        int index = Math.max(0, Math.min(PLAY_SLOT_ROWS.length - 1, slotId - SLOT_PLAY_START));
        return PLAY_SLOT_ROWS[index];
    }

    public static boolean isHandSlot(int slotId) {
        return slotId >= SLOT_HAND_START && slotId < SLOT_HAND_START + HAND_SEGMENT_COUNT;
    }

    public static boolean isSelectSlot(int slotId) {
        return slotId >= SLOT_SELECT_START && slotId < SLOT_SELECT_START + SELECT_ROW_COUNT;
    }

    /** 可点选/可退回的槽位：手牌段与出牌区行（这两类走本模组自己的网络包）。 */
    public static boolean isSelectableSlot(int slotId) {
        return isHandSlot(slotId) || isSelectSlot(slotId);
    }

    // ------------------------------------------------------------------ 命中判定（与绘制共用同一套几何）

    /**
     * 卡槽在屏幕上的矩形：{@code [x, y, width, height]}。
     *
     * <p>三种摆放方式必须各按各的算，因为 Charta 的 {@code GameScreen} 就是这么画的：
     * HORIZONTAL 行按屏幕底部锚定、PREVIEW <b>用屏幕绝对 y</b>、其余按面板左上角。
     * 这里如果算错，命中的槽位就和画出来的不是一个，点下去会吃到别的地方的点击。</p>
     */
    public static float[] slotBounds(CardSlot<?, ?> slot, int screenHeight, int panelLeft, int panelTop) {
        float x = panelLeft + slot.x;
        float y = switch (slot.getType()) {
            case HORIZONTAL -> slotScreenY(slot.y, screenHeight);
            case PREVIEW -> slot.y;
            default -> panelTop + slot.y;
        };
        return new float[]{x, y, CardSlot.getWidth(slot), CardSlot.getHeight(slot)};
    }

    /** 鼠标是否落在矩形内。 */
    public static boolean within(float[] bounds, double mouseX, double mouseY) {
        return mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
    }

    /**
     * 命中判定：返回鼠标下最上层「非空」卡槽的序号（没有则返回 -1）。
     *
     * <p>相邻两行的纵向间距远小于牌的高度，空槽在几何上依然盖住下一行；而「点到第几张牌」只来自
     * 实际绘制出来的牌。若让空槽参与判定，两者会指向不同行、导致点牌无效，因此这里跳过空槽，
     * 只取最上层（序号最大 = 画得最晚）的非空槽，保证「槽位」与「牌序号」属于同一行。</p>
     *
     * @param screenHeight 当前界面高度（HORIZONTAL 行按屏幕底部锚定）
     * @param leftPos      界面左边界屏幕坐标
     * @param topPos       界面上边界屏幕坐标
     */
    public static int resolveTopSlotIndex(List<? extends CardSlot<?, ?>> slots, int screenHeight, int leftPos,
                                          int topPos, double mouseX, double mouseY) {
        int resolved = -1;
        for (int slotId = 0; slotId < slots.size(); slotId++) {
            CardSlot<?, ?> slot = slots.get(slotId);
            if (slot.getSlot().isEmpty()) {
                continue;
            }
            if (within(slotBounds(slot, screenHeight, leftPos, topPos), mouseX, mouseY)) {
                resolved = slotId;
            }
        }
        return resolved;
    }

    /**
     * 横向扇形的排布参数：{@code [每张之间的横向间距, 整行居中时的左缩进]}。
     *
     * <p>与 Charta 的 {@code CardSlotWidget} 完全一致：优先按行宽均分，但每张最多露出
     * {@code 牌宽 × 1.1}，牌少时整行居中。<b>命中判定与拖放指示器共用这一支</b>，
     * 这样「算出来的第几张」和「画出来的那条插入线」永远是同一个位置。</p>
     */
    private static float[] fanLayout(float width, int size) {
        float childWidth = CardSlot.getWidth(CardSlot.Type.DEFAULT);
        float maxOffset = childWidth + childWidth / 10f;
        float offset = childWidth + Math.max(0f, width - childWidth);
        float left = 0f;
        float totalWidth = childWidth + offset * (size - 1f);
        if (totalWidth > width && size > 1) {
            offset -= (totalWidth - width) / (size - 1f);
        }
        totalWidth = childWidth + maxOffset * (size - 1f);
        if (offset > maxOffset) {
            left = Math.max(offset - maxOffset, width - totalWidth);
            offset = maxOffset;
        }
        return new float[]{offset, left};
    }

    /**
     * 某一行里第 {@code index} 张牌在屏幕上的左边 x（拖放时画插入指示线用）。
     *
     * <p>行里没有牌时返回行的左边界——「拖到空段」也有确定的位置可指。</p>
     */
    public static float cardLeftX(CardSlot<?, ?> slot, int screenHeight, int leftPos, int topPos, int index) {
        float[] bounds = slotBounds(slot, screenHeight, leftPos, topPos);
        int size = slot.getSlot().size();
        if (size <= 0) {
            return bounds[0];
        }
        int clamped = Math.max(0, Math.min(size - 1, index));
        float[] layout = fanLayout(bounds[2], size);
        return bounds[0] + layout[1] / 2f + clamped * layout[0];
    }

    /**
     * 鼠标落在该行的第几张牌上（-1 = 没点到牌）。
     *
     * <p>横向扇形与 Charta 的 {@code CardSlotWidget} 完全一致：优先按行宽均分，但每张最多露出
     * {@code 牌宽 × 1.1}，牌少时整行居中。</p>
     */
    /**
     * 鼠标落在该行的第几张牌上。
     *
     * <p>横向扇形与 Charta 的 {@code CardSlotWidget} 完全一致：优先按行宽均分，但每张最多露出
     * {@code 牌宽 × 1.1}，牌少时整行居中。</p>
     *
     * <p><b>只要鼠标落在这一行的矩形里，就一定返回一个合法下标</b>（必要时取最近的那张）。
     * 这一条很关键：以前扇形算术在边缘（最左边一张牌的左侧、或者某两张牌之间的缝里）会返回 -1，
     * 调用方只好退回去用「框架记录的悬停牌号」——而那个牌号可能来自<b>另一行</b>，
     * 于是就成了「点第二行的牌，结果第一行的牌进了出牌区」。宁可吸附到最近的一张，
     * 也不要跨行取一个不属于这一行的下标。</p>
     */
    public static int resolveCardIndex(CardSlot<?, ?> slot, int screenHeight, int leftPos, int topPos,
                                       double mouseX, double mouseY) {
        GameSlot cards = slot.getSlot();
        int size = cards.size();
        if (size <= 0) {
            return -1;
        }
        float[] bounds = slotBounds(slot, screenHeight, leftPos, topPos);
        if (!within(bounds, mouseX, mouseY)) {
            return -1;
        }
        if (slot.getType() != CardSlot.Type.HORIZONTAL) {
            return size - 1;
        }
        float childWidth = CardSlot.getWidth(CardSlot.Type.DEFAULT);
        float[] layout = fanLayout(bounds[2], size);
        float offset = layout[0];
        float left = layout[1];
        float local = (float) mouseX - bounds[0] - left / 2f;
        int nearest = 0;
        float nearestDistance = Float.MAX_VALUE;
        for (int index = 0; index < size; index++) {
            float cardWidth = index == size - 1 ? childWidth : offset;
            float cardLeft = index * offset;
            if (local >= cardLeft && local < cardLeft + cardWidth) {
                return index;
            }
            // 没打中任何一张时退化成「最近的一张」，而不是 -1
            float center = cardLeft + cardWidth / 2f;
            float distance = Math.abs(local - center);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = index;
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------ 界面同步数据

    public static final int DATA_PHASE = 0;
    public static final int DATA_CURRENT_SEAT = 1;
    public static final int DATA_HIGHEST_BID = 2;
    public static final int DATA_MY_SEAT = 3;
    public static final int DATA_CAN_PASS = 4;
    public static final int DATA_CAN_PLAY = 5;
    public static final int DATA_LAST_SEAT = 6;
    public static final int DATA_LAST_TYPE = 7;
    public static final int DATA_LAST_KEY = 8;
    public static final int DATA_LAST_SIZE = 9;
    public static final int DATA_LANDLORD_SEAT = 10;
    public static final int DATA_BOTTOM_REVEALED = 11;
    /** 出牌区当前选牌的牌型序号（-1 = 空选或牌型不合法）。 */
    public static final int DATA_SELECTION_TYPE = 12;
    /** 出牌区当前选牌能否压过上一手（1/0；首出恒为 1）。 */
    public static final int DATA_SELECTION_BEATS = 13;
    /** 各座位剩余手牌数的起始下标。 */
    public static final int DATA_SEAT_COUNT_START = 14;
    /** 记牌器：每个点数「还没露面」的张数，下标 {@code + value - RuleEngine.MIN_VALUE}。 */
    public static final int DATA_UNSEEN_START = DATA_SEAT_COUNT_START + DoudizhuValues.MAX_PLAYERS;
    /** 记牌器覆盖的点数个数（3..大王）。 */
    public static final int DATA_UNSEEN_COUNT = RuleEngine.MAX_VALUE - RuleEngine.MIN_VALUE + 1;
    /** 各座位累计分数的起始下标（关闭计分时恒为 0）。 */
    public static final int DATA_SEAT_SCORE_START = DATA_UNSEEN_START + DATA_UNSEEN_COUNT;
    /** 出牌序号：每次有人成功出牌 +1，界面据此给最新一手加高亮。 */
    public static final int DATA_PLAY_REVISION = DATA_SEAT_SCORE_START + DoudizhuValues.MAX_PLAYERS;
    /** 本局已打出的炸弹 + 王炸数（界面显示倍数）。 */
    public static final int DATA_NUKE_COUNT = DATA_PLAY_REVISION + 1;
    /** 「提示」轮换进度：当前第几个 / 一共几个（0 表示没在轮换）。 */
    public static final int DATA_HINT_INDEX = DATA_NUKE_COUNT + 1;
    public static final int DATA_HINT_TOTAL = DATA_HINT_INDEX + 1;
    /** 是否抢地主模式（1/0）：界面据此把「叫分 1/2/3」换成「抢地主」。 */
    public static final int DATA_GRAB_MODE = DATA_HINT_TOTAL + 1;
    /** AI 难度档位（0 = 保守 / 1 = 均衡 / 2 = 激进）。 */
    public static final int DATA_AI_DIFFICULTY = DATA_GRAB_MODE + 1;
    /** 本局已经发生的抢次数。 */
    public static final int DATA_GRAB_COUNT = DATA_AI_DIFFICULTY + 1;
    /** 局倍数（炸弹 + 抢地主；不含个人倍数与春天）——界面的「这一局值多少」。 */
    public static final int DATA_BASE_MULTIPLIER = DATA_GRAB_COUNT + 1;
    /** 明牌 / 加倍阶段的剩余 tick（0 = 不在这个阶段或已经结束）。 */
    public static final int DATA_DECLARE_TICKS = DATA_BASE_MULTIPLIER + 1;
    /** 各座位的明牌 / 加倍 / 抢 / 表态标记位，起始下标（见 {@code DoudizhuGame.seatMarks}）。 */
    public static final int DATA_SEAT_MARKS_START = DATA_DECLARE_TICKS + 1;
    /** 自己座位的标记位（界面按钮回显用）。 */
    public static final int DATA_MY_MARKS = DATA_SEAT_MARKS_START + DoudizhuValues.MAX_PLAYERS;
    /** 各座位在<b>叫分</b>模式下叫了几分（-1 = 还没表态，0 = 不叫，1..3）。 */
    public static final int DATA_SEAT_BID_START = DATA_MY_MARKS + 1;
    /** 当前「谁最可能当地主」（叫分 / 抢地主还没结束时用它，-1 = 还没人表态）。 */
    public static final int DATA_LEADING_SEAT = DATA_SEAT_BID_START + DoudizhuValues.MAX_PLAYERS;

    /**
     * AI 难度档位对应的语言键，下标就是选项取值。
     *
     * <p>放在这里（而不是在客户端界面里写死）是为了让「选项值 → 显示名」只有一份定义：
     * 界面状态行与选项界面读的是同一张表，序号漂移会立刻编译不过。</p>
     */
    public static final String[] AI_DIFFICULTY_LABELS = {
            "message.chartalandlords.ai_level.conservative",
            "message.chartalandlords.ai_level.balanced",
            "message.chartalandlords.ai_level.aggressive"
    };

    private int phaseSync = 0;
    private int currentSeatSync = 0;
    private int highestBidSync = 0;
    private int mySeatSync = 0;
    private int canPassSync = 0;
    private int canPlaySync = 0;
    private int lastSeatSync = -1;
    private int lastTypeSync = -1;
    private int lastKeySync = -1;
    private int lastSizeSync = 0;
    private int landlordSync = -1;
    private int bottomRevealedSync = 0;
    private int selectionTypeSync = -1;
    private int selectionBeatsSync = 0;
    private final int[] seatCountSync = new int[DoudizhuValues.MAX_PLAYERS];
    private final int[] unseenSync = new int[DATA_UNSEEN_COUNT];
    private final int[] seatScoreSync = new int[DoudizhuValues.MAX_PLAYERS];
    private int playRevisionSync = 0;
    private int nukeCountSync = 0;
    private int hintIndexSync = 0;
    private int hintTotalSync = 0;
    private int grabModeSync = 0;
    private int aiDifficultySync = 1;
    private int grabCountSync = 0;
    private int baseMultiplierSync = 1;
    private int declareTicksSync = 0;
    private final int[] seatMarksSync = new int[DoudizhuValues.MAX_PLAYERS];
    private int myMarksSync = 0;
    private final int[] seatBidSync = new int[DoudizhuValues.MAX_PLAYERS];
    private int leadingSeatSync = -1;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            boolean client = player.level().isClientSide();
            if (index >= DATA_SEAT_COUNT_START && index < DATA_SEAT_COUNT_START + DoudizhuValues.MAX_PLAYERS) {
                int seat = index - DATA_SEAT_COUNT_START;
                return client ? seatCountSync[seat] : game.handSizeAt(seat);
            }
            if (index >= DATA_UNSEEN_START && index < DATA_UNSEEN_START + DATA_UNSEEN_COUNT) {
                return client ? unseenSync[index - DATA_UNSEEN_START] : serverUnseen(index - DATA_UNSEEN_START);
            }
            if (index >= DATA_SEAT_SCORE_START
                    && index < DATA_SEAT_SCORE_START + DoudizhuValues.MAX_PLAYERS) {
                int seat = index - DATA_SEAT_SCORE_START;
                return client ? seatScoreSync[seat] : game.totalScore(seat);
            }
            if (index >= DATA_SEAT_MARKS_START
                    && index < DATA_SEAT_MARKS_START + DoudizhuValues.MAX_PLAYERS) {
                int seat = index - DATA_SEAT_MARKS_START;
                return client ? seatMarksSync[seat] : game.seatMarks(seat);
            }
            if (index >= DATA_SEAT_BID_START && index < DATA_SEAT_BID_START + DoudizhuValues.MAX_PLAYERS) {
                int seat = index - DATA_SEAT_BID_START;
                return client ? seatBidSync[seat] : game.bidValue(seat);
            }
            return switch (index) {
                case DATA_PHASE -> client ? phaseSync : game.phaseOrdinal();
                case DATA_CURRENT_SEAT -> client ? currentSeatSync : game.currentSeat();
                case DATA_HIGHEST_BID -> client ? highestBidSync : game.highestBid();
                case DATA_MY_SEAT -> client ? mySeatSync : game.seatOf(cardPlayer);
                case DATA_CAN_PASS -> client ? canPassSync : (game.canPass(cardPlayer) ? 1 : 0);
                case DATA_CAN_PLAY -> client ? canPlaySync : (game.canPlaySelection(cardPlayer) ? 1 : 0);
                case DATA_LAST_SEAT -> client ? lastSeatSync : game.lastPlaySeat();
                case DATA_LAST_TYPE -> client ? lastTypeSync : game.lastComboTypeOrdinal();
                case DATA_LAST_KEY -> client ? lastKeySync : game.lastComboKey();
                case DATA_LAST_SIZE -> client ? lastSizeSync : game.lastPlaySize();
                case DATA_LANDLORD_SEAT -> client ? landlordSync : game.landlordSeat();
                case DATA_BOTTOM_REVEALED -> client ? bottomRevealedSync : (game.isBottomRevealed() ? 1 : 0);
                case DATA_SELECTION_TYPE -> client ? selectionTypeSync : game.selectionComboTypeOrdinal(cardPlayer);
                case DATA_SELECTION_BEATS -> client ? selectionBeatsSync : (game.selectionBeatsLast(cardPlayer) ? 1 : 0);
                case DATA_PLAY_REVISION -> client ? playRevisionSync : game.playRevision();
                case DATA_NUKE_COUNT -> client ? nukeCountSync : game.nukeCount();
                case DATA_HINT_INDEX -> client ? hintIndexSync : game.hintIndex(cardPlayer);
                case DATA_HINT_TOTAL -> client ? hintTotalSync : game.hintTotal(cardPlayer);
                case DATA_GRAB_MODE -> client ? grabModeSync : (game.isGrabMode() ? 1 : 0);
                case DATA_AI_DIFFICULTY -> client ? aiDifficultySync : game.aiProfile().ordinal();
                case DATA_GRAB_COUNT -> client ? grabCountSync : game.grabCount();
                case DATA_BASE_MULTIPLIER -> client ? baseMultiplierSync : game.baseMultiplier();
                case DATA_DECLARE_TICKS -> client ? declareTicksSync : game.declareTicksLeft();
                case DATA_MY_MARKS -> client ? myMarksSync : game.seatMarks(game.seatOf(cardPlayer));
                case DATA_LEADING_SEAT -> client ? leadingSeatSync : game.leadingSeat();
                default -> 0;
            };
        }

        /**
         * 记牌器的服务端一侧：整副牌的点数分布减去「自己看得见的部分」。
         *
         * <p>一次 {@code broadcastChanges} 会把这 15 个下标各读一遍，所以这里缓存一份。
         * 失效条件必须覆盖所有会改变「已见牌」的事件：出牌（{@code playRevision}）与底牌翻开。
         * 选牌不影响结果（选牌区算已见），所以不需要为它失效。</p>
         */
        private int serverUnseen(int offset) {
            int seat = cardPlayer == null ? 0 : game.seatOf(cardPlayer);
            int revision = game.playRevision() * 2 + (game.isBottomRevealed() ? 1 : 0);
            if (unseenCache == null || unseenCacheSeat != seat || unseenCacheRevision != revision) {
                unseenCache = game.unseenCounts(seat);
                unseenCacheSeat = seat;
                unseenCacheRevision = revision;
            }
            int value = RuleEngine.MIN_VALUE + offset;
            return value >= 0 && value < unseenCache.length ? unseenCache[value] : 0;
        }

        @Override
        public void set(int index, int value) {
            if (index >= DATA_SEAT_COUNT_START && index < DATA_SEAT_COUNT_START + DoudizhuValues.MAX_PLAYERS) {
                seatCountSync[index - DATA_SEAT_COUNT_START] = value;
                return;
            }
            if (index >= DATA_UNSEEN_START && index < DATA_UNSEEN_START + DATA_UNSEEN_COUNT) {
                unseenSync[index - DATA_UNSEEN_START] = value;
                return;
            }
            if (index >= DATA_SEAT_SCORE_START
                    && index < DATA_SEAT_SCORE_START + DoudizhuValues.MAX_PLAYERS) {
                seatScoreSync[index - DATA_SEAT_SCORE_START] = value;
                return;
            }
            if (index >= DATA_SEAT_MARKS_START
                    && index < DATA_SEAT_MARKS_START + DoudizhuValues.MAX_PLAYERS) {
                seatMarksSync[index - DATA_SEAT_MARKS_START] = value;
                return;
            }
            if (index >= DATA_SEAT_BID_START && index < DATA_SEAT_BID_START + DoudizhuValues.MAX_PLAYERS) {
                seatBidSync[index - DATA_SEAT_BID_START] = value;
                return;
            }
            switch (index) {
                case DATA_PHASE -> phaseSync = value;
                case DATA_CURRENT_SEAT -> currentSeatSync = value;
                case DATA_HIGHEST_BID -> highestBidSync = value;
                case DATA_MY_SEAT -> mySeatSync = value;
                case DATA_CAN_PASS -> canPassSync = value;
                case DATA_CAN_PLAY -> canPlaySync = value;
                case DATA_LAST_SEAT -> lastSeatSync = value;
                case DATA_LAST_TYPE -> lastTypeSync = value;
                case DATA_LAST_KEY -> lastKeySync = value;
                case DATA_LAST_SIZE -> lastSizeSync = value;
                case DATA_LANDLORD_SEAT -> landlordSync = value;
                case DATA_BOTTOM_REVEALED -> bottomRevealedSync = value;
                case DATA_SELECTION_TYPE -> selectionTypeSync = value;
                case DATA_SELECTION_BEATS -> selectionBeatsSync = value;
                case DATA_PLAY_REVISION -> playRevisionSync = value;
                case DATA_NUKE_COUNT -> nukeCountSync = value;
                case DATA_HINT_INDEX -> hintIndexSync = value;
                case DATA_HINT_TOTAL -> hintTotalSync = value;
                case DATA_GRAB_MODE -> grabModeSync = value;
                case DATA_AI_DIFFICULTY -> aiDifficultySync = value;
                case DATA_GRAB_COUNT -> grabCountSync = value;
                case DATA_BASE_MULTIPLIER -> baseMultiplierSync = value;
                case DATA_DECLARE_TICKS -> declareTicksSync = value;
                case DATA_MY_MARKS -> myMarksSync = value;
                case DATA_LEADING_SEAT -> leadingSeatSync = value;
                default -> {}
            }
        }

        @Override
        public int getCount() {
            return DATA_LEADING_SEAT + 1;
        }
    };

    /** 记牌器的服务端缓存（见 {@code serverUnseen}）。 */
    private int[] unseenCache;
    private int unseenCacheSeat = Integer.MIN_VALUE;
    private int unseenCacheRevision = Integer.MIN_VALUE;

    // ------------------------------------------------------------------ 上一手牌的界面切片

    /**
     * 上一手牌的界面展示行：按 {@link DoudizhuValues#ROW_CAPACITY} 直接切分真实 playArea，
     * 因此 24 张的「连对十二连」也能完整显示（服务端刷新、由框架同步给客户端）。
     */
    private final List<GameSlot> playViewRows = new ArrayList<>();
    private int playViewSize = -1;
    private Card playViewLast;

    // ------------------------------------------------------------------ 构造

    public DoudizhuMenu(int containerId, Inventory inventory, Definition definition) {
        super(Menus.DOUDIZHU.get(), containerId, inventory, definition);

        // 注册顺序 = 绘制顺序 = 槽位序号，一律「屏幕位置越靠下越晚画」（见 SLOT_PLAY_START 的说明）。
        // 上一手：带内自上而下注册（第 3 → 第 1 行），最低行最后画、完全可见。
        for (int index = 0; index < PLAY_ROW_COUNT; index++) {
            int playRow = PLAY_SLOT_ROWS[index];
            addCardSlot(new CardSlot<>(this.game, g -> playViewRow(playRow), ROW_X,
                    visualRowY(playVisualRow(playRow)), CardSlot.Type.HORIZONTAL));
        }
        // 出牌区：同样带内自上而下注册。
        for (int index = 0; index < SELECT_ROW_COUNT; index++) {
            int selectRow = SELECT_SLOT_ROWS[index];
            addCardSlot(new CardSlot<>(this.game, g -> g.getSelectionRow(cardPlayer, selectRow), ROW_X,
                    visualRowY(selectVisualRow(selectRow)), CardSlot.Type.HORIZONTAL));
        }
        // 手牌：先后排（段 2、3）再前沿行（段 0、1），前沿行最后画、完全可见。
        for (int index = 0; index < HAND_SEGMENT_COUNT; index++) {
            int segment = HAND_SLOT_SEGMENTS[index];
            addCardSlot(new CardSlot<>(this.game, g -> g.getHandSegment(cardPlayer, segment),
                    handSegmentX(segment), visualRowY(handSegmentRow(segment)), CardSlot.Type.HORIZONTAL));
        }
        // 底牌：不占整行，改成面板顶部的小牌预览（和记牌器同一块区域），把整行高度还给牌桌
        addCardSlot(new CardSlot<>(this.game, DoudizhuGame::getBottomViewSlot,
                BOTTOM_PREVIEW_X, BOTTOM_PREVIEW_Y, CardSlot.Type.PREVIEW));
        // 明牌展示槽：只当<b>数据通道</b>用（给「看牌」界面读别人的手牌），
        // 故意摆在屏幕下方很远的地方，所以界面上根本看不到、也点不到。
        // 以前它是一条真的牌带，但 20+ 张牌挤在 150px 里就是一片白色糊条，比不看还难读。
        for (int seat = 0; seat < REVEAL_ROW_COUNT; seat++) {
            int viewSeat = seat;
            addCardSlot(new CardSlot<>(this.game, g -> g.getRevealView(viewSeat), ROW_X,
                    OFF_SCREEN_Y, CardSlot.Type.HORIZONTAL));
        }

        for (int row = 0; row < PLAY_ROW_COUNT; row++) {
            playViewRows.add(displayRow());
        }
        addDataSlots(this.data);
    }

    /** 只读展示槽：框架的「拿起牌」流程不能动它。 */
    private static GameSlot displayRow() {
        return readOnlySlot();
    }

    /**
     * 只读展示槽（手牌视图片段、出牌区行、上一手行、底牌、明牌展示行都用它）。
     *
     * <p>务必用它，不要图省事直接 {@code new GameSlot(...)}：默认的
     * {@link GameSlot#canRemoveCard} 是 {@code !isEmpty()}，也就是<b>允许</b>被拿走。
     * 而框架自带一条「点空槽/展示槽就把牌拿在鼠标上」的流程
     * （{@code GameScreen.mouseClicked} 命中任意卡槽就发 {@code CardContainerSlotClickPayload}），
     * 于是一张上一手牌会被「拿起来」跟着鼠标跑——玩家反馈的「点上一手和底牌重叠的部分会拿起上一手的牌」
     * 就是这么来的。</p>
     */
    private static GameSlot readOnlySlot() {
        return new GameSlot(new LinkedList<>()) {
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

    /**
     * 上一手第 row 行的展示槽。
     *
     * <p>服务端每次 {@code broadcastChanges} 读槽位前重算一次（只在内容变化时重建，避免每帧分配），
     * 客户端只读框架同步下来的内容，绝不用客户端的 playArea 反推。</p>
     */
    private GameSlot playViewRow(int row) {
        if (!player.level().isClientSide()) {
            syncPlayView();
        }
        return row >= 0 && row < playViewRows.size() ? playViewRows.get(row) : playViewRows.get(0);
    }

    private void syncPlayView() {
        GameSlot source = this.game.getPlayArea();
        int size = source.size();
        Card last = size == 0 ? null : source.getLast();
        if (size == playViewSize && last == playViewLast) {
            return;
        }
        playViewSize = size;
        playViewLast = last;
        List<Card> cards = new ArrayList<>();
        source.forEach(cards::add);
        for (int row = 0; row < playViewRows.size(); row++) {
            int from = row * DoudizhuValues.ROW_CAPACITY;
            int to = Math.min(cards.size(), from + DoudizhuValues.ROW_CAPACITY);
            playViewRows.get(row).setCards(to > from ? new ArrayList<>(cards.subList(from, to)) : new ArrayList<>());
        }
    }

    @Override
    public GameType<DoudizhuGame, DoudizhuMenu> getGameType() {
        return chartalandlords.doudizhu.registry.GameTypes.DOUDIZHU.get();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.game != null && this.cardPlayer != null && !this.game.isGameOver();
    }

    // ------------------------------------------------------------------ 界面查询

    public DoudizhuGame.Phase phase() {
        int ordinal = data.get(DATA_PHASE);
        DoudizhuGame.Phase[] values = DoudizhuGame.Phase.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : DoudizhuGame.Phase.OVER;
    }

    public int mySeat() {
        return data.get(DATA_MY_SEAT);
    }

    public int currentSeat() {
        return data.get(DATA_CURRENT_SEAT);
    }

    public int highestBid() {
        return data.get(DATA_HIGHEST_BID);
    }

    public int landlordSeat() {
        return data.get(DATA_LANDLORD_SEAT);
    }

    public int lastPlaySeat() {
        return data.get(DATA_LAST_SEAT);
    }

    public int lastComboType() {
        return data.get(DATA_LAST_TYPE);
    }

    public int lastComboKey() {
        return data.get(DATA_LAST_KEY);
    }

    public int lastPlaySize() {
        return data.get(DATA_LAST_SIZE);
    }

    /** 出牌区当前选牌的牌型序号（-1 = 空选或牌型不合法）。 */
    public int selectionComboType() {
        return data.get(DATA_SELECTION_TYPE);
    }

    /** 出牌区当前选牌能否压过上一手。 */
    public boolean selectionBeatsLast() {
        return data.get(DATA_SELECTION_BEATS) > 0;
    }

    public boolean isBottomRevealed() {
        return data.get(DATA_BOTTOM_REVEALED) > 0;
    }

    /** 记牌器：点数 {@code value}（{@link RuleEngine#MIN_VALUE}..{@link RuleEngine#MAX_VALUE}）还有几张没露面。 */
    public int unseenCount(int value) {
        int offset = value - RuleEngine.MIN_VALUE;
        return offset >= 0 && offset < DATA_UNSEEN_COUNT ? Math.max(0, data.get(DATA_UNSEEN_START + offset)) : 0;
    }

    /** 某个座位累计的分数（关闭计分时恒为 0）。 */
    public int seatScore(int seat) {
        return seat >= 0 && seat < DoudizhuValues.MAX_PLAYERS ? data.get(DATA_SEAT_SCORE_START + seat) : 0;
    }

    /** 客户端上一次同步到的出牌序号；变化说明桌上换牌了。 */
    public int playRevision() {
        return data.get(DATA_PLAY_REVISION);
    }

    /** 本局已打出的炸弹 + 王炸数。 */
    public int nukeCount() {
        return data.get(DATA_NUKE_COUNT);
    }

    /** 「提示」轮换进度：当前第几个候选（1 起；0 表示没在轮换）。 */
    public int hintIndex() {
        return data.get(DATA_HINT_INDEX);
    }

    /** 「提示」轮换：候选总数（0 表示没在轮换）。 */
    public int hintTotal() {
        return data.get(DATA_HINT_TOTAL);
    }

    /** 是否抢地主模式（界面据此把「叫分」换成「抢地主」）。 */
    public boolean isGrabMode() {
        return data.get(DATA_GRAB_MODE) > 0;
    }

    /** 本局已经发生的抢次数。 */
    public int grabCount() {
        return data.get(DATA_GRAB_COUNT);
    }

    /**
     * AI 难度档位序号（0 = 保守 / 1 = 均衡 / 2 = 激进）。
     *
     * <p>取的是 {@code AiProfile.ordinal()}，但界面只把它当序号用，不依赖具体档位实现。</p>
     */
    public int aiDifficulty() {
        return data.get(DATA_AI_DIFFICULTY);
    }

    /** 当前局倍数（炸弹 × 抢地主；不含个人倍数与春天）。 */
    public int baseMultiplier() {
        return Math.max(1, data.get(DATA_BASE_MULTIPLIER));
    }

    /** 明牌 / 加倍阶段剩余 tick（0 = 不在这个阶段）。 */
    public int declareTicksLeft() {
        return Math.max(0, data.get(DATA_DECLARE_TICKS));
    }

    /** 某个座位的明牌 / 加倍标记位（bit0 = 明牌、bit1 = 加倍、bit2 = 已决定）。 */
    public int seatMarks(int seat) {
        return seat >= 0 && seat < DoudizhuValues.MAX_PLAYERS ? data.get(DATA_SEAT_MARKS_START + seat) : 0;
    }

    /** 我自己的标记位。 */
    public int myMarks() {
        return data.get(DATA_MY_MARKS);
    }

    /** 明牌 / 加倍阶段：我还没确认（确认过就只能看别人的了）。 */
    public boolean canDeclareNow() {
        return phase() == DoudizhuGame.Phase.DECLARING && (myMarks() & 4) == 0;
    }

    /** 明牌 / 加倍阶段：我现在还能按「明牌」（已经明牌过的座位不能撤回）。 */
    public boolean canRevealNow() {
        return canDeclareNow() && (myMarks() & 1) == 0;
    }

    /** 明牌 / 加倍阶段：我现在还能按「加倍」。 */
    public boolean canDoubleNow() {
        return canDeclareNow();
    }

    /** 我的个人倍数：明牌与加倍各翻一倍，所以是 1 / 2 / 4。 */
    public int myPersonalFactor() {
        return 1 << (myMarks() & 3);
    }

    /** 某个座位的个人倍数：1 / 2 / 4。 */
    public int seatPersonalFactor(int seat) {
        return 1 << (seatMarks(seat) & 3);
    }

    /** 某个座位是否明牌。 */
    public boolean isSeatRevealed(int seat) {
        return (seatMarks(seat) & 1) != 0;
    }

    /**
     * 明牌展示行里到底有没有牌。
     *
     * <p>服务端只给明牌过的座位填牌，客户端拿到的是框架同步下来的内容，所以这一问在两边都成立；
     * 界面据此决定要不要画这一行的底衬与标签、以及要把记牌器往下挤多少。</p>
     */
    public boolean revealRowHasCards(int seat) {
        int slotId = revealSlotOfSeat(seat);
        return isRevealSlot(slotId) && slotHasCards(slotId);
    }

    /** 明牌展示行里那一手牌（客户端拿到的同步副本；服务端拿到的是真身，只读）。 */
    public List<Card> revealRowCards(int seat) {
        List<Card> cards = new ArrayList<>();
        cardSlots.get(revealSlotOfSeat(seat)).getSlot().forEach(cards::add);
        return cards;
    }

    /** 某个座位是否加倍。 */
    public boolean isSeatDoubled(int seat) {
        return (seatMarks(seat) & 2) != 0;
    }

    // ------------------------------------------------------------------ 「看牌」：每家的手牌现在能看到什么

    /**
     * 座位 {@code seat} 的手牌现在<b>能公开看到什么</b>（「看牌」界面用）。
     *
     * <p>三种来源，一条规则：</p>
     * <ol>
     *   <li><b>自己</b>：真手牌（按段拼回整手，段内顺序就是手牌顺序）；</li>
     *   <li><b>明牌过的座位</b>：{@link #revealRowCards(int)} 里的正面副本——服务端在
     *       {@code syncCensoredHand} 里会跟着真手牌一起更新，所以打出去的牌会同步消失；</li>
     *   <li><b>其余座位</b>：对应的张数张<b>牌背</b>（{@code flipped()} 为真的空牌）——
     *       张数是公开信息，牌面不是。</li>
     * </ol>
     *
     * <p>返回的永远是「一手完整的牌」，长度等于 {@link #seatHandSize(int)}，
     * 所以界面拿到就能直接画，不必再判断该画几张。</p>
     */
    public List<Card> viewerHandCards(int seat) {
        if (seat < 0 || seat >= DoudizhuValues.MAX_PLAYERS) {
            return List.of();
        }
        int size = seatHandSize(seat);
        if (size <= 0) {
            return List.of();
        }
        if (seat == mySeat()) {
            List<Card> mine = new ArrayList<>();
            for (int segment = 0; segment < HAND_SEGMENT_COUNT; segment++) {
                game.getHandSegment(cardPlayer, segment).forEach(mine::add);
            }
            return mine;
        }
        List<Card> shown = new ArrayList<>();
        if (isSeatRevealed(seat)) {
            shown.addAll(revealRowCards(seat));
        }
        // 明牌展示槽可能还没同步到（它比自己手牌的明牌标记晚到一帧）：缺的补牌背，
        // 绝不画成「这个人只剩几张」——张数比牌面更重要。
        while (shown.size() < size) {
            shown.add(new Card());
        }
        if (shown.size() > size) {
            shown = new ArrayList<>(shown.subList(0, size));
        }
        return shown;
    }

    /**
     * 「看牌」界面用的底牌。
     *
     * <p>取的是<b>界面专用</b>底牌槽（{@code getBottomViewSlot}）：牌桌上那一份在第一轮之后
     * 仍会更新，而这一份永远保留着开局翻出来的三张，所以整局随时可查。</p>
     */
    public List<Card> viewerBottomCards() {
        List<Card> cards = new ArrayList<>();
        game.getBottomViewSlot().forEach(cards::add);
        return cards;
    }

    /** 整副手牌里第 {@code handIndex} 张牌（拖拽时「跟手」的那张）；越界返回 null。 */
    public Card handCard(int handIndex) {
        if (handIndex < 0 || handIndex >= seatHandSize(mySeat())) {
            return null;
        }
        int segment = handIndex / DoudizhuValues.SEGMENT_CAPACITY;
        int indexInSegment = handIndex % DoudizhuValues.SEGMENT_CAPACITY;
        if (segment < 0 || segment >= HAND_SEGMENT_COUNT) {
            return null;
        }
        GameSlot slot = game.getHandSegment(cardPlayer, segment);
        return indexInSegment < slot.size() ? slot.get(indexInSegment) : null;
    }

    /** 某个座位是否已经在叫分 / 抢地主阶段表过态。 */
    public boolean isSeatBidDecided(int seat) {
        return (seatMarks(seat) & 16) != 0;
    }

    /** 某个座位是否已经抢过地主（抢地主模式）。 */
    public boolean isSeatGrabbed(int seat) {
        return (seatMarks(seat) & 8) != 0;
    }

    /** 某个座位在叫分模式下叫了几分（-1 = 还没表态，0 = 不叫，1..3）。 */
    public int seatBid(int seat) {
        return seat >= 0 && seat < DoudizhuValues.MAX_PLAYERS ? data.get(DATA_SEAT_BID_START + seat) : -1;
    }

    /** 当前「谁最可能当地主」（叫分 / 抢地主还没结束时用它，-1 = 还没人表态）。 */
    public int leadingSeat() {
        return data.get(DATA_LEADING_SEAT);
    }

    /** 某个座位是否已经在明牌 / 加倍阶段做过决定。 */
    public boolean isSeatDeclared(int seat) {
        return (seatMarks(seat) & 4) != 0;
    }

    /** 上一手第 {@code row} 行对应的卡槽序号。 */
    public static int playSlotOfRow(int row) {
        for (int index = 0; index < PLAY_SLOT_ROWS.length; index++) {
            if (PLAY_SLOT_ROWS[index] == row) {
                return SLOT_PLAY_START + index;
            }
        }
        return SLOT_PLAY_START;
    }

    /**
     * 给上一手带里所有非空行加一次高亮闪烁（用 Charta 自带的 {@code GameSlot.highlightColor/Time}，
     * {@code CardSlotWidget} 会自己倒计时并画外框）。只应由客户端在出牌序号变化时调用。
     */
    public void highlightLastPlay() {
        for (int row = 0; row < PLAY_ROW_COUNT; row++) {
            GameSlot slot = getCardSlot(playSlotOfRow(row)).getSlot();
            if (!slot.isEmpty()) {
                slot.highlightColor = HIGHLIGHT_LAST_PLAY;
                slot.highlightTime = HIGHLIGHT_TICKS;
            }
        }
    }

    /** 新出牌高亮的颜色与持续时间（tick）。 */
    public static final int HIGHLIGHT_LAST_PLAY = 0xFFD54F;
    public static final int HIGHLIGHT_TICKS = 30;

    public boolean isMyTurn() {
        return phase() == DoudizhuGame.Phase.PLAYING && currentSeat() == mySeat();
    }

    public boolean isBidding() {
        return phase() == DoudizhuGame.Phase.BIDDING && currentSeat() == mySeat();
    }

    public boolean canPassNow() {
        return data.get(DATA_CAN_PASS) > 0;
    }

    public boolean canPlayNow() {
        return data.get(DATA_CAN_PLAY) > 0;
    }

    public int seatCount() {
        return game.seatCount();
    }

    public int seatHandSize(int seat) {
        return data.get(DATA_SEAT_COUNT_START + seat);
    }

    public CardPlayer seatPlayer(int seat) {
        return game.seatPlayer(seat);
    }

    /** 出牌区当前的选牌张数（界面用：直接数框架同步下来的展示行）。 */
    public int selectionCount() {
        int count = 0;
        for (int row = 0; row < SELECT_ROW_COUNT; row++) {
            count += game.getSelectionRow(cardPlayer, row).size();
        }
        return count;
    }

    /** 某个卡槽里是否已经有牌（界面用它决定底衬与标签的明暗）。 */
    public boolean slotHasCards(int slotId) {
        return slotId >= 0 && slotId < cardSlots.size() && !cardSlots.get(slotId).getSlot().isEmpty();
    }

    /** 手牌 / 出牌区 / 上一手 / 底牌 这几个带里是否已经有牌。 */
    public boolean handHasCards() {
        for (int slotId = SLOT_HAND_START; slotId < SLOT_HAND_START + HAND_SEGMENT_COUNT; slotId++) {
            if (slotHasCards(slotId)) {
                return true;
            }
        }
        return false;
    }

    public boolean selectionHasCards() {
        for (int slotId = SLOT_SELECT_START; slotId < SLOT_SELECT_START + SELECT_ROW_COUNT; slotId++) {
            if (slotHasCards(slotId)) {
                return true;
            }
        }
        return false;
    }

    public boolean playBandHasCards() {
        for (int slotId = SLOT_PLAY_START; slotId < SLOT_PLAY_START + PLAY_ROW_COUNT; slotId++) {
            if (slotHasCards(slotId)) {
                return true;
            }
        }
        return false;
    }

    /** 底牌预览里有没有牌（界面据此决定要不要画底衬与标签）。 */
    public boolean bottomBandHasCards() {
        return slotHasCards(SLOT_BOTTOM_PREVIEW);
    }

    // ------------------------------------------------------------------ 玩家输入

    /**
     * 手牌行 / 选择区行的点击。
     *
     * <p>槽位序号与「手牌段 / 出牌区行」都不再是顺序对应：手牌前排（段 0、1）为了画在最上层而注册在后，
     * 出牌区与上一手带内也是自上而下注册。映射统一由 {@link #handSegmentOfSlot} /
     * {@link #selectRowOfSlot} 给出。</p>
     */
    public void handleCardClick(int slotId, int cardId) {
        if (isHandSlot(slotId)) {
            game.selectFromHand(cardPlayer, handSegmentOfSlot(slotId), cardId);
        } else if (isSelectSlot(slotId)) {
            game.deselectToHand(cardPlayer, selectRowOfSlot(slotId), cardId);
        }
    }

    /**
     * 手牌内部的两点移动（拖拽排序）：把整副手牌里第 {@code from} 张插到第 {@code to} 个位置。
     *
     * <p>屏幕算出来的是「段 + 段内下标」，这里换算成整副手牌的下标，服务端只需要一个整数位置。</p>
     */
    public void handleCardMove(int from, int to) {
        game.moveHandCard(cardPlayer, from, to);
    }

    /** 某个手牌段里的第 {@code indexInSegment} 张在整副手牌里的下标。 */
    public static int handIndex(int segment, int indexInSegment) {
        return segment * DoudizhuValues.SEGMENT_CAPACITY + indexInSegment;
    }

    public void handleAction(DoudizhuAction action) {
        switch (action) {
            case PLAY -> game.playSelection(cardPlayer);
            case PASS -> game.passTurn(cardPlayer);
            case HINT -> game.hint(cardPlayer);
            case BID_1 -> game.bid(cardPlayer, 1);
            case BID_2 -> game.bid(cardPlayer, 2);
            case BID_3 -> game.bid(cardPlayer, 3);
            case BID_PASS -> game.bid(cardPlayer, 0);
            case RETRACT -> game.retractSelection(cardPlayer);
            case REVEAL_HAND, DOUBLE_UP, DECLARE_PASS -> game.declare(cardPlayer, action);
            case TIDY_HAND -> game.tidyHand(cardPlayer);
        }
    }

}
