package chartalandlords.doudizhu.client;

import chartalandlords.doudizhu.game.DoudizhuMenu;
import dev.lucaargolo.charta.client.render.screen.CardScreen;
import dev.lucaargolo.charta.client.render.screen.widgets.CardWidget;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.utils.CardImage;
import dev.lucaargolo.charta.common.utils.ChartaGuiGraphics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * 「看牌」界面：一页摊开每家的手牌与底牌。
 *
 * <h2>为什么是一个独立界面，而不是牌局界面上盖一层</h2>
 * <p>第一版是在 {@code DoudizhuScreen} 里自绘一层半透明面板，结果框架画的卡牌与按钮全部压在面板上面
 * （它们的绘制时机与牌局界面自己的渲染顺序纠缠在一起，自绘的 z 压不住）。这里改用 Charta 自己的
 * 做法（{@code DeckScreen} / {@code HistoryScreen} 就是这么干的）：<b>换一个 Screen</b>。
 * 牌局界面此时根本不渲染，于是没有任何东西跟它抢图层；牌也用框架的 {@link CardWidget} 画，
 * 悬停立体感、卡牌名 tooltip、发光后处理全部白拿。</p>
 *
 * <h2>能看到什么</h2>
 * <p>规则只有一条——<b>该公开的公开，不该公开的一张都不露</b>：
 * 自己与明牌过的座位画正面牌，其余座位按真实张数画牌背（张数是公开信息，牌面不是）。
 * 数据全部来自 {@link DoudizhuMenu} 已经同步好的内容，所以这个界面<b>不需要任何新协议</b>。</p>
 */
@OnlyIn(Dist.CLIENT)
public class DoudizhuHandsScreen extends CardScreen {

    /** 左侧文字列的一行（牌由 {@link CardWidget} 画，这里只画文字）。 */
    private record Row(int top, int height, Component name, Component role, Component state) {}

    /**
     * 一张牌背：不进控件表，直接在 {@link #renderFg} 里贴图。
     *
     * <p>藏着的座位动辄 25 张牌背，如果每张都做成 {@link CardWidget}，一帧要几百次纹理绘制 +
     * 一百多次发光缓冲切换；而且牌背本来就没有牌面可看（不需要悬停立体感与 tooltip）。
     * 所以正面牌走控件（白拿悬停与 tooltip），牌背直接贴。</p>
     */
    private record Back(ResourceLocation texture, float x, float y, float width, float height) {}

    private final Screen parent;
    private final DoudizhuMenu menu;
    private final List<Row> rows = new ArrayList<>();
    private final List<Back> backs = new ArrayList<>();
    /** 上一次画这一页时的局面指纹（变了就重建，见 {@link #signature()}）。 */
    private String lastSignature;

    public DoudizhuHandsScreen(@Nullable Screen parent, DoudizhuMenu menu) {
        super(Component.translatable("message.chartalandlords.hand_view.title"));
        this.parent = parent;
        this.menu = menu;
    }

    @Override
    protected void init() {
        rows.clear();
        backs.clear();
        lastSignature = signature();
        Component back = Component.literal("\ue5c4").withStyle(ChartaMod.SYMBOLS);
        addRenderableWidget(new Button.Builder(back, button -> onClose())
                .bounds(5, 5, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("message.charta.go_back")))
                .build());

        int seats = Math.max(1, this.menu.seatCount());
        int sections = seats + 1;
        int top = 30;
        int bottom = height - 16;
        int rowHeight = Math.max(20, (bottom - top) / sections);
        // 牌按行高自适应：行高够就是原尺寸（37.5×52.5），不够就等比缩小，下限 0.34（约 13×18）
        float scale = Mth.clamp((rowHeight - 6f) / DoudizhuMenu.ROW_HEIGHT, 0.34f, 1f);
        float cardWidth = CardImage.WIDTH * 1.5f * scale;
        float cardHeight = CardImage.HEIGHT * 1.5f * scale;
        float cardsLeft = 12f + LABEL_WIDTH;
        float cardsRight = width - 16f;

        for (int seat = 0; seat < seats; seat++) {
            int rowTop = top + seat * rowHeight;
            boolean mine = seat == this.menu.mySeat();
            CardPlayer player = this.menu.seatPlayer(seat);
            Component name = player == null ? Component.literal("?") : player.getColoredName();
            rows.add(new Row(rowTop, rowHeight, name, roleText(seat), stateText(seat, mine)));
            fan(this.menu.viewerHandCards(seat), rowTop, rowHeight, cardWidth, cardHeight,
                    cardsLeft, cardsRight, scale);
        }

        int bottomTop = top + seats * rowHeight;
        boolean shown = this.menu.isBottomRevealed();
        rows.add(new Row(bottomTop, rowHeight,
                Component.translatable("message.chartalandlords.hand_view.bottom"),
                Component.translatable("message.chartalandlords.hand_view.bottom_role"),
                Component.translatable(shown
                        ? "message.chartalandlords.hand_view.tag_public"
                        : "message.chartalandlords.hand_view.bottom_hidden")
                        .copy()
                        .append(" · ")
                        .append(Component.translatable("message.chartalandlords.hand_view.count",
                                this.menu.viewerBottomCards().size()))));
        fan(this.menu.viewerBottomCards(), bottomTop, rowHeight, cardWidth, cardHeight,
                cardsLeft, cardsRight, scale);
    }

    /** 名字下面那行：地主 / 农民（还没定地主时写「待定」），自己是地主或农民时再标一个「你」。 */
    private Component roleText(int seat) {
        int landlord = this.menu.landlordSeat();
        Component role = landlord < 0
                ? Component.translatable("message.chartalandlords.hand_view.role_pending")
                : Component.translatable(seat == landlord
                        ? "message.chartalandlords.landlord_tag"
                        : "message.chartalandlords.farmer_tag");
        if (seat == this.menu.mySeat()) {
            return role.copy().append(" · ").append(Component.translatable("message.chartalandlords.hand_view.you"));
        }
        return role;
    }

    /** 第三行：这手牌是公开的还是隐藏的，以及还剩几张。 */
    private Component stateText(int seat, boolean mine) {
        Component state = Component.translatable(mine || this.menu.isSeatRevealed(seat)
                ? "message.chartalandlords.hand_view.tag_public"
                : "message.chartalandlords.hand_view.tag_hidden");
        return state.copy().append(" · ").append(Component.translatable(
                "message.chartalandlords.hand_view.count", this.menu.seatHandSize(seat)));
    }

    /**
     * 把一手牌摊成一行：正面牌用框架的 {@link CardWidget}（悬停立体感 + 卡牌名 tooltip），
     * 牌背直接贴图；横向间距与 Charta 的卡槽一致——优先均分整行、
     * 但每张最多露出 {@code 牌宽 × 1.1}，牌少时整行居中。
     */
    private void fan(List<Card> cards, int rowTop, int rowHeight, float cardWidth, float cardHeight,
                     float left, float right, float scale) {
        int count = cards.size();
        if (count <= 0) {
            return;
        }
        float available = right - left;
        float pitch = count > 1 ? (available - cardWidth) / (count - 1) : cardWidth;
        pitch = Math.min(pitch, cardWidth * 1.1f);
        pitch = Math.max(pitch, cardWidth * 0.22f);
        float total = cardWidth + pitch * (count - 1);
        float x = left + Math.max(0f, (available - total) / 2f);
        float y = rowTop + (rowHeight - cardHeight) / 2f;
        for (Card card : cards) {
            if (card.flipped()) {
                ResourceLocation texture = this.menu.getDeck().getTexture(false);
                backs.add(new Back(texture == null ? ChartaMod.MISSING_CARD : texture, x, y, cardWidth, cardHeight));
            } else {
                addRenderableWidget(new CardWidget(this, card, this.menu.getDeck(), x, y, scale));
            }
            x += pitch;
        }
    }

    @Override
    protected void renderFg(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        for (Back back : backs) {
            ChartaGuiGraphics.blitCard(guiGraphics, back.texture(), back.x(), back.y(),
                    back.width(), back.height());
        }
        guiGraphics.drawCenteredString(font, title, width / 2, 11, 0xFFF0F0F0);
        for (Row row : rows) {
            int textTop = row.top() + Math.max(2, (row.height() - 30) / 2);
            guiGraphics.drawString(font, row.name(), 12, textTop, 0xFFFFFFFF, true);
            guiGraphics.drawString(font, row.role(), 12, textTop + 10, 0xFFB8C8B8, true);
            guiGraphics.drawString(font, row.state(), 12, textTop + 20, 0xFFFFD54F, true);
            // 行之间的分隔线：一行一行看得清，而不是一片牌糊在一起
            if (row.top() > 30) {
                guiGraphics.fill(8, row.top() - 1, width - 8, row.top(), 0x30FFFFFF);
            }
        }
        Component hint = Component.translatable("message.chartalandlords.hand_view.hint");
        guiGraphics.drawString(font, hint, (width - font.width(hint)) / 2, height - 12, 0xFF90A090, true);
    }

    /** 左侧文字列的宽度（名字 / 阵营 / 状态三行小字）。 */
    private static final int LABEL_WIDTH = 76;

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_V) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(parent instanceof DoudizhuScreen gameScreen)) {
            return;
        }
        // 牌局界面不渲染也不 tick，但 BGM 不该在看牌的时候断掉
        if (this.minecraft == null || this.minecraft.player == null
                || this.minecraft.player.containerMenu != this.menu) {
            // 牌局已经没了（走远了 / 被请下桌）：别再放着音乐，直接回世界
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
            return;
        }
        gameScreen.tickMusic();
        // 这一页是「打开那一刻」的快照，但别人出牌、明牌、换阶段都会让内容过期；
        // 所以只要局面指纹变了就重建一次控件（牌不多，重建很便宜）。
        String signature = signature();
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            rebuildWidgets();
        }
    }

    /** 局面指纹：阶段 / 出牌序号 / 地主 + 每家的张数与标记 + 底牌是否翻开。 */
    private String signature() {
        StringBuilder builder = new StringBuilder();
        builder.append(this.menu.phase().ordinal()).append('/')
                .append(this.menu.playRevision()).append('/')
                .append(this.menu.landlordSeat()).append('/');
        for (int seat = 0; seat < this.menu.seatCount(); seat++) {
            builder.append(this.menu.seatHandSize(seat)).append(',')
                    .append(this.menu.seatMarks(seat)).append(';');
        }
        return builder.append(this.menu.isBottomRevealed()).toString();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
