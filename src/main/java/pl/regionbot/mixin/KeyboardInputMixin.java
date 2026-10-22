package pl.regionbot.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.regionbot.Bot;

/**
 * Gdy bot działa, nadpisuje odczyt klawiatury – gra widzi wciśnięte W / spację / A / D
 * dokładnie tak, jakby trzymał je gracz (skok, rozkładanie elytry, chodzenie).
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void regionbot$applyBotInput(CallbackInfo ci) {
        Bot bot = Bot.INSTANCE;
        if (!bot.isRunning()) return;

        this.playerInput = new PlayerInput(
                bot.inForward, bot.inBack, bot.inLeft, bot.inRight,
                bot.inJump, false, bot.inSprint);
        this.movementForward = bot.inForward == bot.inBack ? 0.0f : (bot.inForward ? 1.0f : -1.0f);
        this.movementSideways = bot.inLeft == bot.inRight ? 0.0f : (bot.inLeft ? 1.0f : -1.0f);
    }
}
