package chartalandlords.doudizhu.game.engine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * WORKSTREAM A - disposable rule-engine harness.
 *
 * <p>Compiled with a bare {@code javac} and NO classpath at all: only the pure classes
 * ({@link RuleEngine}, {@link RuleOptions}, {@link Combo}, {@link ComboType}, {@link HandShape})
 * are referenced, which proves they have zero Minecraft / Charta dependencies.</p>
 *
 * <p>Build + run (from build/rule-harness):
 * {@code javac -d classes -sourcepath ..\..\src\main\java HarnessMain.java}
 * {@code java -cp classes chartalandlords.doudizhu.game.HarnessMain}</p>
 */
public final class HarnessMain {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        long start = System.nanoTime();
        classifyTable();
        classifyBoundaries();
        classifyFlags();
        classifyCaps();
        beatsTable();
        generatorInvariants();
        hintMinimality();
        breakBombFlag();
        handShapeChecks();
        minMovesChecks();
        unseenChecks();
        determinismAndPerformance();

        System.out.println();
        System.out.println("---------------------------------------------");
        System.out.printf("HARNESS %s: %d checks, %d failures (%.1f ms)%n",
                failures == 0 ? "PASS" : "FAIL", checks, failures,
                (System.nanoTime() - start) / 1_000_000.0);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ================================================================== 1. all combo types

    private static void classifyTable() {
        section("1. classify: all 14 combo values");
        RuleOptions std = RuleOptions.standard();
        RuleOptions four = RuleOptions.doubleDeck();

        expect(ComboType.SINGLE, v(3), std, "single");
        expect(ComboType.PAIR, v(3, 3), std, "pair");
        expect(ComboType.TRIPLE, v(3, 3, 3), std, "triple");
        expect(ComboType.TRIPLE_SINGLE, v(3, 3, 3, 4), std, "triple + single");
        expect(ComboType.TRIPLE_PAIR, v(3, 3, 3, 4, 4), std, "triple + pair");
        expect(ComboType.STRAIGHT, v(3, 4, 5, 6, 7), std, "straight of five");
        expect(ComboType.STRAIGHT, v(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14), std, "straight 3..A (12)");
        expect(ComboType.DOUBLE_STRAIGHT, v(3, 3, 4, 4, 5, 5), std, "double straight of three");
        expect(ComboType.AIRPLANE, v(3, 3, 3, 4, 4, 4), std, "airplane of two");
        expect(ComboType.AIRPLANE_SINGLE, v(3, 3, 3, 4, 4, 4, 5, 7), std, "airplane + two singles");
        expect(ComboType.AIRPLANE_SINGLE, v(3, 3, 3, 4, 4, 4, 5, 5), std, "airplane + pair-as-two-singles");
        expect(ComboType.AIRPLANE_PAIR, v(3, 3, 3, 4, 4, 4, 5, 5, 7, 7), std, "airplane + two pairs");
        expect(ComboType.FOUR_TWO_SINGLE, v(3, 3, 3, 3, 5, 7), std, "four + two singles");
        expect(ComboType.FOUR_TWO_SINGLE, v(3, 3, 3, 3, 5, 5), std, "four + one pair as two singles");
        expect(ComboType.FOUR_TWO_PAIR, v(3, 3, 3, 3, 5, 5, 7, 7), std, "four + two pairs");
        expect(ComboType.BOMB, v(3, 3, 3, 3), std, "bomb");
        expect(ComboType.ROCKET, v(16, 17), std, "rocket (3p)");
        expect(ComboType.ROCKET, v(16, 16, 17, 17), four, "rocket (4p, two decks)");
        expect(ComboType.PAIR, v(16, 16), std, "pair of small jokers");

        expectNull(v(3, 4, 5, 6), std, "straight of four");
        expectNull(v(3, 3, 4, 4), std, "double straight of two");
        expectNull(v(10, 11, 12, 13, 14, 15), std, "straight containing a two");
        expectNull(v(3, 4), std, "two unrelated cards");
        expectNull(v(3, 3, 3, 3, 5), std, "four of a kind plus junk (5 cards)");
        expectNull(v(3, 3, 3, 3, 5, 5, 5), std, "four + three (7 cards)");
        expectNull(v(16, 17), four, "a lone small+big joker is not a 4p rocket");
    }

    // ================================================================== 2. boundaries

    private static void classifyBoundaries() {
        section("2. classify: documented boundaries");
        RuleOptions std = RuleOptions.standard();
        RuleOptions four = RuleOptions.doubleDeck();

        int[] pairStraight12 = new int[24];
        for (int i = 0; i < 12; i++) {
            pairStraight12[i * 2] = 3 + i;
            pairStraight12[i * 2 + 1] = 3 + i;
        }
        expect(ComboType.DOUBLE_STRAIGHT, pairStraight12, std, "12-pair straight (3..A)");

        expect(ComboType.AIRPLANE, chainTriples(3, 6), std, "airplane of six triples");
        expectNull(chainTriples(3, 7), std, "airplane of seven triples (cap 6)");

        expect(ComboType.AIRPLANE_SINGLE, airplaneSingles(3, 5), std, "airplane x5 + five singles");
        expectNull(airplaneSingles(3, 6), std, "airplane x6 + six singles (cap 5)");
        expect(ComboType.AIRPLANE_PAIR, airplanePairs(3, 4), std, "airplane x4 + four pairs");
        expectNull(airplanePairs(3, 5), std, "airplane x5 + five pairs (cap 4)");

        expectNull(v(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 7, 7), std,
                "airplane wings mixing a single and a pair");
        expect(ComboType.AIRPLANE_SINGLE, v(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 7, 8), std,
                "airplane wings all singles");

        expect(ComboType.FOUR_TWO_SINGLE, v(3, 3, 3, 3, 5, 7), std, "four + two singles");
        expectNull(v(3, 3, 3, 3, 5), std, "four + one");
        expectNull(v(3, 3, 3, 3, 5, 5, 5), std, "four + three");
        expectNull(v(3, 3, 3, 3, 5, 5, 7, 7, 9), std, "four + four + one");
        expectNull(v(3, 3, 3, 3, 5, 5, 5, 5), std, "two four-groups + nothing");
        expectNull(v(3, 3, 3, 3, 5, 5, 5, 7), std, "four + triple + single");

        expect(ComboType.BOMB, v(3, 3, 3, 3, 3), four, "5-of-a-kind bomb (4p)");
        expect(ComboType.BOMB, v(3, 3, 3, 3, 3, 3, 3, 3), four, "8-of-a-kind bomb (4p)");
    }

    // ================================================================== 3. options flags
    private static void classifyFlags() {
        section("3. classify: RuleOptions flags");
        RuleOptions std = RuleOptions.standard();

        expect(ComboType.FOUR_TWO_SINGLE, v(3, 3, 3, 3, 5, 5), std, "4+2 singles may be a pair (default true)");
        expectNull(v(3, 3, 3, 3, 5, 5), std.withFourTwoSinglesMayBePair(false),
                "4+2 singles may NOT be a pair when disabled");
        expect(ComboType.FOUR_TWO_SINGLE, v(3, 3, 3, 3, 5, 7), std.withFourTwoSinglesMayBePair(false),
                "4+2 with two distinct singles still fine when disabled");

        expectNull(v(3, 3, 3, 3, 16, 17), std, "both jokers as the 4+2 attachments (default forbidden)");
        expect(ComboType.FOUR_TWO_SINGLE, v(3, 3, 3, 3, 16, 17), std.withJokersAsWingPair(true),
                "both jokers as the 4+2 attachments when allowed");
        expectNull(v(3, 3, 3, 4, 4, 4, 16, 17), std, "both jokers as airplane wings (forbidden)");
        expect(ComboType.AIRPLANE_SINGLE, v(3, 3, 3, 4, 4, 4, 16, 17), std.withJokersAsWingPair(true),
                "both jokers as airplane wings when allowed");
        expect(ComboType.TRIPLE_SINGLE, v(3, 3, 3, 17), std, "one joker as a triple wing is fine");
    }

    // ================================================================== 4. option caps

    private static void classifyCaps() {
        section("4. classify: RuleOptions caps are honoured");
        RuleOptions tiny = new RuleOptions(false, true, true, true, false, 5, 3, 2, 2, 2);
        expect(ComboType.STRAIGHT, v(3, 4, 5, 6, 7), tiny, "straight of 5 at maxStraight=5");
        expectNull(v(3, 4, 5, 6, 7, 8), tiny, "straight of 6 rejected at maxStraight=5");
        expect(ComboType.DOUBLE_STRAIGHT, v(3, 3, 4, 4, 5, 5), tiny, "3 pairs at maxPairStraight=3");
        expectNull(v(3, 3, 4, 4, 5, 5, 6, 6), tiny, "4 pairs rejected at maxPairStraight=3");
        expect(ComboType.AIRPLANE, v(3, 3, 3, 4, 4, 4), tiny, "2 triples at maxAirplane=2");
        expectNull(chainTriples(3, 3), tiny, "3 triples rejected at maxAirplane=2");
    }

    // ================================================================== 5. beats

    private static void beatsTable() {
        section("5. beats");

        check(beats(v(5), v(4)), "higher single wins");
        check(!beats(v(4), v(5)), "lower single loses");
        check(!beats(v(5), v(4, 4)), "single cannot beat a pair");
        check(!beats(v(4, 4), v(5)), "pair cannot beat a single");
        check(beats(v(3, 3, 3, 3), v(13, 13)), "bomb beats a pair");
        check(beats(v(4, 4, 4, 4), v(3, 3, 3, 3)), "higher bomb wins");
        check(beats(v(16, 17), v(14, 14, 14, 14)), "rocket beats any bomb");
        check(!beats(v(14, 14, 14, 14), v(16, 17)), "nothing beats the rocket");
        check(!beats(v(16, 17), v(16, 17)), "rocket cannot beat a rocket (BUG FIX #1)");
        check(!beats4(v(16, 16, 17, 17), v(16, 16, 17, 17)), "4p rocket cannot beat a rocket");
        check(beats4(v(16, 16, 17, 17), v(15, 15, 15, 15, 15)), "4p rocket beats a 5-bomb");
        check(!beats4(v(15, 15, 15, 15, 15), v(16, 16, 17, 17)), "5-bomb cannot beat a 4p rocket");
        check(!beats(v(4, 5, 6, 7, 8, 9), v(3, 4, 5, 6, 7)), "straights of different length are not comparable");
        check(beats(v(5, 6, 7, 8, 9), v(3, 4, 5, 6, 7)), "higher straight wins");
        check(beats(v(3, 3, 3, 3), v(13, 13, 13, 4, 4)), "bomb beats triple with a pair");
        check(!beats(v(3, 3, 3, 3, 4, 5), v(4, 4, 4, 4)), "four with two is not a bomb");
        check(beats4(v(3, 3, 3, 3, 3), v(14, 14, 14, 14)), "bigger 4p bomb beats a higher ranked bomb");
        check(beats(v(3, 3, 3, 3), v(3, 3, 3, 3, 5, 7)), "bomb beats four-with-two (direction kept)");
        check(!beats(v(3, 3, 3, 3, 5, 7), v(4, 4, 4, 4)), "four-with-two cannot beat a bomb");
        check(beats(v(4, 4, 4, 4, 5, 7), v(3, 3, 3, 3, 5, 7)), "higher four-with-two wins");
        check(!beats(v(4, 4, 4, 4, 5, 7), v(3, 3, 3, 3, 5, 5, 7, 7)),
                "four-with-two-singles cannot beat four-with-two-pairs");
        check(!beats(v(3, 3, 3, 3, 5, 5, 7, 7), v(4, 4, 4, 4, 5, 7)),
                "four-with-two-pairs cannot beat four-with-two-singles");
        check(beats(v(4, 4, 4, 5, 5, 5, 6, 8), v(3, 3, 3, 4, 4, 4, 5, 7)),
                "airplane-single key comparison");
        check(!beats(v(3, 3, 3, 4, 4, 4, 5, 7), v(3, 3, 3, 4, 4, 4, 5, 7)),
                "equal airplane-single does not beat");
        check(!beats(v(3, 3, 3, 4, 4, 4, 5, 5, 7, 7), v(3, 3, 3, 4, 4, 4, 5, 7)),
                "airplane-pair cannot beat airplane-single");
        check(!RuleEngine.beats(null, null), "null candidate never beats");
        check(RuleEngine.beats(v3(), null), "anything beats a null previous");
    }

    private static Combo v3() {
        return RuleEngine.classify(v(3), RuleOptions.standard());
    }

    // ================================================================== 6. generator invariants

    private static void generatorInvariants() {
        section("6. generator invariants (fuzz, seeded)");
        Random random = new Random(20240517L);
        RuleOptions[] options = {RuleOptions.standard(), RuleOptions.doubleDeck()};
        int hands = 300;
        int leadsChecked = 0;
        int beatsChecked = 0;
        int bad = 0;
        for (int i = 0; i < hands; i++) {
            RuleOptions rule = options[i % options.length];
            int[] hand = randomHand(random, 17);
            List<int[]> leads = RuleEngine.findLeads(hand, rule);
            Set<String> signatures = new HashSet<>();
            for (int[] lead : leads) {
                Combo combo = RuleEngine.classify(lead, rule);
                if (combo == null) {
                    bad++;
                    fail("findLeads returned an illegal combo " + str(lead) + " for hand " + str(hand));
                    break;
                }
                if (!covers(hand, lead)) {
                    bad++;
                    fail("findLeads returned cards not in hand: " + str(lead) + " / " + str(hand));
                    break;
                }
                if (!signatures.add(signature(lead))) {
                    bad++;
                    fail("findLeads returned a duplicate: " + str(lead));
                    break;
                }
                leadsChecked++;
            }
            for (int attempt = 0; attempt < 6; attempt++) {
                Combo previous = randomPreviousCombo(random, rule);
                if (previous == null) {
                    continue;
                }
                List<int[]> beats = RuleEngine.findBeats(hand, previous, rule);
                Set<String> beatSignatures = new HashSet<>();
                for (int[] beat : beats) {
                    Combo combo = RuleEngine.classify(beat, rule);
                    if (combo == null || !RuleEngine.beats(combo, previous)) {
                        bad++;
                        fail("findBeats returned " + str(beat) + " which does not beat " + previous);
                        break;
                    }
                    if (!combo.isNuke() && combo.length() != previous.length()) {
                        bad++;
                        fail("findBeats returned a wrong-length " + str(beat) + " vs " + previous);
                        break;
                    }
                    if (!covers(hand, beat)) {
                        bad++;
                        fail("findBeats returned cards not in hand: " + str(beat) + " / " + str(hand));
                        break;
                    }
                    if (!beatSignatures.add(signature(beat))) {
                        bad++;
                        fail("findBeats returned a duplicate: " + str(beat));
                        break;
                    }
                    beatsChecked++;
                }
                int[] hint = RuleEngine.hint(hand, previous, rule);
                if (hint == null) {
                    if (!beats.isEmpty()) {
                        bad++;
                        fail("hint returned null although " + beats.size() + " beats exist vs " + previous);
                    }
                } else {
                    Combo combo = RuleEngine.classify(hint, rule);
                    if (combo == null || !RuleEngine.beats(combo, previous)) {
                        bad++;
                        fail("hint returned a non-beating combo " + str(hint) + " vs " + previous);
                    } else if (!combo.isNuke()) {
                        int bestKey = Integer.MAX_VALUE;
                        for (int[] beat : beats) {
                            Combo other = RuleEngine.classify(beat, rule);
                            if (other != null && !other.isNuke()) {
                                bestKey = Math.min(bestKey, other.key());
                            }
                        }
                        if (bestKey != Integer.MAX_VALUE && combo.key() > bestKey) {
                            bad++;
                            fail("hint " + str(hint) + " key " + combo.key() + " worse than " + bestKey
                                    + " vs " + previous);
                        }
                    }
                }
            }
        }
        check(bad == 0, "every findLeads/findBeats/hint result is legal (" + leadsChecked + " leads, "
                + beatsChecked + " beats over " + hands + " hands)");
    }

    private static void hintMinimality() {
        section("7. hint = cheapest by rank");
        RuleOptions std = RuleOptions.standard();
        int[] hand = v(3, 3, 5, 5, 9, 9, 9, 14, 15, 16, 17, 7, 7, 7, 7, 11, 12);
        int[] hint = RuleEngine.hint(hand, RuleEngine.classify(v(4), std), std);
        check(Arrays.equals(hint, v(5)), "hint vs single 4 -> single 5, got " + str(hint));
        hint = RuleEngine.hint(hand, RuleEngine.classify(v(4, 4), std), std);
        check(Arrays.equals(hint, v(5, 5)), "hint vs pair 4 -> pair 5, got " + str(hint));
        int[] straightHand = v(4, 5, 6, 7, 8, 9, 15);
        hint = RuleEngine.hint(straightHand, RuleEngine.classify(v(3, 4, 5, 6, 7), std), std);
        check(hint != null && RuleEngine.classify(hint, std).type() == ComboType.STRAIGHT,
                "hint vs straight 3-7 -> cheapest straight, got " + str(hint));
        hint = RuleEngine.hint(hand, RuleEngine.classify(v(16, 17), std), std);
        check(hint == null, "hint vs rocket -> null, got " + str(hint));
        hint = RuleEngine.hint(v(3, 3, 3, 3), RuleEngine.classify(v(15), std), std);
        check(hint != null && RuleEngine.classify(hint, std).type() == ComboType.BOMB,
                "hint vs single 2 with only a bomb left -> the bomb, got " + str(hint));
        hint = RuleEngine.hint(hand, null, std);
        check(hint != null && RuleEngine.classify(hint, std) != null, "hint with no previous -> legal lead");
    }

    // ================================================================== 8. allowBreakBomb
    private static void breakBombFlag() {
        section("8. allowBreakBomb");
        RuleOptions std = RuleOptions.standard();
        int[] hand = v(3, 3, 3, 3);
        List<int[]> strict = RuleEngine.findLeads(hand, std, false);
        List<int[]> loose = RuleEngine.findLeads(hand, std, true);
        check(strict.size() == 1, "only the bomb itself when allowBreakBomb=false, got " + strict.size());
        check(loose.size() > strict.size(), "breaking a bomb adds candidates (" + loose.size() + " > "
                + strict.size() + ")");
        check(loose.get(0).length == 4, "the clean bomb is generated before bomb-splitting candidates");

        int[] mixed = v(5, 5, 5, 5, 9, 9, 9, 9);
        Combo previous = RuleEngine.classify(v(4), std);
        List<int[]> strictBeats = RuleEngine.findBeats(mixed, previous, std, false);
        List<int[]> looseBeats = RuleEngine.findBeats(mixed, previous, std, true);
        check(strictBeats.stream().noneMatch(c -> c.length == 1),
                "allowBreakBomb=false offers no single taken out of a bomb");
        check(looseBeats.stream().anyMatch(c -> c.length == 1),
                "allowBreakBomb=true offers bomb-splitting singles as the last resort");
        boolean ordered = true;
        boolean seenSplit = false;
        for (int[] candidate : looseBeats) {
            boolean split = splitsBomb(mixed, candidate);
            if (!split && seenSplit) {
                ordered = false;
            }
            seenSplit |= split;
        }
        check(ordered, "clean candidates come before bomb-splitting ones");
        check(seenSplit, "bomb-splitting candidates are present when the flag is on");
        check(strictBeats.stream().allMatch(c -> c.length == 4),
                "allowBreakBomb=false still offers the whole bombs (" + strictBeats.size() + ")");

        int[] hand5 = v(3, 3, 3, 3, 3);
        List<int[]> leads5 = RuleEngine.findLeads(hand5, std, true);
        check(leads5.stream().anyMatch(c -> c.length == 5), "4p 5-of-a-kind is still offered as a whole bomb");
        check(leads5.stream().anyMatch(c -> c.length == 1), "4p partial use of a 5-group is offered last");
    }

    // ================================================================== 9. HandShape

    private static void handShapeChecks() {
        section("9. HandShape heuristics");
        check(HandShape.countWeakSingles(v(3, 4, 5)) == 3, "3 weak singles");
        check(HandShape.countWeakSingles(v(3, 3, 4, 15)) == 1, "pair is not a weak single, 2 is not weak");
        check(HandShape.countWeakSingles(v(14)) == 1, "ace counts as a weak single (<2)");
        check(HandShape.hasControl(v(15)), "2 is control");
        check(HandShape.hasControl(v(17)), "big joker is control");
        check(!HandShape.hasControl(v(3, 3, 3, 3)), "a bomb alone is not 'control' by this definition");
        check(!HandShape.hasControl(v()), "empty hand has no control");

        check(HandShape.controlScore(v()) == 0, "empty control score");
        check(HandShape.controlScore(v(16, 17)) == 8, "rocket +8, got " + HandShape.controlScore(v(16, 17)));
        check(HandShape.controlScore(v(17)) == 4, "big joker +4");
        check(HandShape.controlScore(v(16)) == 3, "small joker +3");
        check(HandShape.controlScore(v(15)) == 2, "one 2 -> +2");
        check(HandShape.controlScore(v(15, 15)) == 5, "two 2s -> +5");
        check(HandShape.controlScore(v(15, 15, 15)) == 8, "three 2s -> +8");
        check(HandShape.controlScore(v(15, 15, 15, 15)) == 12 + 6, "four 2s -> +12 and a bomb +6");
        check(HandShape.controlScore(v(3, 3, 3, 3)) == 6, "bomb +6");
        check(HandShape.controlScore(v(3, 4, 5)) == -3, "-1 per weak single");

        check(HandShape.breaksSet(v(3, 3, 4), v(3)), "taking one of a pair breaks a set");
        check(!HandShape.breaksSet(v(3, 3, 4), v(3, 3)), "taking the whole pair keeps the set");
        check(HandShape.breaksSet(v(3, 3, 3, 3), v(3, 3)), "taking two of a bomb breaks it");
        check(!HandShape.breaksSet(v(3, 3, 4), v(4)), "singleton move breaks nothing");
        check(HandShape.breaksSet(v(3, 3, 3), v(3, 3)), "pair out of a triple breaks it");

        check(HandShape.spendsBomb(v(3, 3, 3, 3)), "a bomb spends the bomb");
        check(HandShape.spendsBomb(v(3, 3, 3, 3, 5, 7)), "four-with-two spends the bomb");
        check(HandShape.spendsBomb(v(3, 3, 3, 3, 3)), "5-of-a-kind spends the bomb");
        check(!HandShape.spendsBomb(v(3, 3, 3)), "a triple does not spend a bomb");
        check(!HandShape.spendsBomb(v(16, 17)), "the rocket is not a 4-of-a-kind group");
        check(!HandShape.spendsBomb(v(3, 3, 4, 4, 5, 5)), "pair straight spends nothing");

        check(HandShape.burnsControlAsAccessory(v(3, 3, 3, 15), v(3, 3, 3, 15)), "2 as a triple wing is a burn");
        check(HandShape.burnsControlAsAccessory(v(3, 3, 3, 16), v(3, 3, 3, 16)), "joker as a wing is a burn");
        check(!HandShape.burnsControlAsAccessory(v(15), v(15)), "a single 2 is not an accessory");
        check(!HandShape.burnsControlAsAccessory(v(15, 15, 15, 3), v(15, 15, 15, 3)),
                "2s as the main triple are not accessories");
        check(!HandShape.burnsControlAsAccessory(v(10, 11, 12, 13, 14), v(10, 11, 12, 13, 14)),
                "a straight has no accessory");
        check(!HandShape.burnsControlAsAccessory(v(16, 17), v(16, 17)), "rocket has no accessory");

        check(HandShape.isSevereOvershoot(10, 6), "10 vs 6 is a severe overshoot");
        check(HandShape.isSevereOvershoot(15, 13), "2 vs K is a severe overshoot");
        check(!HandShape.isSevereOvershoot(15, 14), "2 vs A is not severe");
        check(!HandShape.isSevereOvershoot(7, 6), "adjacent ranks are not severe");
    }

    private static void minMovesChecks() {
        section("10. minMoves");
        RuleOptions std = RuleOptions.standard();
        check(HandShape.minMoves(v(), std) == 0, "empty hand -> 0");
        check(HandShape.minMoves(v(3), std) == 1, "single -> 1");
        check(HandShape.minMoves(v(3, 3), std) == 1, "pair -> 1");
        check(HandShape.minMoves(v(3, 3, 3), std) == 1, "triple -> 1");
        check(HandShape.minMoves(v(3, 3, 3, 3), std) == 1, "bomb -> 1");
        check(HandShape.minMoves(v(3, 4, 5, 6, 7), std) == 1, "straight -> 1");
        check(HandShape.minMoves(v(3, 3, 4, 4, 5, 5), std) == 1, "pair straight -> 1");
        check(HandShape.minMoves(v(3, 3, 3, 4, 4, 4), std) == 1, "airplane -> 1");
        check(HandShape.minMoves(v(3, 3, 3, 4), std) == 1, "triple + single -> 1");
        check(HandShape.minMoves(v(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14), std) == 1, "12 straight -> 1");
        check(HandShape.minMoves(v(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), std) == 2,
                "12 straight + a 2 -> 2");
        check(HandShape.minMoves(v(3, 5, 7, 9, 11, 13), std) == 6, "six unrelated singles -> 6");
        check(HandShape.minMoves(v(3, 3, 4, 4), std) == 2, "two pairs -> 2");
        check(HandShape.minMoves(v(3, 3, 3, 4, 4, 4, 5, 5, 5), std) == 1, "three consecutive triples -> 1");
        check(HandShape.minMoves(v(3, 3, 3, 3, 15, 15, 15, 15), std) == 2, "two bombs -> 2");
        check(HandShape.minMoves(v(3, 3, 3, 3, 5, 7), std) == 1, "four with two -> 1");
        int[] big = v(3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
        int moves = HandShape.minMoves(big, std);
        check(moves >= 4 && moves <= 12, "23-card hand gets a sane greedy estimate (" + moves + ")");
        // 20 张五个炸弹：飞机 34567(15 张) + 顺子 34567(5 张) = 2 手
        check(HandShape.minMoves(v(3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7), std) == 2,
                "20-card hand (five bombs) -> 2 via airplane + straight, exact path");
        check(HandShape.minMoves(v(3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8), std) == 2,
                "21-card hand (five bombs + a single) -> 2, greedy path");
        check(HandShape.minMoves(v(3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6), std) == 2,
                "16-card hand (four bombs) -> 2 via two four-with-two-pairs");
    }

    private static void unseenChecks() {
        section("11. unseen safety check");
        RuleOptions std = RuleOptions.standard();
        int[] none = new int[18];
        check(HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(17), std), none, std),
                "the big joker is unbeatable when nothing is unseen");
        check(HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(5), std), none, std),
                "a single 5 is safe when nothing is unseen");
        int[] unseenNine = new int[18];
        unseenNine[9] = 1;
        check(!HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(5), std), unseenNine, std),
                "an unseen 9 beats a 5");
        int[] unseenBomb = new int[18];
        unseenBomb[3] = 4;
        check(!HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(5), std), unseenBomb, std),
                "an unseen bomb beats a 5");
        int[] unseenBiggerBomb = new int[18];
        unseenBiggerBomb[7] = 4;
        check(!HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(3, 3, 3, 3), std), unseenBiggerBomb, std),
                "an unseen bigger bomb beats a bomb of 3s");
        int[] unseenSmallerBomb = new int[18];
        unseenSmallerBomb[5] = 4;
        check(HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(6, 6, 6, 6), std), unseenSmallerBomb, std),
                "a smaller unseen bomb cannot beat a bomb of 6s");
        int[] unseenRocket = new int[18];
        unseenRocket[16] = 1;
        unseenRocket[17] = 1;
        check(!HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(14, 14, 14, 14), std), unseenRocket, std),
                "an unseen rocket beats a bomb");
        check(HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(16, 17), std), unseenRocket, std),
                "the rocket itself is always safe");
        check(!HandShape.isUnbeatableByUnseen(RuleEngine.classify(v(5), std), null, std),
                "unknown counts are never claimed safe");
    }

    // ================================================================== 12. determinism + performance

    private static void determinismAndPerformance() {
        section("12. determinism + performance");
        Random random = new Random(777L);
        RuleOptions std = RuleOptions.standard();
        boolean deterministic = true;
        long worstLeads = 0;
        long worstHint = 0;
        long worstMinMoves = 0;
        for (int i = 0; i < 150 && deterministic; i++) {
            int[] hand = randomHand(random, 17);
            List<int[]> first = RuleEngine.findLeads(hand, std);
            long t0 = System.nanoTime();
            List<int[]> second = RuleEngine.findLeads(hand, std);
            long t1 = System.nanoTime();
            if (!sameList(first, second)) {
                deterministic = false;
                fail("findLeads is not deterministic for " + str(hand));
                break;
            }
            int[] previousValues = previousOrNull(random, std);
            Combo previous = previousValues == null ? null : RuleEngine.classify(previousValues, std);
            int[] hintFirst = RuleEngine.hint(hand, previous, std);
            int[] hintSecond = RuleEngine.hint(hand, previous, std);
            long t2 = System.nanoTime();
            if (!Arrays.equals(hintFirst, hintSecond)) {
                deterministic = false;
                fail("hint is not deterministic for " + str(hand) + " vs " + str(previousValues));
                break;
            }
            int mm1 = HandShape.minMoves(hand, std);
            int mm2 = HandShape.minMoves(hand, std);
            long t3 = System.nanoTime();
            if (mm1 != mm2) {
                deterministic = false;
                fail("minMoves is not deterministic for " + str(hand) + ": " + mm1 + " vs " + mm2);
                break;
            }
            worstLeads = Math.max(worstLeads, t1 - t0);
            worstHint = Math.max(worstHint, t2 - t1);
            worstMinMoves = Math.max(worstMinMoves, t3 - t2);
        }
        check(deterministic, "same input -> same output for findLeads / hint / minMoves");
        System.out.printf("      worst findLeads: %.2f ms   worst hint: %.2f ms   worst minMoves(x2): %.2f ms%n",
                worstLeads / 1_000_000.0, worstHint / 1_000_000.0, worstMinMoves / 1_000_000.0);

        // realistic lead evaluation: findLeads + minMoves for the first 48 leads
        long worstLeadChoice = 0;
        long totalLeadChoice = 0;
        int rounds = 60;
        for (int i = 0; i < rounds; i++) {
            int[] hand = randomHand(random, 17);
            long t0 = System.nanoTime();
            List<int[]> leads = RuleEngine.findLeads(hand, std);
            int evaluated = 0;
            for (int[] lead : leads) {
                if (evaluated++ >= 48) {
                    break;
                }
                HandShape.minMoves(RuleEngine.minus(hand, lead), std);
            }
            long elapsed = System.nanoTime() - t0;
            worstLeadChoice = Math.max(worstLeadChoice, elapsed);
            totalLeadChoice += elapsed;
        }
        System.out.printf("      chooseLead hot path: avg %.2f ms, worst %.2f ms over %d hands%n",
                totalLeadChoice / 1_000_000.0 / rounds, worstLeadChoice / 1_000_000.0, rounds);
        check(worstLeadChoice / 1_000_000.0 < 50.0, "chooseLead hot path stays under 50 ms per turn");

        // 4-player double deck: landlord holds 33 cards, which is the worst case for the lead search
        RuleOptions four = RuleOptions.doubleDeck();
        long worstFour = 0;
        long totalFour = 0;
        int roundsFour = 30;
        for (int i = 0; i < roundsFour; i++) {
            int[] hand = randomHand(random, 33);
            long t0 = System.nanoTime();
            List<int[]> leads = RuleEngine.findLeads(hand, four);
            int evaluated = 0;
            for (int[] lead : leads) {
                if (evaluated++ >= 48) {
                    break;
                }
                HandShape.minMoves(RuleEngine.minus(hand, lead), four);
            }
            long elapsed = System.nanoTime() - t0;
            worstFour = Math.max(worstFour, elapsed);
            totalFour += elapsed;
        }
        System.out.printf("      4p 33-card chooseLead hot path: avg %.2f ms, worst %.2f ms over %d hands%n",
                totalFour / 1_000_000.0 / roundsFour, worstFour / 1_000_000.0, roundsFour);
        check(worstFour / 1_000_000.0 < 50.0, "4p chooseLead hot path stays under 50 ms per turn");
    }

    // ================================================================== helpers

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL  " + label);
        } else {
            System.out.println("  ok    " + label);
        }
    }

    private static void fail(String label) {
        checks++;
        failures++;
        System.out.println("  FAIL  " + label);
    }

    private static void expect(ComboType type, int[] values, RuleOptions options, String label) {
        Combo combo = RuleEngine.classify(values, options);
        check(combo != null && combo.type() == type, label + " -> " + type
                + (combo == null ? " (got null)" : " (got " + combo.type() + ")"));
    }

    private static void expectNull(int[] values, RuleOptions options, String label) {
        Combo combo = RuleEngine.classify(values, options);
        check(combo == null, label + " -> invalid" + (combo == null ? "" : " (got " + combo + ")"));
    }

    private static boolean beats(int[] candidate, int[] previous) {
        return beats(candidate, previous, RuleOptions.standard());
    }

    private static boolean beats4(int[] candidate, int[] previous) {
        return beats(candidate, previous, RuleOptions.doubleDeck());
    }

    private static boolean beats(int[] candidate, int[] previous, RuleOptions options) {
        Combo a = RuleEngine.classify(candidate, options);
        Combo b = previous == null ? null : RuleEngine.classify(previous, options);
        if (a == null) {
            throw new IllegalStateException("harness bug: candidate illegal " + str(candidate));
        }
        if (previous != null && b == null) {
            throw new IllegalStateException("harness bug: previous illegal " + str(previous));
        }
        return RuleEngine.beats(a, b);
    }

    private static int[] v(int... values) {
        return values;
    }

    private static int[] chainTriples(int start, int count) {
        int[] values = new int[count * 3];
        int index = 0;
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < 3; c++) {
                values[index++] = start + i;
            }
        }
        return values;
    }

    /** airplane + singles: wings taken from A downwards so they never overlap the body. */
    private static int[] airplaneSingles(int start, int count) {
        int[] head = chainTriples(start, count);
        int[] result = Arrays.copyOf(head, head.length + count);
        for (int i = 0; i < count; i++) {
            result[head.length + i] = 14 - i;
        }
        return result;
    }

    /** airplane + pairs: winged pairs taken from A downwards. */
    private static int[] airplanePairs(int start, int count) {
        int[] head = chainTriples(start, count);
        int[] result = Arrays.copyOf(head, head.length + count * 2);
        for (int i = 0; i < count; i++) {
            result[head.length + i * 2] = 14 - i;
            result[head.length + i * 2 + 1] = 14 - i;
        }
        return result;
    }

    private static int[] randomHand(Random random, int size) {
        int[] hand = new int[size];
        for (int i = 0; i < size; i++) {
            hand[i] = 3 + random.nextInt(15);
        }
        Arrays.sort(hand);
        return hand;
    }

    private static Combo randomPreviousCombo(Random random, RuleOptions options) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int size = 1 + random.nextInt(8);
            Combo combo = RuleEngine.classify(randomHand(random, size), options);
            if (combo != null) {
                return combo;
            }
        }
        return null;
    }

    private static int[] previousOrNull(Random random, RuleOptions options) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int[] values = randomHand(random, 1 + random.nextInt(8));
            if (RuleEngine.classify(values, options) != null) {
                return values;
            }
        }
        return null;
    }

    private static boolean covers(int[] hand, int[] move) {
        int[] counts = RuleEngine.counts(hand);
        for (int value : move) {
            if (value < RuleEngine.MIN_VALUE || value > RuleEngine.MAX_VALUE || counts[value]-- <= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean splitsBomb(int[] hand, int[] move) {
        int[] handCounts = RuleEngine.counts(hand);
        int[] used = RuleEngine.counts(move);
        for (int value = RuleEngine.MIN_VALUE; value <= RuleEngine.MAX_VALUE; value++) {
            if (used[value] > 0 && handCounts[value] >= 4 && used[value] < handCounts[value]) {
                return true;
            }
        }
        return false;
    }

    private static String signature(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return Arrays.toString(sorted);
    }

    private static boolean sameList(List<int[]> first, List<int[]> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (!signature(first.get(i)).equals(signature(second.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static String str(int[] values) {
        if (values == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.append(']').toString();
    }
}
