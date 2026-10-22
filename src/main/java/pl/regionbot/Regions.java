package pl.regionbot;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Zaznaczone obszary (zapisywane w config/regionbot.json). */
public final class Regions {
    public record Region(BlockPos min, BlockPos max) {
        public static Region of(BlockPos a, BlockPos b) {
            return new Region(
                    new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                    new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
        }

        public Box box() {
            return new Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
        }

        public Vec3d topCenter() {
            return new Vec3d((min.getX() + max.getX() + 1) / 2.0, max.getY() + 1, (min.getZ() + max.getZ() + 1) / 2.0);
        }

        public boolean containsXZ(double x, double z) {
            return x >= min.getX() - 1 && x <= max.getX() + 2 && z >= min.getZ() - 1 && z <= max.getZ() + 2;
        }
    }

    public static final List<Region> LIST = new ArrayList<>();
    public static BlockPos pending;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("regionbot.json");
    private static final Gson GSON = new Gson();

    private Regions() {}

    public static void handleSelectKey(MinecraftClient mc) {
        if (mc.player == null) return;
        BlockPos pos;
        HitResult hit = mc.player.raycast(96.0, 1.0f, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) pos = bhr.getBlockPos();
        else pos = mc.player.getBlockPos();

        if (pending == null) {
            pending = pos;
            msg(mc, "§cPunkt 1: §f" + fmt(pos) + " §7(naciśnij J jeszcze raz na drugim rogu)");
        } else {
            Region r = Region.of(pending, pos);
            LIST.add(r);
            pending = null;
            save();
            msg(mc, "§aDodano obszar #" + LIST.size() + " §7" + fmt(r.min()) + " → " + fmt(r.max()));
        }
    }

    public static void removeLast() {
        if (pending != null) { pending = null; return; }
        if (!LIST.isEmpty()) { LIST.remove(LIST.size() - 1); save(); }
    }

    public static void clear() {
        pending = null;
        LIST.clear();
        save();
    }

    public static void load() {
        try {
            if (!Files.exists(FILE)) return;
            int[][] data = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), int[][].class);
            LIST.clear();
            if (data != null) for (int[] d : data) {
                if (d != null && d.length == 6)
                    LIST.add(Region.of(new BlockPos(d[0], d[1], d[2]), new BlockPos(d[3], d[4], d[5])));
            }
        } catch (Exception e) {
            System.err.println("[RegionBot] Nie udało się wczytać obszarów: " + e);
        }
    }

    public static void save() {
        try {
            int[][] data = new int[LIST.size()][];
            for (int i = 0; i < LIST.size(); i++) {
                Region r = LIST.get(i);
                data[i] = new int[]{r.min().getX(), r.min().getY(), r.min().getZ(), r.max().getX(), r.max().getY(), r.max().getZ()};
            }
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[RegionBot] Nie udało się zapisać obszarów: " + e);
        }
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("§4[RegionBot] §r" + s), false);
    }
}
