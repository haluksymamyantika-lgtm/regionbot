package pl.regionbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class RegionBotClient implements ClientModInitializer {
    public static final String CATEGORY = "category.regionbot";

    public static KeyBinding SELECT;
    public static KeyBinding MENU;
    public static KeyBinding START;
    public static KeyBinding STOP;

    @Override
    public void onInitializeClient() {
        SELECT = register("key.regionbot.select", GLFW.GLFW_KEY_J);
        MENU = register("key.regionbot.menu", GLFW.GLFW_KEY_L);
        START = register("key.regionbot.start", GLFW.GLFW_KEY_O);
        STOP = register("key.regionbot.stop", GLFW.GLFW_KEY_P);

        Regions.load();

        ClientTickEvents.END_CLIENT_TICK.register(RegionBotClient::onTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RegionRenderer::render);
    }

    private static KeyBinding register(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(id, InputUtil.Type.KEYSYM, key, CATEGORY));
    }

    private static void onTick(MinecraftClient mc) {
        while (SELECT.wasPressed()) Regions.handleSelectKey(mc);
        while (MENU.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new BotScreen());
        }
        while (START.wasPressed()) Bot.INSTANCE.start();
        while (STOP.wasPressed()) Bot.INSTANCE.stop();

        Bot.INSTANCE.tick(mc);
    }
}
