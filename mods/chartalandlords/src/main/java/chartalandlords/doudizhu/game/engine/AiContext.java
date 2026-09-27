package chartalandlords.doudizhu.game.engine;

/**
 * AI 决策上下文（纯 int/boolean，不依赖 Minecraft / Charta）。
 *
 * @param previous           上一手牌型；null 表示自己首出
 * @param landlord           自己是否是地主
 * @param teammateLed        上一手是否由农民队友打出（自己也是农民且队友不是地主）
 * @param teammateHandSize   队友剩余张数，-1 未知
 * @param landlordHandSize   地主剩余张数，-1 未知
 * @param minOpponentHandSize 所有「对手」里最少的剩余张数，-1 未知。
 *                            对手的定义：自己是地主 → 所有农民；自己是农民 → 只有地主（队友不算）
 * @param unseenCounts       长度 18，下标 = 点数，值为「还没露面」的张数；null = 未统计
 * @param seats              本局人数（搜索策略采样要用；-1 = 未知）
 * @param seat               自己坐哪（-1 = 未知）
 * @param landlordSeat       地主座位下标（-1 = 未定）
 */
public record AiContext(
        Combo previous,
        boolean landlord,
        boolean teammateLed,
        int teammateHandSize,
        int landlordHandSize,
        int minOpponentHandSize,
        int[] unseenCounts,
        int seats,
        int seat,
        int landlordSeat
) {

    /** 首出上下文：没有上一手，也就没有「队友刚出过牌」。 */
    public static AiContext lead(boolean landlord, int minOpponentHandSize, int[] unseenCounts) {
        return new AiContext(null, landlord, false, -1,
                landlord ? -1 : minOpponentHandSize, minOpponentHandSize, unseenCounts, -1, -1, -1);
    }

    /** 自己是地主时的跟牌上下文。 */
    public static AiContext following(Combo previous, boolean landlord, boolean teammateLed,
                                      int teammateHandSize, int landlordHandSize,
                                      int minOpponentHandSize, int[] unseenCounts) {
        return new AiContext(previous, landlord, teammateLed, teammateHandSize, landlordHandSize,
                minOpponentHandSize, unseenCounts, -1, -1, -1);
    }

    /** 兼容旧调用：只给七个公开信息字段（seats / seat / landlordSeat 用 -1 占位）。 */
    public static AiContext of(Combo previous, boolean landlord, boolean teammateLed, int teammateHandSize,
                               int landlordHandSize, int minOpponentHandSize, int[] unseenCounts) {
        return new AiContext(previous, landlord, teammateLed, teammateHandSize, landlordHandSize,
                minOpponentHandSize, unseenCounts, -1, -1, -1);
    }

    /** 带完整座位信息的新入口（游戏层用；三个座位字段给搜索策略采样）。 */
    public static AiContext lead(boolean landlord, int minOpponentHandSize, int[] unseenCounts,
                               int seats, int seat, int landlordSeat) {
        return new AiContext(null, landlord, false, -1,
                landlord ? -1 : minOpponentHandSize, minOpponentHandSize, unseenCounts,
                seats, seat, landlordSeat);
    }

    /** 带完整座位信息的新入口（跟牌版）。 */
    public static AiContext following(Combo previous, boolean landlord, boolean teammateLed,
                                      int teammateHandSize, int landlordHandSize, int minOpponentHandSize,
                                      int[] unseenCounts, int seats, int seat, int landlordSeat) {
        return new AiContext(previous, landlord, teammateLed, teammateHandSize, landlordHandSize,
                minOpponentHandSize, unseenCounts, seats, seat, landlordSeat);
    }
}
