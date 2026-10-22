package pl.regionbot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import pl.regionbot.mixin.InteractionManagerAccessor;

/**
 * Bot: lata na elytrze z fajerwerkami między zaznaczonymi obszarami,
 * kopie siekierą drewno akacjowe / ciemnodębowe / dębowe i ucieka, gdy gracz jest w zasięgu 8 kratek.
 */
public final class Bot {
    public static final Bot INSTANCE = new Bot();

    private enum State { IDLE, TAKEOFF, ASCEND, CRUISE, DIVE, MINE, WAIT }

    private static final double FLEE_RANGE = 8.0;
    private static final double REACH = 4.4;
    private static final int AXE_SLOT = 0;     // gdzie wrzucić siekierę, jeśli jest tylko w plecaku
    private static final int ELYTRA_SLOT = 2;
    private static final int ROCKET_SLOT = 3;  // gdzie wrzucić fajerwerki, jeśli są tylko w plecaku

    private static final Set<Block> LOGS = Set.of(
            Blocks.OAK_LOG, Blocks.OAK_WOOD, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_OAK_WOOD,
            Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STRIPPED_DARK_OAK_WOOD,
            Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_ACACIA_WOOD);

    private State state = State.IDLE;
    private int regionIdx = 0;
    private Regions.Region region;
    private BlockPos mineTarget;
    private Vec3d dest;
    private Vec3d lastPos = Vec3d.ZERO;
    private double cruiseY;
    private int stateTicks, rocketCd, stuckTicks, breakTicks;
    private long now;
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private String status = "Wyłączony";
    private boolean strafeLeft = true;
    private int airTicks, goalTicks;
    private Vec3d walkGoal;
    private Vec3d lastStep = Vec3d.ZERO;
    private final java.util.Random random = new java.util.Random();

    /** Wirtualne klawisze bota – wstrzykiwane prosto do ruchu gracza (KeyboardInputMixin). */
    public boolean inForward, inBack, inLeft, inRight, inJump, inSprint;
    /** Analogowy ruch (gdy analog = true): przód/tył i bok w zakresie -1..1. */
    public boolean analog;
    public float moveF, moveS;

    private Bot() {}

    public boolean isRunning() { return state != State.IDLE; }
    public String getStatus() { return status; }
    public Regions.Region currentRegion() { return isRunning() ? region : null; }

    // ------------------------------------------------------------------ start / stop

    public void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        if (isRunning()) { msg("§eBot już działa."); return; }
        List<Regions.Region> list = Regions.LIST;
        if (list.isEmpty()) { msg("§cNajpierw zaznacz obszar klawiszem J."); return; }

        blacklist.clear();
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            double d = list.get(i).topCenter().squaredDistanceTo(p.getPos());
            if (d < bestD) { bestD = d; best = i; }
        }
        regionIdx = best;
        msg("§aBot włączony.");
        goTo(list.get(best), false);
    }

    public void stop() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean was = isRunning();
        state = State.IDLE;
        status = "Wyłączony";
        releaseKeys(mc);
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        if (was) msg("§cBot zatrzymany – masz kontrolę.");
    }

    // ------------------------------------------------------------------ główna pętla

    public void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            state = State.IDLE;
            status = "Wyłączony";
            return;
        }
        if (!p.isAlive()) { stop(); return; }
        if (Regions.LIST.isEmpty()) { msg("§cBrak obszarów."); stop(); return; }
        if (region == null || !Regions.LIST.contains(region)) {
            regionIdx = 0;
            region = Regions.LIST.get(0);
        }
        regionIdx = Math.floorMod(regionIdx, Regions.LIST.size());

        now++;
        stateTicks++;
        if (rocketCd > 0) rocketCd--;

        switch (state) {
            case TAKEOFF -> takeoff(mc, p);
            case ASCEND -> ascend(mc, p);
            case CRUISE -> cruise(mc, p);
            case DIVE -> dive(mc, p);
            case MINE -> mine(mc, p);
            case WAIT -> waitForRegrow(mc, p);
            default -> {}
        }
        if (state != State.IDLE) syncKeys(mc);
    }

    private void setState(State s) {
        state = s;
        stateTicks = 0;
    }

    // ------------------------------------------------------------------ nawigacja między obszarami

    private void goTo(Regions.Region r, boolean flee) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        region = r;
        mineTarget = null;
        stuckTicks = 0;
        breakTicks = 0;
        if (p == null || mc.world == null) return;

        Vec3d c = r.topCenter();
        double h = horiz(p.getPos(), c);
        if (!flee && p.isOnGround() && (h < 14 || r.containsXZ(p.getX(), p.getZ())) && Math.abs(p.getY() - c.y) < 12) {
            setState(State.MINE);        // blisko – dojdzie pieszo
            return;
        }
        int top = mc.world.getBottomY() + mc.world.getHeight() - 4;
        cruiseY = Math.min(top, Math.max(p.getY(), r.max().getY()) + 25);
        dest = null;
        rocketCd = 0;
        setState(State.TAKEOFF);
        takeoff(mc, p);                          // skok w tym samym ticku – bez czekania
    }

    private void goNext(MinecraftClient mc, boolean flee) {
        mc.interactionManager.cancelBlockBreaking();
        releaseKeys(mc);
        Regions.Region next = pickNext(mc.world, flee);
        if (next == null) {
            if (flee) { goTo(region, true); return; } // jedyny obszar – odlatuje i wraca
            msg("§7Wszystkie obszary skopane – czekam aż odrosną.");
            setState(State.WAIT);
            return;
        }
        goTo(next, flee);
    }

    private Regions.Region pickNext(ClientWorld w, boolean flee) {
        List<Regions.Region> list = Regions.LIST;
        int n = list.size();
        for (int i = 1; i <= n; i++) {
            int idx = (regionIdx + i) % n;
            Regions.Region r = list.get(idx);
            if (flee && idx == regionIdx) continue;
            if (regionLoaded(w, r) && findTarget(w, null, r) == null) continue; // widać, że pusty – nie leć
            regionIdx = idx;
            return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ lot

    private void takeoff(MinecraftClient mc, ClientPlayerEntity p) {
        status = "Start z elytrą → obszar " + (regionIdx + 1);
        inForward = (false);
        inSprint = (false);
        

        if (!ensureElytra(mc, p)) { msg("§cBrak sprawnej elytry w ekwipunku!"); stop(); return; }
        if (!select(mc, p, s -> s.isOf(Items.FIREWORK_ROCKET), ROCKET_SLOT)) { msg("§cBrak fajerwerek!"); stop(); return; }

        lookYaw(p, region.topCenter());
        p.setPitch(-20f);
        analog = false;
        inBack = inLeft = inRight = false;

        if (p.isGliding()) {
            inJump = false;
            // serwer potwierdził rozłożenie elytry -> od razu fajerwerka
            setState(State.ASCEND);
            rocketCd = 0;
            useRocket(mc, p);
            return;
        }
        if (p.isOnGround() || p.isTouchingWater()) {
            // 1) skok – dokładnie to, co robi gra po wciśnięciu spacji na ziemi
            inJump = true;
            airTicks = 0;
            if (p.isOnGround()) p.jump();
            return;
        }
        // 2) w powietrzu: gdy zaczyna opadać – "drugie kliknięcie spacji" = rozłożenie elytry
        //    (ta sama funkcja i ten sam pakiet, które gra wysyła przy ręcznym kliknięciu)
        airTicks++;
        inJump = false;
        if (airTicks >= 3 && p.getVelocity().y < 0.1 && airTicks % 3 == 0) {
            if (p.checkGliding()) sendGlide(p);
            else sendGlide(p);
        }
        if (stateTicks > 80) setState(State.TAKEOFF);
    }

    private void ascend(MinecraftClient mc, ClientPlayerEntity p) {
        status = "Wznoszę się (Y " + (int) p.getY() + " → " + (int) cruiseY + ")";
        if (!airborne(mc, p)) return;
        lookYaw(p, region.topCenter());
        p.setPitch(-55f);
        if (p.getVelocity().length() < 1.2) useRocket(mc, p);
        if (state == State.IDLE) return;
        if (p.getY() >= cruiseY || stateTicks > 300) {
            dest = computeDest(mc.world, p);
            setState(State.CRUISE);
        }
    }

    private void cruise(MinecraftClient mc, ClientPlayerEntity p) {
        status = "Lecę do obszaru " + (regionIdx + 1);
        if (!airborne(mc, p)) return;
        if (dest == null || stateTicks % 10 == 0) dest = computeDest(mc.world, p);

        lookYaw(p, dest);
        double dy = p.getY() - cruiseY;
        float pitch = (float) MathHelper.clamp(dy * 3.0, -30.0, 20.0);
        if (p.horizontalCollision) pitch = -50f;
        p.setPitch(pitch);

        Vec3d v = p.getVelocity();
        if (Math.hypot(v.x, v.z) < 1.5 || dy < -8) useRocket(mc, p);
        if (state == State.IDLE) return;

        double h = horiz(p.getPos(), dest);
        if (h <= Math.max(5.0, (p.getY() - dest.y) * 1.1)) setState(State.DIVE);
    }

    private void dive(MinecraftClient mc, ClientPlayerEntity p) {
        status = "Nurkuję do obszaru " + (regionIdx + 1);
        if (dest == null || stateTicks % 10 == 0) dest = computeDest(mc.world, p);
        lookAt(p, dest);
        if (p.getY() - dest.y < 5 && p.getPitch() > 35f) p.setPitch(35f);

        if (!p.isGliding() || p.isOnGround() || p.isTouchingWater() || stateTicks > 400) {
            releaseKeys(mc);
            mineTarget = null;
            setState(State.MINE);
        }
    }

    /** true = szybuje; w przeciwnym razie próbuje ponownie rozłożyć elytrę lub wystartować. */
    private boolean airborne(MinecraftClient mc, ClientPlayerEntity p) {
        if (p.isGliding()) { inJump = false; return true; }
        if (p.isOnGround() || p.isTouchingWater()) { setState(State.TAKEOFF); return false; }
        // spadł z elytry w powietrzu – ponowne "kliknięcie spacji"
        airTicks++;
        inJump = false;
        if (airTicks % 4 == 0) { if (p.checkGliding()) sendGlide(p); else sendGlide(p); }
        return false;
    }

    private Vec3d computeDest(ClientWorld w, ClientPlayerEntity p) {
        BlockPos t = findTarget(w, p, region);
        int x, z;
        if (t != null) { x = t.getX(); z = t.getZ(); }
        else { Vec3d c = region.topCenter(); x = MathHelper.floor(c.x); z = MathHelper.floor(c.z); }
        int topY = Math.min(region.max().getY() + 10, w.getBottomY() + w.getHeight() - 1);
        int y = region.max().getY() + 1;
        for (int yy = topY; yy >= region.min().getY() - 5; yy--) {
            if (!w.getBlockState(new BlockPos(x, yy, z)).isAir()) { y = yy + 1; break; }
        }
        return new Vec3d(x + 0.5, y + 0.2, z + 0.5);
    }

    private void useRocket(MinecraftClient mc, ClientPlayerEntity p) {
        if (rocketCd > 0) return;
        if (!select(mc, p, s -> s.isOf(Items.FIREWORK_ROCKET), ROCKET_SLOT)) {
            msg("§cSkończyły się fajerwerki!");
            stop();
            return;
        }
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        rocketCd = 18;
    }

    private static void sendGlide(ClientPlayerEntity p) {
        p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }

    // ------------------------------------------------------------------ kopanie

    private void mine(MinecraftClient mc, ClientPlayerEntity p) {
        if (playerNear(mc, p)) {
            msg("§6Gracz w pobliżu (≤8 kratek) – odlatuję!");
            goNext(mc, true);
            return;
        }
        ClientWorld w = mc.world;

        if (mineTarget != null && !isLog(w.getBlockState(mineTarget))) mineTarget = null; // skopane → od razu następny
        if (mineTarget == null) {
            mineTarget = findTarget(w, p, region);
            stuckTicks = 0;
            breakTicks = 0;
            lastPos = p.getPos();
            if (mineTarget == null) {
                msg("§7Obszar " + (regionIdx + 1) + " skopany.");
                goNext(mc, false);
                return;
            }
        }
        status = "Kopię w obszarze " + (regionIdx + 1);

        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(mineTarget);
        if (eye.distanceTo(c) <= REACH) {
            lookAt(p, c);
            // podczas kopania bot cały czas trzyma W
            analog = false;
            inForward = true;
            inBack = inLeft = inRight = false;
            inJump = p.horizontalCollision && p.isOnGround() && stateTicks % 10 == 0;
            pushForward(p);
            inSprint = false;

            select(mc, p, s -> s.getItem() instanceof AxeItem, AXE_SLOT);
            Direction side = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
            ((InteractionManagerAccessor) mc.interactionManager).regionbot$setBlockBreakingCooldown(0);
            mc.interactionManager.updateBlockBreakingProgress(mineTarget, side);
            p.swingHand(Hand.MAIN_HAND);

            if (++breakTicks > 200) {          // nie da się skopać – pomiń
                blacklist.put(mineTarget, now + 600);
                mc.interactionManager.cancelBlockBreaking();
                mineTarget = null;
            }
        } else {
            walkTo(mc, p, c);
            if (stateTicks % 20 == 0) {
                if (p.getPos().distanceTo(lastPos) < 0.4) stuckTicks += 20; else stuckTicks = 0;
                lastPos = p.getPos();
            }
            if (stuckTicks >= 80) {             // utknął – spróbuj inny blok
                blacklist.put(mineTarget, now + 600);
                mineTarget = null;
                releaseKeys(mc);
            }
        }
    }

    /** Losowe miejsce do stania (twardy blok pod nogami, 2 wolne nad nim), z którego kłoda jest w zasięgu. */
    private Vec3d pickWalkGoal(ClientWorld w, ClientPlayerEntity p, BlockPos target) {
        Vec3d best = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            int dx = random.nextInt(7) - 3;
            int dz = random.nextInt(7) - 3;
            for (int dy = 1; dy >= -3; dy--) {
                BlockPos feet = target.add(dx, dy, dz);
                if (!w.getBlockState(feet).getCollisionShape(w, feet).isEmpty()) continue;
                if (!w.getBlockState(feet.up()).getCollisionShape(w, feet.up()).isEmpty()) continue;
                BlockPos below = feet.down();
                if (w.getBlockState(below).getCollisionShape(w, below).isEmpty()) continue;
                Vec3d stand = new Vec3d(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                Vec3d eye = stand.add(0, 1.62, 0);
                if (eye.distanceTo(Vec3d.ofCenter(target)) > REACH - 0.4) break;
                if (horiz(stand, p.getPos()) < 1.0) break;
                if (Math.abs(stand.y - p.getY()) > 1.2) break;
                return stand;
            }
        }
        return best;
    }

    /** Idzie w stronę punktu niezależnie od tego, gdzie patrzy (analogowe W/A/S/D). */
    private void steerTowards(ClientPlayerEntity p, Vec3d goal) {
        double dx = goal.x - p.getX(), dz = goal.z - p.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) { analog = true; moveF = moveS = 0f; return; }
        dx /= len; dz /= len;
        double yaw = Math.toRadians(p.getYaw());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        moveF = (float) (-dx * sin + dz * cos);
        moveS = (float) (dx * cos + dz * sin);
        analog = true;
        inForward = moveF > 0.3f;
        inBack = moveF < -0.3f;
        inLeft = moveS > 0.3f;
        inRight = moveS < -0.3f;
    }

    /** Ruch do przodu bezpośrednio (gdyby gra pominęła wciśnięte W). */
    private static void pushForward(ClientPlayerEntity p) {
        if (!p.isOnGround()) return;
        Vec3d v = p.getVelocity();
        if (Math.hypot(v.x, v.z) > 0.09) return;
        double yaw = Math.toRadians(p.getYaw());
        p.setVelocity(-Math.sin(yaw) * 0.11, v.y, Math.cos(yaw) * 0.11);
    }

    /** Odzwierciedla wirtualne klawisze na prawdziwych klawiszach gry (W, spacja, sprint). */
    private void syncKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(inForward);
        mc.options.backKey.setPressed(inBack);
        mc.options.leftKey.setPressed(inLeft);
        mc.options.rightKey.setPressed(inRight);
        mc.options.jumpKey.setPressed(inJump);
        mc.options.sprintKey.setPressed(inSprint);
    }

    private void walkTo(MinecraftClient mc, ClientPlayerEntity p, Vec3d c) {
        lookAt(p, c);
        analog = false;
        inForward = true;
        inBack = inLeft = inRight = false;
        inSprint = true;
        p.setSprinting(true);
        pushForward(p);
        double h = horiz(p.getPos(), c);
        boolean jump = p.horizontalCollision || p.isTouchingWater() || (c.y - 0.5 > p.getY() + 1.0 && h < 2.5);
        inJump = (jump && (p.isOnGround() || p.isTouchingWater()));
    }

    private void waitForRegrow(MinecraftClient mc, ClientPlayerEntity p) {
        status = "Wszystko skopane – czekam na odrost";
        inForward = (false);
        inSprint = (false);
        inJump = (false);
        if (playerNear(mc, p) && p.isOnGround()) {
            msg("§6Gracz w pobliżu – odlatuję!");
            goTo(region, true);
            return;
        }
        if (stateTicks % 40 == 0) {
            Regions.Region r = pickNext(mc.world, false);
            if (r != null) goTo(r, false);
        }
    }

    private BlockPos findTarget(ClientWorld w, ClientPlayerEntity p, Regions.Region r) {
        Vec3d ref = p != null ? p.getEyePos() : r.topCenter();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockPos min = r.min(), max = r.max();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = min.getY(); y <= max.getY(); y++) {
                    m.set(x, y, z);
                    if (!isLog(w.getBlockState(m))) continue;
                    Long until = blacklist.get(m);
                    if (until != null) {
                        if (until > now) continue;
                        blacklist.remove(m.toImmutable());
                    }
                    double d = ref.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
                    if (d < bestD) { bestD = d; best = m.toImmutable(); }
                }
            }
        }
        return best;
    }

    private static boolean regionLoaded(ClientWorld w, Regions.Region r) {
        for (int cx = r.min().getX() >> 4; cx <= r.max().getX() >> 4; cx++)
            for (int cz = r.min().getZ() >> 4; cz <= r.max().getZ() >> 4; cz++)
                if (!w.getChunkManager().isChunkLoaded(cx, cz)) return false;
        return true;
    }

    private static boolean isLog(BlockState s) {
        return LOGS.contains(s.getBlock());
    }

    private static boolean playerNear(MinecraftClient mc, ClientPlayerEntity p) {
        for (PlayerEntity o : mc.world.getPlayers()) {
            if (o == p || o.isSpectator()) continue;
            if (o.squaredDistanceTo(p) <= FLEE_RANGE * FLEE_RANGE) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ekwipunek

    private static boolean usable(ItemStack s) {
        return s.getMaxDamage() == 0 || s.getDamage() < s.getMaxDamage() - 1;
    }

    /** Zakłada elytrę, jeśli nie jest założona (zamienia ją ze slotem na klatę). */
    private static boolean ensureElytra(MinecraftClient mc, ClientPlayerEntity p) {
        ItemStack chest = p.getEquippedStack(EquipmentSlot.CHEST);
        if (chest.isOf(Items.ELYTRA) && usable(chest)) return true;
        int inv = find(p, s -> s.isOf(Items.ELYTRA) && usable(s));
        if (inv < 0) return false;
        int sync = p.playerScreenHandler.syncId;
        int hotbar = inv;
        if (inv >= 9) {
            mc.interactionManager.clickSlot(sync, inv, ELYTRA_SLOT, SlotActionType.SWAP, p);
            hotbar = ELYTRA_SLOT;
        }
        mc.interactionManager.clickSlot(sync, 6, hotbar, SlotActionType.SWAP, p); // 6 = slot napierśnika
        return p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    /** Wybiera przedmiot na pasku; jeśli jest tylko w plecaku – przenosi go na pasek. */
    private static boolean select(MinecraftClient mc, ClientPlayerEntity p, Predicate<ItemStack> what, int preferred) {
        for (int i = 0; i < 9; i++) {
            if (what.test(p.getInventory().getStack(i))) {
                p.getInventory().selectedSlot = i;
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (what.test(p.getInventory().getStack(i))) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, preferred, SlotActionType.SWAP, p);
                p.getInventory().selectedSlot = preferred;
                return true;
            }
        }
        return false;
    }

    private static int find(ClientPlayerEntity p, Predicate<ItemStack> what) {
        for (int i = 0; i < 36; i++) if (what.test(p.getInventory().getStack(i))) return i;
        return -1;
    }

    // ------------------------------------------------------------------ pomocnicze

    private static void lookAt(ClientPlayerEntity p, Vec3d t) {
        Vec3d e = p.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI);
        p.setYaw(p.getYaw() + MathHelper.wrapDegrees(yaw - p.getYaw()));
        p.setPitch(MathHelper.clamp(pitch, -90f, 90f));
    }

    private static void lookYaw(ClientPlayerEntity p, Vec3d t) {
        double dx = t.x - p.getX(), dz = t.z - p.getZ();
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        p.setYaw(p.getYaw() + MathHelper.wrapDegrees(yaw - p.getYaw()));
    }

    private static double horiz(Vec3d a, Vec3d b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private void releaseKeys(MinecraftClient mc) {
        inForward = inBack = inLeft = inRight = false;
        analog = false;
        moveF = moveS = 0f;
        inJump = (false);
        inSprint = (false);
        syncKeys(mc);
        
    }

    private static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("§4[RegionBot] §r" + s), false);
    }
}
