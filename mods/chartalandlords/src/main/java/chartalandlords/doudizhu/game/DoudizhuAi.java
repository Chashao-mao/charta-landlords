package chartalandlords.doudizhu.game;

import chartalandlords.doudizhu.game.engine.AiContext;
import chartalandlords.doudizhu.game.engine.AiPolicy;
import chartalandlords.doudizhu.game.engine.AiProfile;
import chartalandlords.doudizhu.game.engine.RuleEngine;
import chartalandlords.doudizhu.game.engine.RuleOptions;
import chartalandlords.doudizhu.game.engine.Combo;
import dev.lucaargolo.charta.common.game.api.card.Card;
import java.util.List;

/**
 * 斗地主 AI 的 Card 适配层：把牌翻译成点数交给纯策略 {@link AiPolicy}，再把结果翻译回牌。
 *
 * <p>所有决策逻辑都在 {@link AiPolicy}（零 Minecraft 依赖，可用
 * {@code tools/logic-check.ps1} 离线穷举校验）；这里只负责
 * {@link DoudizhuValues#sortedValues(List)} 与 {@link DoudizhuRules#cardsFor(List, int[])} 两个方向的映射。</p>
 */
public final class DoudizhuAi {

    private DoudizhuAi() {}

    /** 手牌强度（控场分，越大越值得叫地主）。 */
    public static int strength(List<Card> hand) {
        if (hand == null || hand.isEmpty()) {
            return 0;
        }
        return AiPolicy.strength(DoudizhuValues.sortedValues(hand));
    }

    /** @return 0 表示不叫，否则 1..3；必须高于当前最高分 */
    public static int chooseBid(List<Card> hand, int currentHighest) {
        return chooseBid(hand, currentHighest, null);
    }

    /** 带难度档位的叫分；{@code profile} 为 null 时用均衡档。 */
    public static int chooseBid(List<Card> hand, int currentHighest, AiProfile profile) {
        if (hand == null || hand.isEmpty()) {
            return 0;
        }
        return AiPolicy.chooseBid(DoudizhuValues.sortedValues(hand), currentHighest, profile);
    }

    /**
     * 抢地主模式下的「抢 / 不抢」。
     *
     * @param grabsSoFar   已经有多少人抢过（每次抢都让倍数翻一倍）
     * @param lastToDecide 自己是不是最后一个还没决定的人
     */
    public static boolean chooseGrab(List<Card> hand, int grabsSoFar, boolean lastToDecide, AiProfile profile) {
        if (hand == null || hand.isEmpty()) {
            return false;
        }
        return AiPolicy.chooseGrab(DoudizhuValues.sortedValues(hand), grabsSoFar, lastToDecide, profile);
    }

    /** 手牌相对强弱（控场分 − 同张数的基线）；明牌 / 加倍的门槛用它。 */
    public static int strengthMargin(List<Card> hand) {
        if (hand == null || hand.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        return AiPolicy.strengthMargin(DoudizhuValues.sortedValues(hand));
    }

    /** 首出：优先能一手走完，其次让「剩余手数」最少。 */
    public static List<Card> chooseLead(List<Card> hand, RuleOptions options) {
        return chooseLead(hand, options, null);
    }

    /** 兼容旧签名：按人数取默认规则。 */
    public static List<Card> chooseLead(List<Card> hand, boolean fourPlayer) {
        return chooseLead(hand, DoudizhuRules.optionsFor(fourPlayer), null);
    }

    /**
     * 首出（带明牌信息版本）。
     *
     * @param unseenCounts 未露面张数（下标 = 点数）；可用于奖励「外面已经压不动」的安全首出，null = 不统计
     */
    public static List<Card> chooseLead(List<Card> hand, RuleOptions options, int[] unseenCounts) {
        return chooseLead(hand, options, unseenCounts, null);
    }

    /** 首出（带明牌信息 + 难度档位）。 */
    public static List<Card> chooseLead(List<Card> hand, RuleOptions options, int[] unseenCounts, AiProfile profile) {
        if (hand == null || hand.isEmpty()) {
            return null;
        }
        int[] chosen = AiPolicy.chooseLeadValues(DoudizhuValues.sortedValues(hand), options, unseenCounts, profile);
        return chosen == null ? null : DoudizhuRules.cardsFor(hand, chosen);
    }

    /**
     * 跟牌 / 首出统一入口。
     *
     * @return 要出的牌；null 表示不出
     */
    public static List<Card> choosePlay(List<Card> hand, AiContext context, RuleOptions options) {
        return choosePlay(hand, context, options, null);
    }

    /** 跟牌 / 首出统一入口（带难度档位）。 */
    public static List<Card> choosePlay(List<Card> hand, AiContext context, RuleOptions options, AiProfile profile) {
        if (hand == null || hand.isEmpty()) {
            return null;
        }
        int[] chosen = AiPolicy.choosePlayValues(DoudizhuValues.sortedValues(hand), context, options, profile);
        return chosen == null ? null : DoudizhuRules.cardsFor(hand, chosen);
    }

    /** 提示：最小可压的一手（首出时给出 AI 认为最好的首出）。null = 没有。 */
    public static List<Card> hint(List<Card> hand, Combo previous, RuleOptions options) {
        if (hand == null || hand.isEmpty()) {
            return null;
        }
        RuleOptions rule = options == null ? RuleOptions.standard() : options;
        if (previous == null) {
            return chooseLead(hand, rule);
        }
        int[] choice = RuleEngine.hint(DoudizhuValues.sortedValues(hand), previous, rule);
        return choice == null ? null : DoudizhuRules.cardsFor(hand, choice);
    }
}
