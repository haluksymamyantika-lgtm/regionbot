package pl.regionbot;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Menu pod L: Start, Stop, przypisywanie klawiszy i zarządzanie obszarami. */
public class BotScreen extends Screen {
    private KeyBinding listening;
    private ButtonWidget startKeyBtn;
    private ButtonWidget stopKeyBtn;

    public BotScreen() {
        super(Text.literal("RegionBot"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = height / 2 - 62;

        addDrawableChild(ButtonWidget.builder(Text.literal("§a§lStart"), b -> {
            Bot.INSTANCE.start();
            close();
        }).dimensions(cx - 102, y, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("§c§lStop"), b -> Bot.INSTANCE.stop())
                .dimensions(cx + 2, y, 100, 20).build());

        startKeyBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> listen(RegionBotClient.START))
                .dimensions(cx - 102, y + 30, 204, 20).build());
        stopKeyBtn = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> listen(RegionBotClient.STOP))
                .dimensions(cx - 102, y + 54, 204, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Usuń ostatni obszar"), b -> Regions.removeLast())
                .dimensions(cx - 102, y + 84, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Usuń wszystkie"), b -> {
            Bot.INSTANCE.stop();
            Regions.clear();
        }).dimensions(cx + 2, y + 84, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Zamknij"), b -> close())
                .dimensions(cx - 50, y + 114, 100, 20).build());

        refreshLabels();
    }

    private void listen(KeyBinding key) {
        listening = key;
        refreshLabels();
    }

    private void refreshLabels() {
        startKeyBtn.setMessage(label("Klawisz Start", RegionBotClient.START));
        stopKeyBtn.setMessage(label("Klawisz Stop", RegionBotClient.STOP));
    }

    private Text label(String name, KeyBinding key) {
        if (listening == key) return Text.literal("§e> Naciśnij klawisz (Esc = brak) <");
        return Text.literal(name + ": §b" + key.getBoundKeyLocalizedText().getString());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) listening.setBoundKey(InputUtil.UNKNOWN_KEY);
            else listening.setBoundKey(InputUtil.fromKeyCode(keyCode, scanCode));
            KeyBinding.updateKeysByCode();
            if (client != null) client.options.write();
            listening = null;
            refreshLabels();
            return true;
        }
        if (RegionBotClient.MENU.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        int y = height / 2 - 62;
        ctx.fill(cx - 110, y - 44, cx + 110, y - 6, 0xAA200000);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§c§lRegionBot"), cx, y - 40, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal((Bot.INSTANCE.isRunning() ? "§a● " : "§7● ") + Bot.INSTANCE.getStatus()), cx, y - 28, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Obszary: §f" + Regions.LIST.size() + (Regions.pending != null ? " §e(zaznaczasz…)" : "")),
                cx, y - 17, 0xFFFFFFFF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
