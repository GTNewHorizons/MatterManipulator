package com.recursive_pineapple.matter_manipulator.common.items.manipulator;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.Phase;
import cpw.mods.fml.common.gameevent.TickEvent.PlayerTickEvent;
import cpw.mods.fml.relauncher.Side;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import com.gtnewhorizon.gtnhlib.util.AboveHotbarHUD;
import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.recursive_pineapple.matter_manipulator.GlobalMMConfig;
import com.recursive_pineapple.matter_manipulator.GlobalMMConfig.RenderingConfig;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.client.rendering.BoxRenderer;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig.VoxelAABB;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PlaceMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.Shape;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.opengl.GL11;

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

@EventBusSubscriber(side = Side.CLIENT)
public class MMRenderer {

    private static long lastAnalysisMS = 0;

    private static MMConfig lastAnalyzedConfig = null;

    private static Location lastPlayerPosition = null;

    private static List<PendingBlock> analysisCache = null;

    private static ItemMatterManipulator lastDrawer = null;

    private static boolean wasValid = false;

    private static final long ANALYSIS_INTERVAL_MS = 10_000;

    private static long lastExceptionPrint = 0;

    private static boolean needsHintDraw = false;
    private static boolean needsAnalysis = false;

    private static LongList errors, warnings;
    private static long statusExpiration = 0;

    private static boolean wasInUse = false;

    private MMRenderer() {}

    /** Just loads the class */
    public static void init() {}

    private static void showHudText(String text) {
        AboveHotbarHUD
            .renderTextAboveHotbar(EnumChatFormatting.WHITE + text, (int) (ANALYSIS_INTERVAL_MS * 20 / 1000), true, false);
    }

    public static void markNeedsRedraw() {
        needsHintDraw = true;
    }

    public static void markNeedsReanalysis() {
        needsAnalysis = true;
    }

    public static void setStatusHints(LongList errors, LongList warnings) {
        needsHintDraw = true;
        MMRenderer.errors = errors;
        MMRenderer.warnings = warnings;

        if (GlobalMMConfig.RenderingConfig.statusExpiration <= 0) {
            statusExpiration = 0;
        } else {
            statusExpiration = System.currentTimeMillis() + GlobalMMConfig.RenderingConfig.statusExpiration * 1000;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void renderSelection(RenderWorldLastEvent event) {
        try {
            renderSelectionImpl(event);
        } catch (Throwable t) {
            MMMod.LOG.error("Could not render matter manipulator preview", t);

            long now = System.currentTimeMillis();
            if ((now - lastExceptionPrint) > 10_000) {
                Minecraft.getMinecraft().thePlayer.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.RED
                            + "Could not render preview due to a crash. Check the logs for more info. Building will not work - items may be voided if you try."
                    )
                );
                lastExceptionPrint = now;
            }
        }
    }

    /**
     * Renders the overlay.
     */
    private static void renderSelectionImpl(RenderWorldLastEvent event) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack held = player.getHeldItem();

        if (held != null && held.getItem() instanceof ItemMatterManipulator manipulator) {
            MMState state = ItemMatterManipulator.getState(held);

            switch (state.config.placeMode) {
                case GEOMETRY:
                case EXCHANGING:
                case CABLES: {
                    renderGeom(event, player, state, manipulator);
                    break;
                }
                case COPYING:
                case MOVING: {
                    renderRegions(event, player, state, manipulator);
                    break;
                }
            }

            if (hintPass != null) stepHints(player);
        } else {
            if (lastDrawer != null) {
                lastDrawer = null;
                clear(player);
            }
        }
    }

    private static void clear(EntityPlayer player) {
        lastAnalysisMS = 0;
        lastAnalyzedConfig = null;
        lastPlayerPosition = null;
        analysisCache = null;
        hintPass = null;

        needsHintDraw = false;
        needsAnalysis = false;

        RenderHints.INSTANCE.reset();

        AboveHotbarHUD.renderTextAboveHotbar("", 0, false, false);
    }

    @SubscribeEvent
    public static void checkPlayerStoppedBuilding(PlayerTickEvent event) {
        if (event.side != Side.CLIENT) return;
        if (event.phase != Phase.END) return;

        ItemStack inUse = event.player.getItemInUse();

        if (inUse != null) {
            if (inUse.getItem() instanceof ItemMatterManipulator) {
                wasInUse = true;
            }

            return;
        }

        if (wasInUse) {
            markNeedsReanalysis();
            wasInUse = false;
        }
    }

    private static void renderGeom(RenderWorldLastEvent event, EntityPlayer player, MMState state, ItemMatterManipulator manipulator) {
        Vector3i lookingAt = MMUtils.getLookingAtLocation(player);

        Location coordA = state.config.getCoordA(player.worldObj, lookingAt);
        Location coordB = state.config.getCoordB(player.worldObj, lookingAt);
        Location coordC = state.config.getCoordC(player.worldObj, lookingAt);

        if (state.config.placeMode == PlaceMode.CABLES) ItemMatterManipulator.moveCableCornersWithCoordA(state, coordA);

        state.config.coordA = coordA;
        state.config.coordB = coordB;
        state.config.coordC = coordC;

        boolean isAValid = coordA != null && coordA.isInWorld(player.worldObj);
        boolean isBValid = coordB != null && coordB.isInWorld(player.worldObj);
        boolean isCValid = coordC != null && coordC.isInWorld(player.worldObj);

        boolean isValid = isAValid && isBValid;

        List<Vector3i> cablePoints = null;

        // For cylinders, coord B must be pinned to one of the axis planes and coord C must be on the normal of that plane
        if (state.config.placeMode == PlaceMode.GEOMETRY && state.config.shape == Shape.CYLINDER) {
            isValid &= isCValid;

            if (isAValid && isBValid) {
                Objects.requireNonNull(coordA);
                Objects.requireNonNull(coordB);

                Vector3i b2 = MMState.pinToPlanes(coordA.toVec(), coordB.toVec());

                coordB.x = b2.x;
                coordB.y = b2.y;
                coordB.z = b2.z;

                if (isCValid) {
                    Objects.requireNonNull(coordC);
                    Vector3i height = MMState.pinToLine(coordA.toVec(), b2, coordC.toVec());

                    coordC.x = height.x;
                    coordC.y = height.y;
                    coordC.z = height.z;
                }
            }
        }

        if (!isValid && wasValid) {
            clear(player);
            wasValid = false;
            return;
        }

        wasValid = isValid;

        // For cables, coord B must be somewhere on one of the axes
        if (isAValid && isBValid && state.config.placeMode == PlaceMode.CABLES) {
            Objects.requireNonNull(coordA);
            Objects.requireNonNull(coordB);

            cablePoints = MMState.getCablePoints(coordA.toVec(), state.config.cableCorners, coordB.toVec());

            Vector3i b = cablePoints.get(cablePoints.size() - 1);

            coordB.x = b.x;
            coordB.y = b.y;
            coordB.z = b.z;
        }

        if (isAValid && state.config.coordAOffset != null) {
            GL11.glColor4f(0.15f, 0.6f, 0.75f, 0.75F);
            drawRulers(player, coordA, false, event.partialTicks);
        }

        if (isBValid && state.config.coordBOffset != null) {
            GL11.glColor4f(0.15f, 0.6f, 0.75f, 0.75F);
            drawRulers(player, coordB, false, event.partialTicks);
        }

        if (isCValid && state.config.coordCOffset != null) {
            GL11.glColor4f(0.15f, 0.6f, 0.75f, 0.75F);
            drawRulers(player, coordC, false, event.partialTicks);
        }

        if (isAValid && isBValid) {
            Objects.requireNonNull(coordA);
            Objects.requireNonNull(coordB);

            Location playerLocation = new Location(
                player.getEntityWorld(),
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY),
                MathHelper.floor_double(player.posZ)
            );

            Vector3i vA = coordA.toVec();
            Vector3i vB = coordB.toVec();
            Vector3i vC = null;

            VoxelAABB aabb = new VoxelAABB(vA, vB);

            // expand the AABB if the shape uses coord C
            if ((state.config.placeMode != PlaceMode.GEOMETRY || state.config.shape.requiresC()) && isCValid) {
                Objects.requireNonNull(coordC);
                vC = coordC.toVec();

                aabb.union(vC);
            }

            BoxRenderer.INSTANCE.start(event.partialTicks);

            if (cablePoints != null && cablePoints.size() > 2) {
                drawCablePath(cablePoints, new Vector3f(0.15f, 0.6f, 0.75f));

                for (Vector3i point : cablePoints) {
                    aabb.union(point);
                }
            } else {
                BoxRenderer.INSTANCE.drawAround(aabb.toBoundingBox(), new Vector3f(0.15f, 0.6f, 0.75f));
            }

            BoxRenderer.INSTANCE.finish();

            long now = System.currentTimeMillis();

            if (statusExpiration > 0 && now > statusExpiration) {
                errors = null;
                warnings = null;
                statusExpiration = 0;
                needsHintDraw = true;
            }

            needsAnalysis = needsAnalysis ||
                (now - lastAnalysisMS) >= ANALYSIS_INTERVAL_MS ||
                lastDrawer != manipulator ||
                !Objects.equals(lastAnalyzedConfig, state.config);

            needsHintDraw = needsHintDraw ||
                needsAnalysis ||
                (lastPlayerPosition.distanceTo(playerLocation) > 2 && manipulator.tier.maxRange != -1) ||
                lastPlayerPosition.distanceTo(playerLocation) > HINT_REFRESH_DISTANCE;

            boolean busy = RenderHints.INSTANCE.isBuilding() || hintPass != null;
            if (needsAnalysis && !busy) {
                lastAnalysisMS = now;
                lastAnalyzedConfig = state.config;
                analysisCache = analyze(state, manipulator, player);
                needsAnalysis = false;

                showHudText(aabb.describe());
            }

            if (needsHintDraw && !busy) {
                lastPlayerPosition = playerLocation;
                lastDrawer = manipulator;
                needsHintDraw = false;

                drawHints(event, state, player, playerLocation, manipulator.tier.maxRange);
            }
        }
    }

    private static void renderRegions(RenderWorldLastEvent event, EntityPlayer player, MMState state, ItemMatterManipulator manipulator) {
        Location sourceA = state.config.coordA;
        Location sourceB = state.config.coordB;
        Location paste = state.config.coordC;

        Vector3i lookingAt = MMUtils.getLookingAtLocation(player);

        if (state.config.action != null) {
            switch (state.config.action) {
                case MARK_COPY_A:
                case MARK_CUT_A: {
                    sourceA = new Location(player.worldObj, lookingAt);
                    GL11.glColor4f(0.15f, 0.6f, 0.75f, 0.75F);
                    drawRulers(player, sourceA, false, event.partialTicks);
                    break;
                }
                case MARK_COPY_B:
                case MARK_CUT_B: {
                    sourceB = new Location(player.worldObj, lookingAt);
                    GL11.glColor4f(0.15f, 0.6f, 0.75f, 0.75F);
                    drawRulers(player, sourceB, false, event.partialTicks);
                    break;
                }
                case MARK_PASTE: {
                    paste = new Location(player.worldObj, lookingAt);
                    GL11.glColor4f(0.75f, 0.5f, 0.15f, 0.75F);
                    drawRulers(player, paste, false, event.partialTicks);
                    break;
                }
                case MARK_ARRAY: {
                    GL11.glColor4f(0.4f, 0.75f, 0.15f, 0.75F);
                    drawRulers(player, new Location(player.worldObj, lookingAt), false, event.partialTicks);

                    if (paste != null && paste.isInWorld(player.worldObj)) {
                        state.config.arraySpan = state.config.getArrayMult(player.worldObj, sourceA, sourceB, paste, lookingAt);
                    }

                    break;
                }
                default: {
                    return;
                }
            }
        }

        state.config.coordA = sourceA;
        state.config.coordB = sourceB;
        state.config.coordC = paste;

        boolean isSourceAValid = sourceA != null && sourceA.isInWorld(player.worldObj);
        boolean isSourceBValid = sourceB != null && sourceB.isInWorld(player.worldObj);
        boolean isPasteValid = paste != null && paste.isInWorld(player.worldObj);

        boolean isValid = isSourceAValid && isSourceBValid && isPasteValid;

        if (!isValid && wasValid) {
            clear(player);
            wasValid = false;
            return;
        }

        wasValid = isValid;

        VoxelAABB copyDeltas = null;

        BoxRenderer.INSTANCE.start(event.partialTicks);

        try {
            if (isSourceAValid && isSourceBValid) {
                Objects.requireNonNull(sourceA);
                Objects.requireNonNull(sourceB);

                copyDeltas = new VoxelAABB(sourceA.toVec(), sourceB.toVec());

                BoxRenderer.INSTANCE
                    .drawAround(copyDeltas.toBoundingBox(), new Vector3f(0.15f, 0.6f, 0.75f));
            }

            VoxelAABB pasteDeltas = null;

            if (isPasteValid) {
                Objects.requireNonNull(paste);

                pasteDeltas = state.config.getPasteVisualDeltas(player.worldObj, state.config.placeMode == PlaceMode.COPYING);

                if (pasteDeltas == null) {
                    pasteDeltas = new VoxelAABB(paste.toVec(), paste.toVec());
                }

                BoxRenderer.INSTANCE.drawAround(pasteDeltas.toBoundingBox(), new Vector3f(0.75f, 0.5f, 0.15f));

                Location playerLocation = new Location(
                    player.getEntityWorld(),
                    MathHelper.floor_double(player.posX),
                    MathHelper.floor_double(player.posY),
                    MathHelper.floor_double(player.posZ)
                );

                long now = System.currentTimeMillis();

                needsAnalysis = needsAnalysis ||
                    (now - lastAnalysisMS) >= ANALYSIS_INTERVAL_MS ||
                    lastDrawer != manipulator ||
                    !Objects.equals(lastAnalyzedConfig, state.config);

                needsHintDraw = needsHintDraw ||
                    needsAnalysis ||
                    (lastPlayerPosition.distanceTo(playerLocation) > 2 && manipulator.tier.maxRange != -1) ||
                    lastPlayerPosition.distanceTo(playerLocation) > HINT_REFRESH_DISTANCE;

                boolean busy = RenderHints.INSTANCE.isBuilding() || hintPass != null;
                if (needsAnalysis && !busy) {
                    lastAnalysisMS = now;
                    lastAnalyzedConfig = state.config;
                    analysisCache = analyze(state, manipulator, player);
                    needsAnalysis = false;
                }

                if (needsHintDraw && !busy) {
                    lastPlayerPosition = playerLocation;
                    lastDrawer = manipulator;
                    needsHintDraw = false;

                    drawHints(event, state, player, playerLocation, manipulator.tier.maxRange);
                }
            }

            if (pasteDeltas != null) {
                String array = "";

                Vector3i span = state.config.arraySpan;
                if (span != null) {
                    array = String.format(
                        " stX=%d stY=%d stZ=%d",
                        span.x >= 0 ? span.x + 1 : span.x,
                        span.y >= 0 ? span.y + 1 : span.y,
                        span.z >= 0 ? span.z + 1 : span.z
                    );
                }

                showHudText(pasteDeltas.describe() + array);
            } else if (copyDeltas != null) {
                showHudText(copyDeltas.describe());
            }
        } finally {
            BoxRenderer.INSTANCE.finish();
        }
    }

    /** Computes the preview blocks. */
    private static List<PendingBlock> analyze(MMState state, ItemMatterManipulator manipulator, EntityPlayer player) {
        Profiler p = Minecraft.getMinecraft().mcProfiler;

        p.startSection("MM Analysis");
        List<PendingBlock> blocks = state.getPendingBlocks(manipulator.tier, player.getEntityWorld());
        blocks.removeIf(Objects::isNull);
        p.endSection();

        return blocks;
    }

    private static void drawHints(
        RenderWorldLastEvent event,
        MMState state,
        EntityPlayer player,
        Location playerLocation,
        int maxRange
    ) {
        RenderHints.INSTANCE.start();
        RenderHints.INSTANCE.setDepthTest(!RenderingConfig.hintsOnTop && state.config.placeMode != PlaceMode.EXCHANGING);

        hintPass = new HintPass(player.worldObj, analysisCache, playerLocation, maxRange);
    }

    /** Runs the hint pass for up to {@link #HINT_BUDGET_NANOS} this frame. */
    private static void stepHints(EntityPlayer player) {
        // a world change resets RenderHints
        if (hintPass.world != player.worldObj) {
            hintPass = null;
            needsHintDraw = true;
            return;
        }

        Minecraft.getMinecraft().mcProfiler.startSection("MM Hints");

        if (hintPass.step(System.nanoTime() + HINT_BUDGET_NANOS)) hintPass = null; // successful, null it

        Minecraft.getMinecraft().mcProfiler.endSection();
    }

    /** The hint pass in progress, or null. New analyses and passes wait for it. */
    private static HintPass hintPass;

    private static final long HINT_BUDGET_NANOS = 3_000_000; // 3ms

    /** Passes estimated to take longer than 5 frame budgets use the max hint distance. */
    private static final long DISTANCE_LIMIT_NANOS = 5 * HINT_BUDGET_NANOS;

    /** The previous pass's cost without the distance limit. */
    private static long estimatedFullPassNanos;

    /** How far the player can walk before the hints are redrawn. */
    private static final int HINT_REFRESH_DISTANCE = 16;

    /** Compares the preview blocks with the world and adds a hint for each one that differs, a slice per frame. */
    private static class HintPass {

        final World world;
        final List<PendingBlock> blocks;
        final Location playerLocation;
        final int maxRange, buildable;

        final BlockSpec pooled = new BlockSpec();
        final LongOpenHashSet errors = MMRenderer.errors == null ? null : new LongOpenHashSet(MMRenderer.errors);
        final LongOpenHashSet warnings = MMRenderer.warnings == null ? null : new LongOpenHashSet(MMRenderer.warnings);
        final Map<ImmutableBlockSpec, SpecInfo> specInfos = new IdentityHashMap<>();

        int next = 0, hints = 0;

        /// The distance limit only applies when a pass without it would take more than [#DISTANCE_LIMIT_NANOS]. Decided from
        /// the previous pass's estimate, to stop far hints from flickering.
        final boolean limitDistance = estimatedFullPassNanos > DISTANCE_LIMIT_NANOS;
        long workNanos;
        /// Blocks within the manipulator's range, and how many of them were compared with the world
        int candidates, compared;

        HintPass(World world, List<PendingBlock> blocks, Location playerLocation, int maxRange) {
            this.world = world;
            this.blocks = blocks;
            this.playerLocation = playerLocation;
            this.maxRange = maxRange;
            this.buildable = maxRange * maxRange;
        }

        /** @return True when the pass is done and the hints were handed to {@link RenderHints} */
        boolean step(long deadline) {
            long start = System.nanoTime();

            while (next < blocks.size()) {
                // we are over the deadline for this frame
                if ((next & 255) == 0 && System.nanoTime() > deadline) {
                    workNanos += System.nanoTime() - start;
                    return false;
                }

                PendingBlock pendingBlock = blocks.get(next++);

                if (!pendingBlock.isInWorld(world)) continue;

                int dist2 = pendingBlock.distanceTo2(playerLocation);

                if (maxRange != -1 && dist2 > buildable) continue;

                candidates++;

                int maxDistance = RenderingConfig.maxHintDistance;
                if (limitDistance && maxDistance > 0 && dist2 > maxDistance * maxDistance) continue;

                compared++;

                SpecInfo spec = specInfos.get(pendingBlock.spec);

                if (spec == null) {
                    spec = new SpecInfo(pendingBlock.spec);

                    // copied regions have a spec per block, which would make the cache useless and big
                    if (specInfos.size() < MAX_CACHED_SPECS) specInfos.put(pendingBlock.spec, spec);
                }

                if (spec.isAir && world.isAirBlock(pendingBlock.x, pendingBlock.y, pendingBlock.z)) continue;

                Block block = pendingBlock.getBlock();
                int meta = spec.blockMeta;

                Block previewBlock = pendingBlock.getPreviewBlock();
                if (previewBlock != null) {
                    block = previewBlock;
                    meta = pendingBlock.getPreviewMeta();
                } else if (pendingBlock.mp != null) {
                    block = Blocks.redstone_wire;
                    meta = 0;
                }

                if (block == null) continue;

                // air in the world never matches a spec with an item - skip the slow comparison
                boolean isEmptySpace = !spec.isAir && world.getBlock(pendingBlock.x, pendingBlock.y, pendingBlock.z) == Blocks.air && spec.hasStack();

                if (!isEmptySpace) {
                    BlockSpec.fromBlock(pooled, world, pendingBlock.x, pendingBlock.y, pendingBlock.z);

                    if (pendingBlock.mp != null) {
                        if (pooled.getBlock() == pendingBlock.getBlock()) continue;
                    } else {
                        if (pooled.isEquivalent(pendingBlock.spec)) continue;
                    }
                }

                if (++hints > RenderingConfig.maxHints) break;

                long packed = CoordinatePacker.pack(pendingBlock.x, pendingBlock.y, pendingBlock.z);

                short[] tint = WHITE;

                if (warnings != null && warnings.remove(packed)) {
                    tint = WARNING;
                }

                if (errors != null && errors.remove(packed)) {
                    tint = ERROR;
                }

                if (spec.isAir) {
                    RenderHints.INSTANCE.addHint(
                        pendingBlock.x,
                        pendingBlock.y,
                        pendingBlock.z,
                        StructureLibAPI.getBlockHint(),
                        StructureLibAPI.HINT_BLOCK_META_ERROR,
                        tint
                    );
                } else {
                    RenderHints.INSTANCE.addHint(
                        pendingBlock.x,
                        pendingBlock.y,
                        pendingBlock.z,
                        block,
                        meta,
                        tint
                    );
                }
            }

            if (warnings != null) {
                for (long packed : warnings) {
                    int x = CoordinatePacker.unpackX(packed);
                    int y = CoordinatePacker.unpackY(packed);
                    int z = CoordinatePacker.unpackZ(packed);

                    RenderHints.INSTANCE.addHint(x, y, z, StructureLibAPI.getBlockHint(), StructureLibAPI.HINT_BLOCK_META_AIR, WARNING);
                }
            }

            if (errors != null) {
                for (long packed : errors) {
                    int x = CoordinatePacker.unpackX(packed);
                    int y = CoordinatePacker.unpackY(packed);
                    int z = CoordinatePacker.unpackZ(packed);

                    RenderHints.INSTANCE.addHint(x, y, z, StructureLibAPI.getBlockHint(), StructureLibAPI.HINT_BLOCK_META_AIR, ERROR);
                }
            }

            RenderHints.INSTANCE.finish();

            // what this pass would have cost without the distance limit
            workNanos += System.nanoTime() - start;
            estimatedFullPassNanos = compared == 0 ? 0 : workNanos * candidates / compared;

            return true;
        }
    }

    private static final int MAX_CACHED_SPECS = 256;

    /** The values {@link #drawHints} needs from a spec (slow to get because each call copies item stacks) */
    private static class SpecInfo {

        private final ImmutableBlockSpec spec;
        public final boolean isAir;
        public final int blockMeta;
        private Boolean hasStack;

        public SpecInfo(ImmutableBlockSpec spec) {
            this.spec = spec;
            this.isAir = spec.isAir();
            this.blockMeta = spec.getBlockMeta();
        }

        public boolean hasStack() {
            if (hasStack == null) hasStack = spec.toStack(1) != null;

            return hasStack;
        }
    }

    private static final short[] WHITE = {
        229, 242, 255, 255
    };
    private static final short[] WARNING = {
        255, 170, 0, 255
    };
    private static final short[] ERROR = {
        255, 85, 85, 255
    };

    private static Vector3d getVecForDir(ForgeDirection dir) {
        return new Vector3d(dir.offsetX, dir.offsetY, dir.offsetZ);
    }

    private static final int RULER_LENGTH = 128;

    /**
     * Draws a bent cable path as one continuous shape.
     * Straight runs of voxels whose sides match are merged into a single box.
     */
    private static void drawCablePath(List<Vector3i> points, Vector3f colour) {
        List<Vector3i> path = MMState.getCablePath(points);
        Set<Vector3i> voxels = new HashSet<>(path);
        Set<Vector3i> drawn = new HashSet<>();

        Vector3i start = null, end = null;
        ForgeDirection dir = ForgeDirection.UNKNOWN;
        int startSides = 0, endSides = 0;

        for (Vector3i voxel : path) {
            // check if this is already part of the path, if yes then don't render it again
            if (!drawn.add(voxel)) {
                if (start != null) drawCableRun(start, end, dir, startSides, endSides, colour);

                start = null;
                continue;
            }

            int sides = getCableNeighbours(voxels, voxel);

            if (start != null) {
                ForgeDirection step = MMState.getAxisDirection(end, voxel);
                int axis = step.flag | step.getOpposite().flag;

                if ((dir == ForgeDirection.UNKNOWN || dir == step) && (startSides & ~axis) == (sides & ~axis)) {
                    end = voxel;
                    endSides = sides;
                    dir = step;
                    continue;
                }

                drawCableRun(start, end, dir, startSides, endSides, colour);
            }

            // this is the first voxel of a straight segment
            start = end = voxel;
            startSides = endSides = sides;
            dir = ForgeDirection.UNKNOWN;
        }

        if (start != null) drawCableRun(start, end, dir, startSides, endSides, colour);
    }

    /**
     * Draws a straight run of cable voxels as one box. The sides along the run are shared by every voxel, the back
     * side comes from the first voxel and the front side comes from the last voxel.
     */
    private static void drawCableRun(Vector3i start, Vector3i end, ForgeDirection dir, int startSides, int endSides, Vector3f colour) {
        int openSides = (startSides & ~dir.flag) | (endSides & ~dir.getOpposite().flag);

        BoxRenderer.INSTANCE.drawAround(new VoxelAABB(start, end).toBoundingBox(), colour, openSides);
    }

    /**
     * @return The {@link ForgeDirection} flags of the sides of a voxel that touch another voxel of the cable.
     */
    private static int getCableNeighbours(Set<Vector3i> voxels, Vector3i voxel) {
        int sides = 0;

        Vector3i neighbour = new Vector3i();

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            neighbour.set(voxel)
                .add(dir.offsetX, dir.offsetY, dir.offsetZ);

            if (voxels.contains(neighbour)) sides |= dir.flag;
        }

        return sides;
    }

    private static void drawRulers(EntityPlayer player, Location l, boolean fromSurface, float partialTickTime) {
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glLineWidth(2.0F);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDepthMask(false);

        GL11.glPointSize(4);

        OpenGlHelper.glBlendFunc(770, 771, 1, 0);

        GL11.glPushMatrix();

        double d0 = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double) partialTickTime;
        double d1 = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double) partialTickTime;
        double d2 = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double) partialTickTime;
        GL11.glTranslated(l.x - d0 + 0.5, l.y - d1 + 0.5, l.z - d2 + 0.5);

        Tessellator tessellator = Tessellator.instance;

        try {
            tessellator.startDrawing(GL11.GL_LINES);

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                Vector3d delta = getVecForDir(dir);

                if (fromSurface) {
                    tessellator.addVertex(delta.x * 0.5, delta.y * 0.5, delta.z * 0.5);
                } else {
                    tessellator.addVertex(0, 0, 0);
                }
                tessellator.addVertex(delta.x * RULER_LENGTH, delta.y * RULER_LENGTH, delta.z * RULER_LENGTH);
            }
        } finally {
            try {
                tessellator.draw();
            } catch (Throwable t) {
                t.printStackTrace();
            }

            GL11.glPopMatrix();

            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
        }
    }
}
