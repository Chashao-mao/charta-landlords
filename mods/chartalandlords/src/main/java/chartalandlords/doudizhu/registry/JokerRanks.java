package chartalandlords.doudizhu.registry;

import chartalandlords.doudizhu.Doudizhu;
import dev.lucaargolo.charta.common.game.api.card.Rank;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Two extra ranks appended to Charta's synced {@code charta:rank} registry so that the small and
 * the big joker are distinct cards (Charta's own {@code charta:joker} rank cannot tell them apart).
 */
public final class JokerRanks {

    private JokerRanks() {}

    public static final ResourceKey<Registry<Rank>> CHARTA_RANK_KEY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("charta", "rank"));

    public static final DeferredRegister<Rank> REGISTRY = DeferredRegister.create(CHARTA_RANK_KEY, Doudizhu.MOD_ID);

    /** 小王，点数 16（大于 2）。 */
    public static final DeferredHolder<Rank, Rank> SMALL_JOKER = REGISTRY.register("small_joker", () -> new Rank(16));

    /** 大王，点数 17。 */
    public static final DeferredHolder<Rank, Rank> BIG_JOKER = REGISTRY.register("big_joker", () -> new Rank(17));
}
