package chartalandlords.doudizhu.client;

import chartalandlords.doudizhu.Doudizhu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 斗地主背景音乐：默认用原版音符盒音效在客户端<b>现场合成</b>一段快节奏的中国风循环。
 *
 * <h2>为什么不是「欢乐斗地主」那首原曲</h2>
 * <p>那首曲子是腾讯的原创音乐作品（曲谱站点的作者署名就是腾讯），把它的旋律抄进 jar 里分发
 * 属于复制他人音乐作品，所以这个模组<b>不</b>包含它。这里是一段**原创**的、按同一路数写的
 * 五声音阶小调：150 BPM 的十六分音符、明亮竹笛主旋律、琵琶式琶音伴奏、大鼓 + 板 + 小锣的锣鼓点，
 * 听感上就是「中国牌桌喜庆小曲」这一类，而不是某首具体作品的翻版。</p>
 *
 * <p>如果你自己拥有那首曲子的使用授权，把它换成真实音频只要两步，代码不用改：</p>
 * <ol>
 *   <li>做一个资源包，放 {@code assets/chartalandlords/sounds/doudizhu_bgm.ogg}，
 *       并在 {@code assets/chartalandlords/sounds.json} 里注册
 *       {@code "chartalandlords:doudizhu_bgm"} 指向它；</li>
 *   <li>进游戏，什么都不用按。{@link #useCustomTrack()} 会在运行时探测到这个音效事件，
 *       自动改放整首曲目（循环），内置小调同时静音。</li>
 * </ol>
 *
 * <h2>混音与音量</h2>
 * <p>全部走 {@link SoundSource#MUSIC}，所以原版的「音乐」音量滑条直接控制它，MUSIC 拉到 0 就是静音。
 * 刻意不用 {@code SimpleSoundInstance.forUI(...)}：那个走的是 MASTER，会绕过音乐滑条，
 * 让「关掉游戏音乐」的玩家仍然被迫听牌桌 BGM。</p>
 *
 * <h2>确定性</h2>
 * <p>旋律与配器全部来自下面几张常量表，编曲在类初始化时一次算完（{@link #PLAN}），
 * 播放过程不用随机数：同样的 tick 序列一定得到同样的音，不会「每次进牌桌都不一样」。</p>
 */
@OnlyIn(Dist.CLIENT)
final class DoudizhuBgm {

    // ------------------------------------------------------------------ 真实音频挂钩

    /** 资源包可以提供这个音效事件来顶掉内置小调。 */
    private static final ResourceLocation CUSTOM_SOUND = Doudizhu.id("doudizhu_bgm");
    /** 同时要求这个文件真的存在，避免只注册了 sounds.json 却没放文件时静音。 */
    private static final ResourceLocation CUSTOM_OGG =
            ResourceLocation.fromNamespaceAndPath(Doudizhu.MOD_ID, "sounds/doudizhu_bgm.ogg");

    // ------------------------------------------------------------------ 曲式

    /** 每个格子多少 tick：2 tick = 十六分音符 @ 150 BPM，正是「喜庆小曲」的速度。 */
    private static final int STEP_TICKS = 2;
    /** 每小节多少格（4/4 拍，一格一个十六分音符）。 */
    private static final int STEPS_PER_BAR = 16;
    /** 一共几小节：4 小节一句，循环一次约 6.4 秒。 */
    private static final int BARS = 4;
    private static final int TOTAL_STEPS = BARS * STEPS_PER_BAR;
    /** 进牌桌后先安静这么久再起旋律，避免开界面就被音效砸一下。 */
    private static final int INITIAL_DELAY = 10;

    /** 休止符。 */
    private static final int REST = Integer.MIN_VALUE;

    /**
     * 主旋律（竹笛），一格一个半音偏移，{@code -} 是休止。
     *
     * <p>写的是 C 宫五声音阶的相对音级（0 2 4 7 9 = 宫商角徵羽），
     * 实际发声再整体移调 {@link #MELODY_SHIFT}，把音域压进音符盒好听的 0.5~2.0 倍速区间。
     * 每一行长 16 格 = 一小节，四个乐句分别是「起、扬、承、落」，最后一格落在属音上，
     * 于是循环回第一小节时不会顿一下。</p>
     */
    private static final String MELODY = """
            0 - 4 7  9 9 9 -  7 7 7 -  4 4 7 -
            9 - 12 9 7 - 4 7  9 9 9 -  12 - - -
            12 - 14 12 9 - 7 9 12 12 12 - 9 9 7 -
            4 7 9 12 14 - 12 9 7 - 4 2 0 - - -
            """;

    /** 旋律整体移调（半音）：-4 之后音域是 -4..+12，正好落在 0.79~2.00 倍速。 */
    private static final int MELODY_SHIFT = -4;

    /** 每小节的根音（五声音阶里的宫 → 徵 → 羽 → 徵，和旋律的落音对得上）。 */
    private static final int[] BAR_ROOTS = {0, 7, 9, 7};

    /** 伴奏琶音型：一小节 8 个十六分音符，绕根音上下滚动，模仿琵琶/古筝的分解和弦。 */
    private static final int[] ARPEGGIO = {0, 7, 12, 7, 0, 7, 12, 7};

    /** 伴奏整体比旋律低一个八度，主次分明。 */
    private static final int ACCOMPANIMENT_SHIFT = -12;

    // ------------------------------------------------------------------ 配器

    // 音量：小节头一拍会同时响 5 个音（主旋律 + 伴奏 + 低音 + 大鼓 + 小锣），
    // 所以每个都压在 0.4 以下，叠加后的峰值留出余量，不至于在混音里削波炸掉。
    private static final float LEAD_VOLUME = 0.35f;
    private static final float PLUCK_VOLUME = 0.16f;
    private static final float BASS_VOLUME = 0.35f;
    private static final float DRUM_VOLUME = 0.25f;
    private static final float TICK_VOLUME = 0.12f;
    private static final float GONG_VOLUME = 0.18f;

    /** 一个格子上同时要发出的所有音。 */
    private record Note(Holder<SoundEvent> event, float pitch, float volume) {}

    /** 编好的曲：下标 = 第几格，值 = 那一格要发的音。类初始化时算一次，之后只读。 */
    private static final List<List<Note>> PLAN = buildPlan();

    /** 全局开关。做成静态是因为它属于「这个客户端想不想听」，不属于任何一局牌。 */
    private static boolean enabled = true;

    private int step;
    private int wait = INITIAL_DELAY;
    /** 正在播放的资源包曲目（没有就是 null）。 */
    private SoundInstance custom;
    private boolean customChecked;
    private boolean customAvailable;

    /** 开关当前是否打开（界面 ♪ 徽章回显用）。 */
    static boolean isEnabled() {
        return enabled;
    }

    /** 翻转开关；关掉时立刻静音并把步进器复位，重新打开会从头起句。 */
    static void toggle() {
        enabled = !enabled;
    }

    /** 每个客户端 tick 调一次。 */
    void tick() {
        if (!enabled) {
            resetSequencer();
            stopCustom();
            return;
        }
        if (useCustomTrack()) {
            if (custom == null || !isPlaying(custom)) {
                custom = startCustom();
            }
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        for (Note note : PLAN.get(step)) {
            play(note);
        }
        step = (step + 1) % TOTAL_STEPS;
        wait = STEP_TICKS;
    }

    /** 关界面 / 关开关时调用。 */
    void stop() {
        resetSequencer();
        stopCustom();
        // 下次开界面重新探测一次资源包曲目：玩家可能在两次之间加/删了资源包（或按了 F3+T）
        customChecked = false;
        customAvailable = false;
    }

    private void resetSequencer() {
        step = 0;
        wait = INITIAL_DELAY;
    }

    // ------------------------------------------------------------------ 资源包曲目

    /** 资源包有没有提供 {@code chartalandlords:doudizhu_bgm}；只探测一次。 */
    private boolean useCustomTrack() {
        if (customChecked) {
            return customAvailable;
        }
        customChecked = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return false;
        }
        SoundManager sounds = minecraft.getSoundManager();
        ResourceManager resources = minecraft.getResourceManager();
        // 双重条件：音效事件真的注册了（有 sounds.json），并且 ogg 文件也在。
        // 只看其中一个都会在「注册了但文件缺失」时变成一片死寂。
        customAvailable = sounds.getSoundEvent(CUSTOM_SOUND) != null
                && resources != null
                && resources.getResource(CUSTOM_OGG).isPresent();
        if (customAvailable) {
            Doudizhu.LOGGER.info("Doudizhu BGM: using the resource-pack track {} instead of the built-in tune",
                    CUSTOM_SOUND);
        }
        return customAvailable;
    }

    private SoundInstance startCustom() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return null;
        }
        SoundInstance instance = new SimpleSoundInstance(CUSTOM_SOUND, SoundSource.MUSIC, 1.0F, 1.0F,
                SoundInstance.createUnseededRandom(), true, 0, SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0, true);
        minecraft.getSoundManager().play(instance);
        return instance;
    }

    private static boolean isPlaying(SoundInstance instance) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.getSoundManager() != null
                && minecraft.getSoundManager().isActive(instance);
    }

    private void stopCustom() {
        if (custom == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().stop(custom);
        }
        custom = null;
    }

    // ------------------------------------------------------------------ 编曲

    private static List<List<Note>> buildPlan() {
        int[] melody = decode(MELODY);
        if (melody.length != TOTAL_STEPS) {
            throw new IllegalStateException("BGM melody must be " + TOTAL_STEPS + " steps but has "
                    + melody.length);
        }
        List<List<Note>> plan = new ArrayList<>(TOTAL_STEPS);
        for (int index = 0; index < TOTAL_STEPS; index++) {
            plan.add(new ArrayList<>(4));
        }
        for (int index = 0; index < TOTAL_STEPS; index++) {
            if (melody[index] != REST) {
                plan.get(index).add(note(SoundEvents.NOTE_BLOCK_FLUTE, melody[index] + MELODY_SHIFT, LEAD_VOLUME));
            }
        }
        for (int bar = 0; bar < BARS; bar++) {
            int base = bar * STEPS_PER_BAR;
            int root = BAR_ROOTS[bar % BAR_ROOTS.length] + ACCOMPANIMENT_SHIFT;
            for (int index = 0; index < ARPEGGIO.length; index++) {
                plan.get(base + index * 2).add(note(SoundEvents.NOTE_BLOCK_BANJO, root + ARPEGGIO[index],
                        PLUCK_VOLUME));
            }
            // 低音：每小节第 1、3 拍，第二拍走五度，撑住和声
            plan.get(base).add(note(SoundEvents.NOTE_BLOCK_BASS, root, BASS_VOLUME));
            plan.get(base + 8).add(note(SoundEvents.NOTE_BLOCK_BASS, root + 7, BASS_VOLUME - 0.05f));
            // 锣鼓：大鼓踩 1、3 拍，板走后半拍，小锣点小节头
            plan.get(base).add(note(SoundEvents.NOTE_BLOCK_BASEDRUM, 0, DRUM_VOLUME));
            plan.get(base + 8).add(note(SoundEvents.NOTE_BLOCK_BASEDRUM, 0, DRUM_VOLUME - 0.04f));
            plan.get(base).add(note(SoundEvents.NOTE_BLOCK_BELL, 12, GONG_VOLUME));
            for (int at = 2; at < STEPS_PER_BAR; at += 4) {
                plan.get(base + at).add(note(SoundEvents.NOTE_BLOCK_HAT, 5, TICK_VOLUME));
            }
        }
        return List.copyOf(plan);
    }

    /** 把「一行一节的空格分隔谱」拆成半音数组。 */
    private static int[] decode(String pattern) {
        List<Integer> values = new ArrayList<>();
        for (String token : pattern.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            values.add("-".equals(token) ? REST : Integer.parseInt(token));
        }
        int[] result = new int[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    /** 半音偏移 → 音符盒音高；夹在 0.5~2.0 之间，超出范围会又尖又糊。 */
    private static Note note(Holder<SoundEvent> event, int semitones, float volume) {
        float pitch = (float) Math.pow(2.0, semitones / 12.0);
        return new Note(event, Math.max(0.5f, Math.min(2.0f, pitch)), volume);
    }

    private static void play(Note note) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return;
        }
        minecraft.getSoundManager().play(new SimpleSoundInstance(
                note.event().value().getLocation(),
                SoundSource.MUSIC,
                note.volume(),
                note.pitch(),
                SoundInstance.createUnseededRandom(),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0, 0.0, 0.0,
                true));
    }
}
