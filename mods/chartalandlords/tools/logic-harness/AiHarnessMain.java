package chartalandlords.doudizhu.game.engine;

import java.util.Arrays;
import java.util.Random;

/**
 * WORKSTREAM A - disposable AI-policy harness.
 *
 * <p>Unlike {@link HarnessMain} this one needs the Charta/Minecraft jars on the classpath to compile
 * {@link DoudizhuAi} (the Card adapter layer). It only ever calls the pure policy entry points
 * ({@code chooseLeadValues}/{@code choosePlayValues}) plus the pure engine, so the JVM never has to
 * touch {@code Card}, {@code ModRanks} or anything that needs a running game.</p>
 */
public final class AiHarnessMain {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        aiPolicy();
        System.out.println();
        System.out.println("---------------------------------------------");
        System.out.printf("AI HARNESS %s: %d checks, %d failures%n",
                failures == 0 ? "PASS" : "FAIL", checks, failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void aiPolicy() {
        System.out.println("== DoudizhuAi policy (pure entry points)");
        RuleOptions std = RuleOptions.standard();

        // finish in one move
        int[] chosen = AiPolicy.choosePlayValues(v(4, 5, 6, 7, 8),
                AiContext.following(RuleEngine.classify(v(3, 4, 5, 6, 7), std), true, false, -1, 30, 40, null), std);
        check(chosen != null && RuleEngine.classify(chosen, std).type() == ComboType.STRAIGHT,
                "finishing move is played when it empties the hand, got " + str(chosen));
        chosen = AiPolicy.choosePlayValues(v(4, 4),
                AiContext.following(RuleEngine.classify(v(3, 3), std), true, false, -1, 30, 40, null), std);
        check(Arrays.equals(chosen, v(4, 4)), "pair finisher is played, got " + str(chosen));
        // a winning bomb may overrule the 'never bomb a teammate' rule (the play ends the game)
        chosen = AiPolicy.choosePlayValues(v(3, 3, 3, 3),
                AiContext.following(RuleEngine.classify(v(10), std), false, true, 10, 1, 1, null), std);
        check(chosen != null && RuleEngine.classify(chosen, std).isNuke(),
                "a bomb that empties the hand still wins the game, got " + str(chosen));

        // cheapest safe beater
        chosen = AiPolicy.choosePlayValues(v(6, 6, 9),
                AiContext.following(RuleEngine.classify(v(5), std), true, false, -1, 30, 40, null), std);
        check(Arrays.equals(chosen, v(9)), "cheapest non-breaking beater wins, got " + str(chosen));
        chosen = AiPolicy.choosePlayValues(v(6, 7, 9),
                AiContext.following(RuleEngine.classify(v(5), std), true, false, -1, 30, 40, null), std);
        check(Arrays.equals(chosen, v(6)), "cheapest beater wins when nothing is broken, got " + str(chosen));

        // teammate protection
        chosen = AiPolicy.choosePlayValues(v(3, 11, 12),
                AiContext.following(RuleEngine.classify(v(15), std), false, true, 10, 10, 10, null), std);
        check(chosen == null, "farmer passes on a teammate's strong lead, got " + str(chosen));
        chosen = AiPolicy.choosePlayValues(v(3, 11, 12),
                AiContext.following(RuleEngine.classify(v(5), std), false, true, 10, 10, 10, null), std);
        check(chosen != null && RuleEngine.beats(RuleEngine.classify(chosen, std), RuleEngine.classify(v(5), std)),
                "farmer still takes over a teammate's weak lead, got " + str(chosen));

        // landlord on 1 card: farmer must block a single
        chosen = AiPolicy.choosePlayValues(v(3, 11, 12),
                AiContext.following(RuleEngine.classify(v(9), std), false, true, 10, 1, 1, null), std);
        check(Arrays.equals(chosen, v(11)),
                "farmer must block a single when the landlord has 1 card, got " + str(chosen));

        // never bomb a teammate (when the bomb is not the winning play)
        chosen = AiPolicy.choosePlayValues(v(3, 3, 3, 3, 5),
                AiContext.following(RuleEngine.classify(v(10), std), false, true, 10, 1, 1, null), std);
        check(chosen == null, "never bomb a teammate, got " + str(chosen));

        // opponent about to finish: bomb
        chosen = AiPolicy.choosePlayValues(v(3, 3, 3, 3, 5),
                AiContext.following(RuleEngine.classify(v(10), std), true, false, -1, 30, 1, null), std);
        check(chosen != null && RuleEngine.classify(chosen, std).isNuke(),
                "bomb when an opponent is about to finish, got " + str(chosen));

        // no threat: keep the bomb
        chosen = AiPolicy.choosePlayValues(v(3, 3, 3, 3, 5),
                AiContext.following(RuleEngine.classify(v(10), std), true, false, -1, 30, 30, null), std);
        check(chosen == null, "keep the bomb when nobody is close to finishing, got " + str(chosen));

        // previous is a bomb: answer with a bigger bomb
        chosen = AiPolicy.choosePlayValues(v(4, 4, 4, 4, 5),
                AiContext.following(RuleEngine.classify(v(3, 3, 3, 3), std), true, false, -1, 30, 30, null), std);
        check(chosen != null && RuleEngine.classify(chosen, std).isNuke(),
                "bigger bomb answers a bomb, got " + str(chosen));

        // leading: keep the bomb and the control cards
        int[] lead = AiPolicy.chooseLeadValues(v(3, 3, 3, 3, 5, 7, 9, 11, 12, 15, 16, 17), std, null);
        check(lead != null && RuleEngine.classify(lead, std).type() == ComboType.SINGLE && lead[0] < 15,
                "lead keeps the bomb and the control cards, got " + str(lead));

        // leading: prefer the long combination
        lead = AiPolicy.chooseLeadValues(v(3, 4, 5, 6, 7, 9, 12, 13), std, null);
        check(lead != null && RuleEngine.classify(lead, std).type() == ComboType.STRAIGHT,
                "lead prefers a straight over loose singles, got " + str(lead));

        // previous == null delegates to the lead logic
        lead = AiPolicy.chooseLeadValues(v(3, 5, 9), std, null);
        int[] viaContext = AiPolicy.choosePlayValues(v(3, 5, 9), AiContext.lead(false, 10, null), std);
        check(Arrays.equals(lead, viaContext), "choosePlay with no previous delegates to the lead logic");

        // unseen-count safe lead bonus must not change legality
        int[] unseen = new int[18];
        lead = AiPolicy.chooseLeadValues(v(3, 3, 4, 4, 5, 5, 7, 9, 11, 13, 15, 16, 17), std, unseen);
        check(lead != null && RuleEngine.classify(lead, std) != null, "lead stays legal with unseen counts");

        // AiContext.lead factory
        AiContext context = AiContext.lead(false, 7, null);
        check(context.previous() == null && !context.landlord() && !context.teammateLed()
                        && context.teammateHandSize() == -1 && context.landlordHandSize() == 7
                        && context.minOpponentHandSize() == 7,
                "AiContext.lead fills the farmer fields consistently");
        context = AiContext.lead(true, 7, null);
        check(context.landlordHandSize() == -1, "AiContext.lead leaves landlordHandSize unknown for the landlord");

        // fuzz: every answer is legal, beats the previous hand, is in hand, and is deterministic
        Random random = new Random(4242L);
        int plays = 0;
        int bad = 0;
        for (int i = 0; i < 400; i++) {
            int[] hand = randomHand(random, 17);
            int[] previousValues = previousOrNull(random, std);
            Combo previous = previousValues == null ? null : RuleEngine.classify(previousValues, std);
            AiContext ctx = new AiContext(previous, random.nextBoolean(), random.nextBoolean(),
                    random.nextInt(4) - 1, random.nextInt(4) - 1, random.nextInt(4) - 1, null);
            int[] play = AiPolicy.choosePlayValues(hand, ctx, std);
            if (play == null) {
                continue;
            }
            plays++;
            Combo combo = RuleEngine.classify(play, std);
            if (combo == null || !RuleEngine.beats(combo, previous) || !covers(hand, play)) {
                bad++;
                fail("AI played " + str(play) + " vs " + previous + " from " + str(hand));
                break;
            }
            if (!Arrays.equals(play, AiPolicy.choosePlayValues(hand, ctx, std))) {
                bad++;
                fail("AI is not deterministic for " + str(hand) + " vs " + previous);
                break;
            }
        }
        check(bad == 0, "AI always answers with a legal, beating, in-hand combo (" + plays
                + " plays / 400 hands)");

        // fuzz: leading is always legal and inside the hand
        bad = 0;
        for (int i = 0; i < 200; i++) {
            int[] hand = randomHand(random, 17);
            int[] play = AiPolicy.chooseLeadValues(hand, std, null);
            if (play == null || RuleEngine.classify(play, std) == null || !covers(hand, play)) {
                bad++;
                fail("AI led " + str(play) + " from " + str(hand));
                break;
            }
        }
        check(bad == 0, "AI always leads a legal in-hand combination (200 hands)");
    }

    // ------------------------------------------------------------------ helpers

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

    private static int[] v(int... values) {
        return values;
    }

    private static int[] randomHand(Random random, int size) {
        int[] hand = new int[size];
        for (int i = 0; i < size; i++) {
            hand[i] = 3 + random.nextInt(15);
        }
        Arrays.sort(hand);
        return hand;
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

    private static String str(int[] values) {
        return values == null ? "null" : Arrays.toString(values);
    }
}
