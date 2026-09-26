package chartalandlords.doudizhu.game.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 自定义四家手牌、离线跑规则判定的命令行（零 Minecraft 依赖）。
 *
 * <p>用法（推荐走 {@code tools/debug-hands.ps1}，它会自动编译）：</p>
 * <pre>
 * java -cp &lt;classes&gt; chartalandlords.doudizhu.game.engine.DebugHandsMain \
 *      --players 4 --hand 0 "3 3 3 4 5" --hand 1 "S B 7 8 9" --landlord 0 \
 *      --bottom "10 J Q" --last "0:5 5" --turn 1 --profile 1 --rule mixed-joker-rocket
 * </pre>
 *
 * <p>不带参数运行时是自检模式：跑一组断言并打印一份示例报告，任何一条失败就以非零退出，
 * 因此 {@code tools/logic-check.ps1} 会顺带验证这个调试工具本身没坏。</p>
 */
public final class DebugHandsMain {

    /** 自检输出是否只用 ASCII（管道 / CI 日志下不花屏）。 */
    private static boolean asciiOutput;

    public static void main(String[] args) {
        if (args.length == 0) {
            smoke(false);
            return;
        }
        if (args.length == 1 && args[0].equals("--ascii")) {
            smoke(true);
            return;
        }

        int players = 4;
        Map<Integer, String> handSpecs = new TreeMap<>();
        String bottomSpec = null;
        String lastSpec = null;
        String probeSpec = null;
        int landlord = -1;
        int turn = 0;
        int profileIndex = 1;
        boolean ascii = false;
        List<String> toggles = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                case "--ascii" -> ascii = true;
                case "--players" -> players = Integer.parseInt(next(args, ++i, arg));
                case "--hand" -> {
                    int seat = Integer.parseInt(next(args, ++i, arg));
                    handSpecs.put(seat, next(args, ++i, arg));
                }
                case "--landlord" -> landlord = Integer.parseInt(next(args, ++i, arg));
                case "--bottom" -> bottomSpec = next(args, ++i, arg);
                case "--turn" -> turn = Integer.parseInt(next(args, ++i, arg));
                case "--last" -> lastSpec = next(args, ++i, arg);
                case "--probe" -> probeSpec = next(args, ++i, arg);
                case "--profile" -> profileIndex = Integer.parseInt(next(args, ++i, arg));
                case "--rule" -> toggles.addAll(Arrays.asList(next(args, ++i, arg).split(",")));
                default -> throw new IllegalArgumentException("未知参数: " + arg);
            }
        }
        if (players < 2) {
            throw new IllegalArgumentException("--players 至少是 2");
        }

        RuleOptions rule = RuleOptions.of(players >= 4);
        for (String toggle : toggles) {
            rule = apply(rule, toggle.trim().toLowerCase(Locale.ROOT));
        }

        int[][] hands = new int[players][];
        for (int seat = 0; seat < players; seat++) {
            hands[seat] = new int[0];
        }
        for (Map.Entry<Integer, String> entry : handSpecs.entrySet()) {
            if (entry.getKey() < 0 || entry.getKey() >= players) {
                throw new IllegalArgumentException("座位越界: " + entry.getKey());
            }
            hands[entry.getKey()] = DebugHands.parse(entry.getValue());
        }

        Combo previous = null;
        int previousSeat = -1;
        if (lastSpec != null) {
            previousSeat = seatOf(lastSpec);
            int[] cards = cardsOf(lastSpec);
            previous = RuleEngine.classify(cards, rule);
            if (previous == null) {
                System.out.println("!! 上一手 " + DebugHands.format(cards, ascii)
                        + " 在这套规则下不是合法牌型，已按首出处理");
            }
        }

        DebugHands.Table table = new DebugHands.Table(rule, hands, landlord,
                bottomSpec == null ? null : DebugHands.parse(bottomSpec), turn, previousSeat, previous);

        int[] probe = probeSpec == null ? null : cardsOf(probeSpec);
        int probeSeat = probeSpec == null ? table.turnSeat() : seatOf(probeSpec);

        System.out.println();
        System.out.print(DebugHands.report(table, AiProfile.of(profileIndex), probe, probeSeat, ascii));
        System.out.println();
    }

    // ------------------------------------------------------------------ 参数工具

    private static String next(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " 后面缺少取值");
        }
        return args[index];
    }

    private static int seatOf(String spec) {
        int colon = spec.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("要写成「座位:牌」的形式，例如 0:5 5 → " + spec);
        }
        return Integer.parseInt(spec.substring(0, colon).trim());
    }

    private static int[] cardsOf(String spec) {
        int colon = spec.indexOf(':');
        return DebugHands.parse(colon < 0 ? spec : spec.substring(colon + 1));
    }

    private static RuleOptions apply(RuleOptions rule, String toggle) {
        return switch (toggle) {
            case "mixed-joker-rocket" -> rule.withMixedJokerRocket(true);
            case "no-mixed-joker-rocket" -> rule.withMixedJokerRocket(false);
            case "no-three-with-two" -> rule.withThreeWithTwo(false);
            case "three-with-two" -> rule.withThreeWithTwo(true);
            case "no-four-with-two" -> rule.withFourWithTwo(false);
            case "four-with-two" -> rule.withFourWithTwo(true);
            case "jokers-as-wing-pair" -> rule.withJokersAsWingPair(true);
            case "no-jokers-as-wing-pair" -> rule.withJokersAsWingPair(false);
            case "no-four-two-pair-kicker" -> rule.withFourTwoSinglesMayBePair(false);
            case "four-two-pair-kicker" -> rule.withFourTwoSinglesMayBePair(true);
            default -> throw new IllegalArgumentException("未知规则开关: " + toggle);
        };
    }

    // ------------------------------------------------------------------ 用法 / 自检

    private static void usage() {
        System.out.println("""
                DebugHandsMain - 自定义四家手牌，离线跑规则判定

                  --players N           人数（默认 4；>=4 视为两副牌）
                  --hand SEAT "牌"      设定某家手牌（可重复），例：--hand 0 "3 3 3 4 5"
                  --landlord SEAT       地主座位（默认 -1 未定）
                  --bottom "牌"         底牌，例：--bottom "10 J Q"
                  --turn SEAT           该谁出（默认 0）
                  --last SEAT:"牌"      上一手，例：--last "0:5 5"（省略即首出）
                  --probe SEAT:"牌"     检查这一手能不能出，例：--probe "1:S B"
                  --profile 0|1|2       AI 档位：0 保守 / 1 均衡（默认）/ 2 激进
                  --rule a,b            规则开关，逗号分隔：
                                        mixed-joker-rocket（四人局两张王也算王炸）
                                        no-three-with-two / no-four-with-two
                                        jokers-as-wing-pair / no-four-two-pair-kicker
                  --ascii               输出只用 ASCII（大小王写 S / B）
                  --help                显示这段说明

                牌面写法：3 4 5 6 7 8 9 10 J Q K A 2，外加 S（小王）/ B（大王）；
                分隔符用空格、逗号或顿号都行。

                不带参数运行 = 自检模式（跑断言 + 打印示例报告）。
                """);
    }

    private static void smoke(boolean ascii) {
        asciiOutput = ascii;
        int[] parsed = DebugHands.parse("B S A K 10 3,4、5");
        check(Arrays.equals(parsed, new int[]{3, 4, 5, 10, 13, 14, 16, 17}),
                label("parse: 空格/逗号/顿号混用 → 升序点数", "parse: mixed separators -> sorted values"));
        check(DebugHands.format(new int[]{17, 16, 14, 3, 3}, ascii).equals(ascii ? "3 3 A S B" : "3 3 A 小王 大王"),
                label("format: 点数 → 可读写法", "format: values -> readable text"));
        // 回归保护：牌面 "2" 是第二大点数 15，不是数字 2（写错过一次，手牌里有 2 就整局报错）
        check(DebugHands.value("2") == RuleEngine.TWO_VALUE
                        && DebugHands.value("10") == 10
                        && DebugHands.value("A") == RuleEngine.A_VALUE
                        && DebugHands.value("S") == RuleEngine.SMALL_JOKER_VALUE
                        && DebugHands.value("B") == RuleEngine.BIG_JOKER_VALUE,
                "parse: 2 / 10 / A / S / B -> 15 / 10 / 14 / 16 / 17");

        RuleOptions p3 = RuleOptions.standard();
        RuleOptions p4 = RuleOptions.doubleDeck();
        RuleOptions house = p4.withMixedJokerRocket(true);

        expect(RuleEngine.classify(new int[]{16, 17}, p3), ComboType.ROCKET,
                "3p: small+big joker is the rocket");
        expect(RuleEngine.classify(new int[]{16, 17}, p4), null,
                "4p: small+big joker is illegal by default");
        expect(RuleEngine.classify(new int[]{16, 17}, house), ComboType.ROCKET,
                "4p: small+big joker is a rocket with the house rule on");
        expect(RuleEngine.classify(new int[]{16, 16, 17, 17}, house), ComboType.ROCKET,
                "4p: four jokers stay the rocket");
        expect(RuleEngine.classify(new int[]{16, 16, 17}, house), null,
                "4p: two small + one big is never a rocket");

        Combo rocket2 = RuleEngine.classify(new int[]{16, 17}, house);
        Combo rocket4 = RuleEngine.classify(new int[]{16, 16, 17, 17}, house);
        check(RuleEngine.beats(rocket4, rocket2),
                "house rule: the four-joker rocket beats the two-joker one");
        check(!RuleEngine.beats(rocket2, rocket4),
                "house rule: the two-joker rocket cannot beat the four-joker one");
        check(!RuleEngine.beats(rocket4, rocket4),
                "house rule: equal rockets still never beat each other");

        DebugHands.Table table = new DebugHands.Table(house,
                new int[][]{
                        {3, 3, 4, 5, 6, 7, 8},
                        {16, 17, 7, 8, 9, 9, 10},
                        {3, 3, 3, 4, 4, 5, 6},
                        {11, 12, 13, 5, 6, 7, 8}},
                0, new int[]{14, 15, 9}, 1, 0, RuleEngine.classify(new int[]{3, 3}, house));
        check(table.canPlay(1, new int[]{16, 17}),
                "table: the house rocket answers a pair");
        check(!table.canPlay(2, new int[]{3, 3, 3, 4, 4}),
                "table: triple-with-pair cannot answer a pair");
        check(!table.canPlay(1, new int[]{16, 16}),
                "table: a pair of small jokers does not answer a pair of 3s");
        check(table.hasCards(1, new int[]{16, 17}) && !table.hasCards(1, new int[]{16, 16, 17}),
                "table: hasCards is a real multiset check");

        System.out.println();
        System.out.println(ascii
                ? "  Seat 1: " + table.moves(1).size() + " moves, hint = "
                        + DebugHands.format(table.hint(1), true) + ", AI = "
                        + DebugHands.format(table.aiPlay(1, AiProfile.of(1)), true)
                : "  座位 1 能出的组合：" + table.moves(1).size() + " 手，提示 = "
                        + DebugHands.format(table.hint(1)) + "，AI 决策 = "
                        + DebugHands.format(table.aiPlay(1, AiProfile.of(1))));
        System.out.println();
        System.out.print(DebugHands.report(table, AiProfile.of(1), new int[]{16, 17}, 1, ascii));
        System.out.println();
        System.out.println("DEBUG HANDS " + (failed == 0 ? "PASS" : "FAIL")
                + ": " + (passed + failed) + " checks, " + failed + " failures");
        if (failed > 0) {
            throw new IllegalStateException(failed + " debug-hand checks failed");
        }
    }

    private static String label(String chinese, String english) {
        return asciiOutput ? english : chinese;
    }

    // 自检计数器（Java 允许字段出现在方法之后，放这里离使用点最近）
    private static int passed;
    private static int failed;

    private static void check(boolean condition, String label) {
        if (condition) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((condition ? "  ok    " : "  FAIL  ") + label);
    }

    private static void expect(Combo combo, ComboType expected, String label) {
        ComboType actual = combo == null ? null : combo.type();
        boolean ok = actual == expected;
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        System.out.println((ok ? "  ok    " : "  FAIL  ") + label
                + (ok ? "" : "  (expected " + expected + ", got " + actual + ")"));
    }
}
