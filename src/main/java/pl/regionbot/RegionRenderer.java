package pl.regionbot;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** Rysuje zaznaczone obszary na czerwono – jak w Litematice. */
public final class RegionRenderer {
    private RegionRenderer() {}

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        if (Regions.LIST.isEmpty() && Regions.pending == null) return;
        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;

        Vec3d cam = ctx.camera().getPos();
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        Regions.Region current = Bot.INSTANCE.currentRegion();

        ms.push();
        MatrixStack.Entry entry = ms.peek();
        Matrix4f m = entry.getPositionMatrix();

        // Półprzezroczyste czerwone ściany
        VertexConsumer quads = imm.getBuffer(RenderLayer.getDebugQuads());
        for (Regions.Region r : Regions.LIST) {
            Box b = r.box().expand(0.003).offset(-cam.x, -cam.y, -cam.z);
            float a = r.equals(current) ? 0.30f : 0.16f;
            faces(quads, m, b, 1.0f, 0.08f, 0.08f, a);
        }
        imm.draw(RenderLayer.getDebugQuads());

        // Czerwone krawędzie
        VertexConsumer lines = imm.getBuffer(RenderLayer.getLines());
        for (Regions.Region r : Regions.LIST) {
            Box b = r.box().expand(0.003).offset(-cam.x, -cam.y, -cam.z);
            edges(lines, entry, b, 1.0f, 0.1f, 0.1f, 1.0f);
        }
        if (Regions.pending != null) {
            Box b = new Box(Regions.pending).expand(0.01).offset(-cam.x, -cam.y, -cam.z);
            edges(lines, entry, b, 1.0f, 0.55f, 0.55f, 1.0f);
        }
        imm.draw(RenderLayer.getLines());
        ms.pop();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float[] p, float r, float g, float b, float a) {
        // obie strony, żeby było widać od środka i od zewnątrz
        for (int i = 0; i < 4; i++) vc.vertex(m, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]).color(r, g, b, a);
        for (int i = 3; i >= 0; i--) vc.vertex(m, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]).color(r, g, b, a);
    }

    private static void faces(VertexConsumer vc, Matrix4f m, Box bx, float r, float g, float b, float a) {
        float x1 = (float) bx.minX, y1 = (float) bx.minY, z1 = (float) bx.minZ;
        float x2 = (float) bx.maxX, y2 = (float) bx.maxY, z2 = (float) bx.maxZ;
        quad(vc, m, new float[]{x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2}, r, g, b, a); // dół
        quad(vc, m, new float[]{x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1}, r, g, b, a); // góra
        quad(vc, m, new float[]{x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1}, r, g, b, a); // północ
        quad(vc, m, new float[]{x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2}, r, g, b, a); // południe
        quad(vc, m, new float[]{x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1}, r, g, b, a); // zachód
        quad(vc, m, new float[]{x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2}, r, g, b, a); // wschód
    }

    private static void edges(VertexConsumer vc, MatrixStack.Entry e, Box bx, float r, float g, float b, float a) {
        float x1 = (float) bx.minX, y1 = (float) bx.minY, z1 = (float) bx.minZ;
        float x2 = (float) bx.maxX, y2 = (float) bx.maxY, z2 = (float) bx.maxZ;
        line(vc, e, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(vc, e, x1, y1, z2, x2, y1, z2, r, g, b, a);
        line(vc, e, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(vc, e, x1, y2, z2, x2, y2, z2, r, g, b, a);
        line(vc, e, x1, y1, z1, x1, y1, z2, r, g, b, a);
        line(vc, e, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(vc, e, x1, y2, z1, x1, y2, z2, r, g, b, a);
        line(vc, e, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(vc, e, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(vc, e, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(vc, e, x1, y1, z2, x1, y2, z2, r, g, b, a);
        line(vc, e, x2, y1, z2, x2, y2, z2, r, g, b, a);
    }

    private static void line(VertexConsumer vc, MatrixStack.Entry e,
                             float xa, float ya, float za, float xb, float yb, float zb,
                             float r, float g, float b, float a) {
        float nx = xb - xa, ny = yb - ya, nz = zb - za;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return;
        nx /= len; ny /= len; nz /= len;
        vc.vertex(e, xa, ya, za).color(r, g, b, a).normal(e, nx, ny, nz);
        vc.vertex(e, xb, yb, zb).color(r, g, b, a).normal(e, nx, ny, nz);
    }
}
