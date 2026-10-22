package pl.regionbot.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Pozwala wyzerować 5-tickowe opóźnienie między blokami (kopanie jak Baritone). */
@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerAccessor {
    @Accessor("blockBreakingCooldown")
    void regionbot$setBlockBreakingCooldown(int value);
}
