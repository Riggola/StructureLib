package com.gtnewhorizon.structurelib.dismantle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.structurelib.ConfigurationHandler;
import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.alignment.constructable.ConstructableUtility;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.item.ItemConstructableTriggerV2;
import com.gtnewhorizon.structurelib.item.ProjectorMode;
import com.gtnewhorizon.structurelib.item.ProjectorTier;
import com.gtnewhorizon.structurelib.net.DismantleInputMessage;
import com.gtnewhorizon.structurelib.net.WirelessLinkMessage;
import com.gtnewhorizon.structurelib.structure.StructureCapture;
import com.gtnewhorizon.structurelib.structure.StructureCapture.Candidate;
import com.gtnewhorizon.structurelib.structure.StructureCapture.Position;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Server-side, tick-counted sustained confirmation and budgeted dismantling. */
public enum DismantleManager {

    INSTANCE;

    private static final int MAX_PENDING_INPUTS = 2048;
    private final ConcurrentLinkedQueue<Input> inputs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final Map<UUID, Hold> holds = new HashMap<>();
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final Map<UUID, Long> latched = new HashMap<>();
    /** Per-session only. Never written to item NBT or a world save. */
    private final Map<UUID, WirelessLink> links = new HashMap<>();
    private long tick;

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clearPlayerSession(event.player);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        clearPlayerSession(event.player);
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            unlink((EntityPlayerMP) event.player, false);
            UUID id = event.player.getUniqueID();
            holds.remove(id);
            jobs.remove(id);
            latched.remove(id);
        }
    }

    private void clearPlayerSession(EntityPlayer player) {
        UUID id = player.getUniqueID();
        holds.remove(id);
        jobs.remove(id);
        latched.remove(id);
        links.remove(id);
        clearSessionWirelessTags(player);
    }

    private static void clearSessionWirelessTags(EntityPlayer player) {
        for (ItemStack stack : player.inventory.mainInventory) ProjectorMode.setWireless(stack, false);
        player.inventory.markDirty();
    }

    public void enqueue(EntityPlayerMP player, byte action, int x, int y, int z) {
        if (action < DismantleInputMessage.TOGGLE || action > DismantleInputMessage.PROMPT) return;
        if (queued.incrementAndGet() > MAX_PENDING_INPUTS) {
            queued.decrementAndGet();
            return;
        }
        inputs.add(new Input(player, action, new Position(x, y, z)));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tick++;
        // Keep links across item changes and unloaded chunks, but discard removed controllers.
        for (WirelessLink link : new ArrayList<>(links.values())) validLink(link.player);
        Input input;
        while ((input = inputs.poll()) != null) {
            queued.decrementAndGet();
            if (input.player.isDead) continue;
            handleInput(input);
        }
        updateHolds();
        updateJobs();
        latched.entrySet().removeIf(e -> tick - e.getValue() > 3);
    }

    private void handleInput(Input input) {
        UUID id = input.player.getUniqueID();
        switch (input.action) {
            case DismantleInputMessage.TOGGLE:
                toggleMode(input.player);
                break;
            case DismantleInputMessage.BIND:
                bind(input.player, input.target);
                break;
            case DismantleInputMessage.UNLINK:
                if (ProjectorMode.isWireless(input.player.getHeldItem())) unlink(input.player, true);
                break;
            case DismantleInputMessage.REMOTE_BUILD:
                buildLinked(input.player);
                break;
            case DismantleInputMessage.PROMPT:
                handlePrompt(input);
                break;
            case DismantleInputMessage.RELEASE:
                holds.remove(id);
                latched.remove(id);
                break;
            case DismantleInputMessage.HOLD:
                handleHold(input, id);
                break;
            default:
                break;
        }
    }

    private void toggleMode(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        holds.remove(id);
        latched.remove(id);
        ItemStack stack = player.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemConstructableTriggerV2)) return;
        ProjectorMode mode = ProjectorMode.toggle(stack);
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        say(
                player,
                mode == ProjectorMode.DISMANTLE ? "structurelib.dismantle.mode.dismantle"
                        : "structurelib.dismantle.mode.build");
    }

    private void handlePrompt(Input input) {
        EntityPlayerMP player = input.player;
        ItemStack stack = player.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemConstructableTriggerV2)
                || ProjectorMode.get(stack) != ProjectorMode.DISMANTLE)
            return;

        if (input.target.x == DismantleInputMessage.REMOTE_TARGET_X) {
            WirelessLink link = ProjectorMode.isWireless(stack) ? validLink(player) : null;
            if (link != null) promptDismantle(player, link.controller);
            return;
        }

        Position pos = input.target;
        if (!player.worldObj.blockExists(pos.x, pos.y, pos.z)
                || player.getDistanceSq(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > 36)
            return;
        MovingObjectPosition sight = rayTraceController(player);
        if (!isControllerHit(sight, pos)) return;
        TileEntity controller = player.worldObj.getTileEntity(pos.x, pos.y, pos.z);
        if (controller == null || ConstructableUtility.getConstructable(controller, sight.sideHit) == null) return;
        promptDismantle(player, controller);
    }

    private void handleHold(Input input, UUID id) {
        EntityPlayerMP player = input.player;
        ItemStack stack = player.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemConstructableTriggerV2)
                || ProjectorMode.get(stack) != ProjectorMode.DISMANTLE)
            return;

        boolean wireless = ProjectorMode.isWireless(stack);
        Position target = input.target;
        if (wireless && target.x == DismantleInputMessage.REMOTE_TARGET_X) {
            WirelessLink link = validLink(player);
            if (link == null) return;
            target = link.target;
        } else {
            WirelessLink link = wireless ? validLink(player) : null;
            if (wireless && (link == null || !link.target.equals(target))) return;
        }

        if (jobs.containsKey(id) || latched.containsKey(id)) {
            latched.put(id, tick);
            return;
        }
        Hold hold = holds.get(id);
        if (hold == null || !hold.target.equals(target) || hold.wireless != wireless) {
            hold = new Hold(player, target, wireless);
            holds.put(id, hold);
        }
        hold.lastPulse = tick;
    }

    private void updateHolds() {
        for (Map.Entry<UUID, Hold> entry : new ArrayList<>(holds.entrySet())) {
            Hold hold = entry.getValue();
            if (tick - hold.lastPulse > 2 || !valid(hold)) {
                holds.remove(entry.getKey());
                StructureLib.sendDismantleHoldResolved(hold.player);
                continue;
            }
            if (hold.lastPulse != tick) continue;
            if (hold.pulses++ % 20 == 0) {
                int remaining = (ConfigurationHandler.INSTANCE.getDismantleHoldTicks() - hold.pulses + 19) / 20;
                hold.player.addChatMessage(
                        new ChatComponentTranslation("structurelib.dismantle.hold", Math.max(1, remaining)));
            }
            if (hold.pulses >= ConfigurationHandler.INSTANCE.getDismantleHoldTicks()) {
                holds.remove(entry.getKey());
                latched.put(entry.getKey(), tick);
                StructureLib.sendDismantleHoldResolved(hold.player);
                Job job = start(hold);
                if (job != null) jobs.put(entry.getKey(), job);
            }
        }
    }

    private void updateJobs() {
        for (Map.Entry<UUID, Job> entry : new ArrayList<>(jobs.entrySet())) {
            if (!entry.getValue().process()) jobs.remove(entry.getKey());
        }
    }

    private boolean valid(Hold hold) {
        EntityPlayerMP player = hold.player;
        ItemStack held = player.getHeldItem();
        if (player.isDead || player.dimension != hold.dimension
                || player.inventory.currentItem != hold.slot
                || !player.isSneaking()
                || held == null
                || !ItemStack.areItemStacksEqual(held, hold.stack)
                || ProjectorMode.get(held) != ProjectorMode.DISMANTLE
                || ProjectorMode.isWireless(held) != hold.wireless)
            return false;
        Position p = hold.target;
        if (hold.wireless) {
            WirelessLink link = validLink(player);
            return link != null && p.equals(link.target)
                    && ConstructableUtility.getConstructable(link.controller, link.side) != null;
        }
        if (player.getDistanceSq(p.x + 0.5, p.y + 0.5, p.z + 0.5) > 36 || !player.worldObj.blockExists(p.x, p.y, p.z))
            return false;
        MovingObjectPosition sight = rayTraceController(player);
        if (!isControllerHit(sight, p)) return false;
        TileEntity target = player.worldObj.getTileEntity(p.x, p.y, p.z);
        return target != null && ConstructableUtility.getConstructable(target, sight.sideHit) != null;
    }

    private Job start(Hold hold) {
        Position p = hold.target;
        World world = hold.player.worldObj;
        TileEntity controller = world.getTileEntity(p.x, p.y, p.z);
        IConstructable constructable;
        if (hold.wireless) {
            WirelessLink link = validLink(hold.player);
            constructable = link == null ? null : ConstructableUtility.getConstructable(link.controller, link.side);
        } else {
            MovingObjectPosition sight = rayTraceController(hold.player);
            constructable = controller == null || !isControllerHit(sight, p) ? null
                    : ConstructableUtility.getConstructable(controller, sight.sideHit);
        }
        if (constructable == null) {
            say(hold.player, "structurelib.dismantle.unsupported");
            return null;
        }
        String blocked = dismantleBlockedReason(controller);
        if (blocked != null) {
            say(hold.player, blocked);
            return null;
        }
        Ae2DismantleStorage storage = Ae2DismantleStorage.find(hold.player);
        if (storage == null) {
            say(hold.player, "structurelib.dismantle.no_network");
            return null;
        }
        StructureCapture capture = StructureCapture
                .collect(constructable, ProjectorTier.forStructure(hold.stack), world, p);
        if (capture == null) {
            say(hold.player, "structurelib.dismantle.unsupported");
            return null;
        }
        Set<Position> shared = SharedBlockProtection
                .findShared(world, controller, capture, ProjectorTier.forStructure(hold.stack));
        if (shared == null) {
            say(hold.player, "structurelib.dismantle.shared_scan_failed");
            return null;
        }
        if (!shared.isEmpty()) say(hold.player, "structurelib.dismantle.shared_preserved", shared.size());
        Queue<Candidate> queue = new ArrayDeque<>();
        for (Candidate candidate : capture.getCandidates()) {
            if (!shared.contains(candidate.pos)) queue.add(candidate);
        }
        if (queue.isEmpty()) {
            say(hold.player, "structurelib.dismantle.no_unshared_blocks");
            return null;
        }
        say(hold.player, "structurelib.dismantle.started");
        return new Job(hold.player, controller, p, queue, storage);
    }

    private static void say(EntityPlayerMP player, String key) {
        player.addChatMessage(new ChatComponentTranslation(key));
    }

    private static void say(EntityPlayerMP player, String key, Object... args) {
        player.addChatMessage(new ChatComponentTranslation(key, args));
    }

    private static void promptDismantle(EntityPlayerMP player, TileEntity controller) {
        String blocked = dismantleBlockedReason(controller);
        say(player, blocked == null ? "structurelib.dismantle.prompt" : blocked);
    }

    /**
     * Fail before capturing or removing anything when a machine is running. GT5 is optional to StructureLib, so resolve
     * the conventional controller methods without linking its classes. The God Forge has a separate visual renderer
     * which physically replaces its rings; even when processing is off, its rings must be restored by disabling the
     * renderer first.
     *
     * @return localization key explaining the block, or null when dismantle may proceed.
     */
    private static String dismantleBlockedReason(TileEntity controller) {
        if (controller == null) return null;
        Object meta = invokeNoArgs(controller, "getMetaTileEntity");
        if (meta != null
                && "tectech.thing.metaTileEntity.multi.godforge.MTEForgeOfGods".equals(meta.getClass().getName())) {
            try {
                Field field = meta.getClass().getDeclaredField("data");
                field.setAccessible(true);
                Object state = field.get(meta);
                if (state == null || !Boolean.FALSE.equals(invokeNoArgs(state, "isRenderActive")))
                    return "structurelib.dismantle.renderer_active";
            } catch (ReflectiveOperationException | SecurityException error) {
                StructureLib.LOGGER.warn("Could not inspect the God Forge renderer state safely", error);
                return "structurelib.dismantle.renderer_active";
            }
        }
        if (Boolean.TRUE.equals(invokeNoArgs(controller, "isActive"))
                || (meta != null && Boolean.TRUE.equals(invokeNoArgs(meta, "isActive"))))
            return "structurelib.dismantle.controller_active";
        return null;
    }

    private static Object invokeNoArgs(Object target, String method) {
        try {
            Method accessor = target.getClass().getMethod(method);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException | SecurityException error) {
            return null;
        }
    }

    private static String controllerDisplayName(TileEntity controller) {
        // Real, localized in-game name (e.g. Exo-Foundry Controller).
        Object meta = invokeNoArgs(controller, "getMetaTileEntity");
        Object name = meta == null ? null : invokeNoArgs(meta, "getLocalName");
        if (name instanceof String && !((String) name).trim().isEmpty()) return (String) name;
        return controller.getBlockType().getLocalizedName();
    }

    private void bind(EntityPlayerMP player, Position requested) {
        ItemStack projector = player.getHeldItem();
        if (projector == null || !(projector.getItem() instanceof ItemConstructableTriggerV2) || player.isSneaking())
            return;
        if (!player.worldObj.blockExists(requested.x, requested.y, requested.z)
                || player.getDistanceSq(requested.x + 0.5, requested.y + 0.5, requested.z + 0.5) > 36)
            return;
        MovingObjectPosition sight = rayTraceController(player);
        if (!isControllerHit(sight, requested)) return;
        TileEntity controller = player.worldObj.getTileEntity(requested.x, requested.y, requested.z);
        if (controller == null || ConstructableUtility.getConstructable(controller, sight.sideHit) == null) return;
        WirelessLink previous = links.get(player.getUniqueID());
        if (previous != null) ProjectorMode.setWireless(previous.projector, false);
        clearSessionWirelessTags(player);
        ProjectorMode.setWireless(projector, true);
        links.put(player.getUniqueID(), new WirelessLink(player, projector, controller, requested, sight.sideHit));
        holds.remove(player.getUniqueID());
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        StructureLib.sendWirelessLink(
                new WirelessLinkMessage(true, requested.x, requested.y, requested.z, player.dimension, sight.sideHit),
                player);
        say(
                player,
                "structurelib.wireless.linked",
                controllerDisplayName(controller),
                requested.x,
                requested.y,
                requested.z);
    }

    private WirelessLink validLink(EntityPlayerMP player) {
        WirelessLink link = links.get(player.getUniqueID());
        if (link == null) return null;
        if (player.isDead || player.dimension != link.dimension || player.worldObj != link.world) {
            unlink(player, false);
            return null;
        }
        if (!link.world.blockExists(link.target.x, link.target.y, link.target.z)) return null;
        if (link.world.getBlock(link.target.x, link.target.y, link.target.z) != link.block
                || link.world.getBlockMetadata(link.target.x, link.target.y, link.target.z) != link.metadata) {
            unlink(player, false);
            return null;
        }
        TileEntity controller = link.world.getTileEntity(link.target.x, link.target.y, link.target.z);
        if (controller == null) return null;
        Object meta = invokeNoArgs(controller, "getMetaTileEntity");
        if (controller.getClass() != link.controllerClass || (meta != null && meta.getClass() != link.metaTileClass)) {
            unlink(player, false);
            return null;
        }
        if (link.metaTileClass != null && meta == null) return null;
        link.controller = controller;
        return link;
    }

    private void unlink(EntityPlayerMP player, boolean announce) {
        WirelessLink link = links.remove(player.getUniqueID());
        holds.remove(player.getUniqueID());
        if (link == null) {
            // Repair a stale session-only NBT overlay, without claiming a link was removed.
            ItemStack held = player.getHeldItem();
            if (ProjectorMode.isWireless(held)) {
                ProjectorMode.setWireless(held, false);
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
                StructureLib.sendWirelessLink(new WirelessLinkMessage(false, 0, 0, 0, player.dimension, 0), player);
            }
            return;
        }
        ProjectorMode.setWireless(link.projector, false);
        clearSessionWirelessTags(player);
        player.inventoryContainer.detectAndSendChanges();
        StructureLib.sendWirelessLink(new WirelessLinkMessage(false, 0, 0, 0, player.dimension, 0), player);
        if (announce) say(player, "structurelib.wireless.unlinked");
    }

    private void buildLinked(EntityPlayerMP player) {
        ItemStack held = player.getHeldItem();
        if (held == null || !ProjectorMode.isWireless(held)
                || ProjectorMode.get(held) != ProjectorMode.BUILD
                || !player.isSneaking())
            return;
        if (jobs.containsKey(player.getUniqueID())) return;
        WirelessLink link = validLink(player);
        if (link == null) {
            say(player, "structurelib.wireless.no_link");
            return;
        }
        ConstructableUtility
                .handleRemote(held, player, link.world, link.target.x, link.target.y, link.target.z, link.side);
    }

    /** Vanilla 1.7.10 rayTrace is not available on a dedicated-server EntityPlayerMP. */
    private static MovingObjectPosition rayTraceController(EntityPlayerMP player) {
        Vec3 look = player.getLook(1.0F);
        if (look == null) return null;
        Vec3 eyes = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 end = eyes.addVector(look.xCoord * 5.0D, look.yCoord * 5.0D, look.zCoord * 5.0D);
        return player.worldObj.rayTraceBlocks(eyes, end);
    }

    private static boolean isControllerHit(MovingObjectPosition hit, Position pos) {
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.blockX == pos.x
                && hit.blockY == pos.y
                && hit.blockZ == pos.z;
    }

    private static final class Input {

        final EntityPlayerMP player;
        final byte action;
        final Position target;

        Input(EntityPlayerMP player, byte action, Position target) {
            this.player = player;
            this.action = action;
            this.target = target;
        }
    }

    private static final class WirelessLink {

        final EntityPlayerMP player;
        final ItemStack projector;
        final World world;
        TileEntity controller;
        final Position target;
        final int dimension;
        final int side;
        final Block block;
        final int metadata;
        final Class<?> controllerClass;
        final Class<?> metaTileClass;

        WirelessLink(EntityPlayerMP player, ItemStack projector, TileEntity controller, Position target, int side) {
            this.player = player;
            this.projector = projector;
            this.world = player.worldObj;
            this.controller = controller;
            this.target = target;
            this.dimension = player.dimension;
            this.side = side;
            this.block = world.getBlock(target.x, target.y, target.z);
            this.metadata = world.getBlockMetadata(target.x, target.y, target.z);
            this.controllerClass = controller.getClass();
            Object meta = invokeNoArgs(controller, "getMetaTileEntity");
            this.metaTileClass = meta == null ? null : meta.getClass();
        }
    }

    private static final class Hold {

        final EntityPlayerMP player;
        final ItemStack stack;
        final int slot;
        final int dimension;
        final Position target;
        final boolean wireless;
        long lastPulse;
        int pulses;

        Hold(EntityPlayerMP player, Position target, boolean wireless) {
            this.player = player;
            ItemStack held = player.getHeldItem();
            this.stack = held == null ? null : held.copy();
            this.slot = player.inventory.currentItem;
            this.dimension = player.dimension;
            this.target = target;
            this.wireless = wireless;
        }
    }

    private static final class Job {

        final EntityPlayerMP player;
        final TileEntity controller;
        final Position origin;
        final Queue<Candidate> remaining;
        final Ae2DismantleStorage storage;
        long lastBatch = Long.MIN_VALUE / 2;

        Job(EntityPlayerMP player, TileEntity controller, Position origin, Queue<Candidate> remaining,
                Ae2DismantleStorage storage) {
            this.player = player;
            this.controller = controller;
            this.origin = origin;
            this.remaining = remaining;
            this.storage = storage;
        }

        boolean process() {
            World world = player.worldObj;
            if (player.isDead || player.dimension != controller.getWorldObj().provider.dimensionId
                    || world.getTileEntity(origin.x, origin.y, origin.z) != controller
                    || !storage.isStillConnected(player)) {
                say(player, "structurelib.dismantle.stopped");
                return false;
            }
            String blocked = dismantleBlockedReason(controller);
            if (blocked != null) {
                say(player, blocked);
                return false;
            }
            long now = System.currentTimeMillis();
            if (now - lastBatch < ConfigurationHandler.INSTANCE.getAutoPlaceInterval()) return true;
            lastBatch = now;
            int budget = ConfigurationHandler.INSTANCE.getAutoPlaceBudget();
            for (int i = 0; i < budget && !remaining.isEmpty(); i++) {
                Candidate next = remaining.peek();
                Position p = next.pos;
                if (!world.blockExists(p.x, p.y, p.z) || world.getBlock(p.x, p.y, p.z) != next.block
                        || world.getBlockMetadata(p.x, p.y, p.z) != next.metadata) {
                    say(player, "structurelib.dismantle.changed");
                    return false;
                }
                Block block = next.block;
                List<ItemStack> drops = block.getDrops(world, p.x, p.y, p.z, next.metadata, 0);
                if (drops == null || drops.isEmpty()
                        || drops.stream().anyMatch(s -> s == null || s.getItem() == null || s.stackSize <= 0)) {
                    say(player, "structurelib.dismantle.no_drop");
                    return false;
                }
                if (!storage.canAccept(drops)) {
                    say(player, "structurelib.dismantle.storage_full");
                    return false;
                }
                BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(
                        p.x,
                        p.y,
                        p.z,
                        world,
                        block,
                        next.metadata,
                        player);
                if (MinecraftForge.EVENT_BUS.post(breakEvent)) {
                    say(player, "structurelib.dismantle.protected");
                    return false;
                }
                if (!world.setBlockToAir(p.x, p.y, p.z)) {
                    say(player, "structurelib.dismantle.changed");
                    return false;
                }
                List<ItemStack> leftovers = storage.insert(drops);
                if (!leftovers.isEmpty()) {
                    for (ItemStack residual : leftovers) {
                        ItemStack unreturned = residual.copy();
                        player.inventory.addItemStackToInventory(unreturned);
                        if (unreturned.stackSize > 0) {
                            world.spawnEntityInWorld(
                                    new EntityItem(world, origin.x + 0.5, origin.y + 1.0, origin.z + 0.5, unreturned));
                        }
                    }
                    say(player, "structurelib.dismantle.storage_full");
                    return false;
                }
                remaining.remove();
            }
            if (!remaining.isEmpty()) return true;
            say(player, "structurelib.dismantle.complete");
            return false;
        }
    }
}
