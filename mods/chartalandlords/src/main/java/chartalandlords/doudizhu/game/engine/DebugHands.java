package chartalandlords.doudizhu.game.engine;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 调试用的「自定义牌局」：把任意四家手牌、地主、底牌和上一手喂进规则引擎，直接看判定结果
 * （合法牌型 / 能出什么 / 提示 / AI 决策）。
 *
 * <p>刻意留在 {@code game/engine} 里：这里零 Minecraft 依赖，所以
 * {@code tools/debug-hands.ps1} 的离线命令行、测试断言，以及以后游戏内的调试入口
 * （从真实牌局导出 {@link Table}）能共用同一份实现，不会出现「两套调试逻辑互相不一致」。
 * 它只是纯计算，不注册任何东西，发布 jar 里也只是一个没人调用的小工具类。</p>
 *
 * <p>牌面写法：{@code 3 4 5 6 7 8 9 10 J Q K A 2} 加 {@code S}（小王）/ {@code B}（大王），
 * 分隔符可以是空格、逗号、顿号，大小写不敏感。</p>
 */
public final class DebugHands {

    private DebugHands() {}

    // ------------------------------------------------------------------ 牌面写法

    /** {@code "3 3 4 J S B"} → {@code {3,3,4,11,16,17}}（升序）；无法识别的记号抛异常。 */
    public static int[] parse(String spec) {
        if (spec == null) {
            return new int[0];
        }
        String cleaned = spec.replace(',', ' ').replace('，', ' ').replace('、', ' ').trim();
        if (cleaned.isEmpty()) {
            return new int[0];
        }
        String[] tokens = cleaned.split("\\s+");
        int[] values = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            values[i] = value(tokens[i]);
        }
        Arrays.sort(values);
        return values;
    }

    /** 单个记号 → 点数。 */
    public static int value(String token) {
        if (token == null) {
            throw new IllegalArgumentException("空的牌面记号");
        }
        String text = token.trim().toUpperCase(Locale.ROOT);
        switch (text) {
            case "S", "SJ", "W", "小王" -> {
                return RuleEngine.SMALL_JOKER_VALUE;
            }
            case "B", "BJ", "大王" -> {
                return RuleEngine.BIG_JOKER_VALUE;
            }
            case "T", "10" -> {
                return 10;
            }
            case "J" -> {
                return 11;
            }
            case "Q" -> {
                return 12;
            }
            case "K" -> {
                return 13;
            }
            case "A", "1" -> {
                return RuleEngine.A_VALUE;
            }
            case "2" -> {
                // 注意：牌面 "2" 不是数字 2，而是第二大点数 15（下面按数字解析会当成非法点数）
                return RuleEngine.TWO_VALUE;
            }
            default -> {
                // 落到下面的数字分支
            }
        }
        try {
            int number = Integer.parseInt(text);
            if (number >= RuleEngine.MIN_VALUE && number <= RuleEngine.MAX_VALUE) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的异常
        }
        throw new IllegalArgumentException("无法识别的牌面记号: " + token);
    }

    /** 点数 → 显示名（11→J、16→小王……）。 */
    public static String name(int value) {
        return name(value, false);
    }

    /** 点数 → 显示名；{@code ascii=true} 时大小王写成 {@code S} / {@code B}。 */
    public static String name(int value, boolean ascii) {
        return switch (value) {
            case 10 -> "10";
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            case RuleEngine.A_VALUE -> "A";
            case RuleEngine.TWO_VALUE -> "2";
            case RuleEngine.SMALL_JOKER_VALUE -> ascii ? "S" : "小王";
            case RuleEngine.BIG_JOKER_VALUE -> ascii ? "B" : "大王";
            default -> Integer.toString(value);
        };
    }

    /** 点数数组 → 规范写法（升序）。 */
    public static String format(int[] values) {
        return format(values, false);
    }

    /** 点数数组 → 规范写法（升序）；{@code ascii=true} 时纯 ASCII，管道/CI 下不会花屏。 */
    public static String format(int[] values, boolean ascii) {
        if (values == null || values.length == 0) {
            return ascii ? "(empty)" : "(空)";
        }
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        StringBuilder builder = new StringBuilder();
        for (int value : sorted) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(name(value, ascii));
        }
        return builder.toString();
    }

    /** 一副牌里每个点数有几张：3 人局 4 张 / 王各 1，4 人局两副牌 8 张 / 王各 2。 */
    public static int[] fullDeck(RuleOptions rule) {
        boolean four = rule != null && rule.fourPlayer();
        int[] deck = new int[RuleEngine.RANK_SLOTS];
        int copies = four ? 8 : 4;
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.TWO_VALUE; value++) {
            deck[value] = copies;
        }
        int jokers = four ? 2 : 1;
        deck[RuleEngine.SMALL_JOKER_VALUE] = jokers;
        deck[RuleEngine.BIG_JOKER_VALUE] = jokers;
        return deck;
    }

    // ------------------------------------------------------------------ 牌桌

    /**
     * 一局可任意改写的调试牌桌。
     *
     * @param rule         规则开关（null → 4 人默认）
     * @param hands        每家手牌的点数数组（下标 = 座位）
     * @param landlord     地主座位，-1 表示还没定
     * @param bottom       底牌，null/空表示不设
     * @param turnSeat     该谁行动（超出范围会绕回）
     * @param previousSeat 上一手是谁出的，-1 表示该家首出
     * @param previous     上一手牌型（null 表示首出）
     */
    public static final class Table {

        private final RuleOptions rule;
        private final int[][] hands;
        private final int landlord;
        private final int[] bottom;
        private final int turnSeat;
        private final int previousSeat;
        private final Combo previous;

        public Table(RuleOptions rule, int[][] hands, int landlord, int[] bottom,
                     int turnSeat, int previousSeat, Combo previous) {
            if (hands == null || hands.length < 2) {
                throw new IllegalArgumentException("至少要给两家手牌");
            }
            this.rule = rule == null ? RuleOptions.doubleDeck() : rule;
            this.hands = new int[hands.length][];
            for (int seat = 0; seat < hands.length; seat++) {
                this.hands[seat] = hands[seat] == null ? new int[0] : hands[seat].clone();
            }
            this.landlord = landlord >= 0 && landlord < hands.length ? landlord : -1;
            this.bottom = bottom == null ? new int[0] : bottom.clone();
            this.turnSeat = wrap(turnSeat);
            this.previousSeat = previousSeat < 0 ? -1 : wrap(previousSeat);
            this.previous = previous;
        }

        public int seats() {
            return hands.length;
        }

        public RuleOptions rule() {
            return rule;
        }

        public int landlord() {
            return landlord;
        }

        public boolean landlordAt(int seat) {
            return seat == landlord;
        }

        public int[] hand(int seat) {
            return hands[wrap(seat)].clone();
        }

        public int[] bottom() {
            return bottom.clone();
        }

        public int turnSeat() {
            return turnSeat;
        }

        public int previousSeat() {
            return previousSeat;
        }

        public Combo previous() {
            return previous;
        }

        /** 还没露面的张数（总牌堆 − 所有手牌 − 底牌）：AI 判断「外面还有没有牌」要它。 */
        public int[] unseenCounts() {
            int[] unseen = fullDeck(rule);
            for (int[] hand : hands) {
                for (int value : hand) {
                    if (value >= 0 && value < unseen.length) {
                        unseen[value]--;
                    }
                }
            }
            for (int value : bottom) {
                if (value >= 0 && value < unseen.length) {
                    unseen[value]--;
                }
            }
            for (int i = 0; i < unseen.length; i++) {
                if (unseen[i] < 0) {
                    unseen[i] = 0;
                }
            }
            return unseen;
        }

        /** 这一手是什么牌型（非法 → null）。 */
        public Combo classify(int[] play) {
            return RuleEngine.classify(play, rule);
        }

        /** 手牌里真的有这几张（多重集合包含）。 */
        public boolean hasCards(int seat, int[] play) {
            int[] held = RuleEngine.counts(hands[wrap(seat)]);
            int[] used = RuleEngine.counts(play);
            for (int value = 0; value < held.length; value++) {
                if (used[value] > held[value]) {
                    return false;
                }
            }
            return true;
        }

        /** 这一手对这家来说是否合法（牌型合法 + 手上有牌 + 压得过上一手）。 */
        public boolean canPlay(int seat, int[] play) {
            Combo combo = classify(play);
            return combo != null && hasCards(seat, play) && RuleEngine.beats(combo, previous);
        }

        /** 这家现在能出的所有组合（首出 = 全部合法牌型，跟牌 = 能压过上一手的）。 */
        public List<int[]> moves(int seat) {
            int[] hand = hands[wrap(seat)];
            return previous == null
                    ? RuleEngine.findLeads(hand, rule)
                    : RuleEngine.findBeats(hand, previous, rule);
        }

        /** 提示（最小可压的一手；没有 → null）。 */
        public int[] hint(int seat) {
            return RuleEngine.hint(hands[wrap(seat)], previous, rule);
        }

        /** AI 决策（null = 不出）。 */
        public int[] aiPlay(int seat, AiProfile profile) {
            return AiPolicy.choosePlayValues(hands[wrap(seat)], context(seat), rule, profile);
        }

        /** 给这家拼一个 AI 决策上下文：只喂「公开信息」（各家手牌数、自己是否地主、队友是否刚出过）。 */
        public AiContext context(int seat) {
            int who = wrap(seat);
            boolean isLandlord = landlordAt(who);
            if (previous == null) {
                return AiContext.lead(isLandlord, minOpponentHandSize(who), unseenCounts());
            }
            boolean teammateLed = !isLandlord && previousSeat >= 0 && !landlordAt(previousSeat);
            return AiContext.following(previous, isLandlord, teammateLed, teammateHandSize(who),
                    landlordHandSize(isLandlord, who), minOpponentHandSize(who), unseenCounts());
        }

        private int landlordHandSize(boolean isLandlord, int seat) {
            if (isLandlord) {
                return hands[seat].length;
            }
            return landlord >= 0 ? hands[landlord].length : -1;
        }

        private int teammateHandSize(int seat) {
            if (landlordAt(seat)) {
                return -1;
            }
            int smallest = -1;
            for (int other = 0; other < hands.length; other++) {
                if (other == seat || landlordAt(other)) {
                    continue;
                }
                smallest = smallest < 0 ? hands[other].length : Math.min(smallest, hands[other].length);
            }
            return smallest;
        }

        private int minOpponentHandSize(int seat) {
            if (!landlordAt(seat)) {
                return landlord >= 0 ? hands[landlord].length : -1;
            }
            int smallest = -1;
            for (int other = 0; other < hands.length; other++) {
                if (landlordAt(other)) {
                    continue;
                }
                smallest = smallest < 0 ? hands[other].length : Math.min(smallest, hands[other].length);
            }
            return smallest;
        }

        private int wrap(int seat) {
            int seats = hands.length;
            int wrapped = seat % seats;
            return wrapped < 0 ? wrapped + seats : wrapped;
        }
    }

    // ------------------------------------------------------------------ 报告

    /**
     * 把一局调试牌桌摊成多行文本（CLI 与以后游戏内的调试入口共用同一份输出）。
     *
     * @param probePlay 想检查的那一手，null/空表示不检查
     * @param probeSeat 检查时按哪个座位的手牌算
     */
    public static String report(Table table, AiProfile profile, int[] probePlay, int probeSeat) {
        return report(table, profile, probePlay, probeSeat, false);
    }

    /**
     * 同 {@link #report(Table, AiProfile, int[], int)}，{@code ascii=true} 时只输出 ASCII
     * （管道、CI 日志、非中文终端下不会花屏）。
     */
    public static String report(Table table, AiProfile profile, int[] probePlay, int probeSeat, boolean ascii) {
        StringBuilder out = new StringBuilder();
        out.append(label(ascii, "规则：", "Rules: "))
                .append(table.rule().fourPlayer()
                        ? (ascii ? "4 players / two decks" : "4 人两副牌")
                        : (ascii ? "3 players / one deck" : "3 人单副牌"))
                .append(label(ascii, "　两张王算王炸=", "  two jokers as rocket="))
                .append(onOff(ascii, table.rule().mixedJokerRocket()))
                .append(label(ascii, "　三带=", "  triple+kicker="))
                .append(onOff(ascii, table.rule().threeWithTwo()))
                .append(label(ascii, "　四带二=", "  four+two="))
                .append(onOff(ascii, table.rule().fourWithTwo()))
                .append(label(ascii, "　大小王当带牌=", "  both jokers as kicker="))
                .append(onOff(ascii, table.rule().jokersAsWingPair()))
                .append('\n');
        for (int seat = 0; seat < table.seats(); seat++) {
            int[] hand = table.hand(seat);
            String role = table.landlordAt(seat)
                    ? (ascii ? " LANDLORD" : " 地主")
                    : table.landlord() < 0 ? "" : (ascii ? " farmer" : " 农民");
            out.append(ascii
                    ? String.format("Seat %d%s (%d cards): %s%n", seat, role, hand.length, format(hand, true))
                    : String.format("座位 %d%s（%d 张）：%s%n", seat, role, hand.length, format(hand, false)));
        }
        if (table.bottom().length > 0) {
            out.append(label(ascii, "底牌：", "Bottom: ")).append(format(table.bottom(), ascii)).append('\n');
        }
        if (table.previous() != null) {
            out.append(ascii
                    ? String.format("Previous: seat %d played %s (key %s, %d cards)%n", table.previousSeat(),
                            table.previous().type(), name(table.previous().key(), true), table.previous().size())
                    : String.format("上一手：座位 %d 出的 %s（关键点数 %s · %d 张）%n", table.previousSeat(),
                            table.previous().type(), name(table.previous().key(), false), table.previous().size()));
        } else {
            out.append(label(ascii, "上一手：无（首出）\n", "Previous: none (this is a lead)\n"));
        }

        int seat = table.turnSeat();
        List<int[]> moves = table.moves(seat);
        out.append(ascii
                ? String.format("To act: seat %d%s%n", seat, table.landlordAt(seat) ? " (landlord)" : "")
                : String.format("该谁出：座位 %d%s%n", seat, table.landlordAt(seat) ? "（地主）" : ""));
        out.append(label(ascii, "  能出的组合：", "  Legal plays: ")).append(moves.size());
        out.append(label(ascii, " 手", " moves"));
        if (!moves.isEmpty()) {
            out.append(ascii ? " (first 12: " : "（前 12 手：");
            for (int i = 0; i < Math.min(12, moves.size()); i++) {
                if (i > 0) {
                    out.append(" / ");
                }
                out.append(format(moves.get(i), ascii));
            }
            if (moves.size() > 12) {
                out.append(" ...");
            }
            out.append(ascii ? ')' : '）');
        }
        out.append('\n');

        int[] hint = table.hint(seat);
        out.append(label(ascii, "  提示：", "  Hint: "))
                .append(hint == null
                        ? (ascii ? "nothing playable (pass)" : "没有能出的牌（只能不出）")
                        : format(hint, ascii))
                .append('\n');
        int[] ai = table.aiPlay(seat, profile);
        out.append(label(ascii, "  AI（", "  AI ("))
                .append(profileName(profile, ascii))
                .append(label(ascii, "）：", "): "))
                .append(ai == null ? (ascii ? "pass" : "不出") : format(ai, ascii))
                .append('\n');

        if (probePlay != null && probePlay.length > 0) {
            Combo combo = table.classify(probePlay);
            boolean beatsPrevious = combo != null && RuleEngine.beats(combo, table.previous());
            boolean canPlay = table.canPlay(probeSeat, probePlay);
            if (ascii) {
                out.append(String.format(
                        "  Check \"seat %d plays %s\": combo=%s  inHand=%s  beatsPrevious=%s  => %s%n",
                        probeSeat, format(probePlay, true),
                        combo == null ? "ILLEGAL" : combo.type().toString(),
                        table.hasCards(probeSeat, probePlay), beatsPrevious,
                        canPlay ? "CAN PLAY" : "CANNOT PLAY"));
            } else {
                out.append(String.format("  检查「座位 %d 出 %s」：牌型=%s　手上有=%s　压得过上一手=%s　结论=%s%n",
                        probeSeat, format(probePlay, false),
                        combo == null ? "非法" : combo.type().toString(),
                        table.hasCards(probeSeat, probePlay), beatsPrevious,
                        canPlay ? "可以出" : "不能出"));
            }
        }
        return out.toString();
    }

    /** 档位显示名：0 = 保守、1 = 均衡、2 = 激进。 */
    public static String profileName(AiProfile profile) {
        return profileName(profile, false);
    }

    /** 档位显示名，{@code ascii=true} 时用英文。 */
    public static String profileName(AiProfile profile, boolean ascii) {
        if (profile == null) {
            return ascii ? "default" : "默认";
        }
        return switch (profile.ordinal()) {
            case 0 -> ascii ? "cautious" : "保守";
            case 2 -> ascii ? "aggressive" : "激进";
            default -> ascii ? "balanced" : "均衡";
        };
    }

    private static String onOff(boolean ascii, boolean value) {
        return value ? (ascii ? "on" : "开") : (ascii ? "off" : "关");
    }

    private static String label(boolean ascii, String chinese, String english) {
        return ascii ? english : chinese;
    }
}
