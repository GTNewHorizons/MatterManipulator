package com.recursive_pineapple.matter_manipulator.common.items.manipulator;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
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
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig.VoxelAABB;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PendingAction;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PlaceMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.Shape;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.opengl.GL11;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
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
    private static boolean copyAnalysisDeferred = false;
    private static boolean sourceWasAvailable = false;
    private static boolean sourceAvailability = false;
    private static long nextSourceCheckMS = 0;
    private static final long SOURCE_CHECK_INTERVAL_MS = 1_000;

    // Keep the last COMPLETE copy analysis across held-item changes. The ordinary render
    // cache is cleared when the manipulator is unequipped, but its source chunks might
    // be unloaded when the player equips it again near the paste destination.
    private static List<PendingBlock> cachedCopyBlocks;
    private static MMConfig cachedCopyConfig;
    private static World cachedCopyWorld;

    // Minecraft creates a new client World when joining a server, even if the
    // selected Copy/Paste coordinates still point to the same dimension.
    // Never reuse the previous connection's renderer state in the new World.
    private static World lastRenderWorld;

    // A saved item selection does not reactivate a snapshot on reconnect. Use the
    // original client-side preview until the player explicitly marks Copy again.
    private static boolean copySnapshotEnabled;
    private static boolean copySelectionInProgress;

    // Detect when the destination becomes available again without scanning the
    // entire Paste region on every frame.
    private static long nextPasteChunkCheckMS;
    private static int lastPasteChunkX = Integer.MIN_VALUE;
    private static int lastPasteChunkZ = Integer.MIN_VALUE;
    private static boolean lastPasteChunkLoaded;

    private static void rememberCopyAnalysis(MMState state, World world) {
        cachedCopyBlocks = analysisCache;
        cachedCopyConfig = state.clone().config;
        cachedCopyWorld = world;
    }

    private static boolean restoreCopyAnalysis(MMState state, World world) {
        if (!copySnapshotEnabled || cachedCopyBlocks == null || cachedCopyWorld != world || !Objects.equals(cachedCopyConfig, state.config)) return false;

        analysisCache = cachedCopyBlocks;
        return true;
    }

    /**
     * Drop only client-side preview data on a world change. The selection lives
     * in the MM item's NBT and is intentionally untouched. After reconnecting,
     * preview falls back to ordinary source reads; only a new Mark Copy enables
     * snapshot caching for the current client World.
     */
    private static void resetOnWorldChange(EntityPlayer player, World world) {
        if (lastRenderWorld == world) return;

        lastRenderWorld = world;
        clear(player);
        lastDrawer = null;
        wasValid = false;
        cachedCopyBlocks = null;
        cachedCopyConfig = null;
        cachedCopyWorld = null;
        copySnapshotEnabled = false;
        copySelectionInProgress = false;
        sourceWasAvailable = false;
        sourceAvailability = false;
        nextSourceCheckMS = 0;
        errors = null;
        warnings = null;
        statusExpiration = 0;
    }

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

    /** Checks client source availability without loading any chunks. */
    private static boolean areCopyChunksLoaded(World world, Location a, Location b) {
        if (a == null || b == null || !a.isInWorld(world) || !b.isInWorld(world)) return false;
        int minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x);
        int minZ = Math.min(a.z, b.z), maxZ = Math.max(a.z, b.z);
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (world.getChunkFromChunkCoords(cx, cz).isEmpty()) return false;
            }
        }
        return true;
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
        if (player == null || player.worldObj == null) return;

        resetOnWorldChange(player, player.worldObj);
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
        } else {
            if (lastDrawer != null) {
                lastDrawer = null;
                clear(player);
            }
        }
    }

    private static void clear(EntityPlayer player) {
        lastAnalysisMS = 0;
        // The render cache belongs to the currently held item. Clear it whenever
        // the preview is hidden, but NEVER discard the independent Copy snapshot:
        // it may be the only accurate source while Copy chunks are unloaded.
        lastAnalyzedConfig = null;
        analysisCache = null;
        lastPlayerPosition = null;
        copyAnalysisDeferred = false;
        // Keep sourceWasAvailable across hotbar switches. If the Copy chunks
        // become available again, their next availability check still triggers
        // the normal snapshot refresh (without rebuilding it just for re-equipping).
        nextSourceCheckMS = 0;
        nextPasteChunkCheckMS = 0;
        lastPasteChunkX = Integer.MIN_VALUE;
        lastPasteChunkZ = Integer.MIN_VALUE;
        lastPasteChunkLoaded = false;

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
                (lastPlayerPosition.distanceTo(playerLocation) > 2 && manipulator.tier.maxRange != -1);

            if (needsAnalysis) {
                lastAnalysisMS = now;
                lastAnalyzedConfig = state.config;
                analysisCache = state.getPendingBlocks(manipulator.tier, player.getEntityWorld());
                analysisCache.removeIf(Objects::isNull);
                analysisCache.sort(Comparator.comparingInt((PendingBlock b) -> b.renderOrder));
                needsAnalysis = false;

                showHudText(aabb.describe());
            }

            if (needsHintDraw) {
                lastPlayerPosition = playerLocation;
                lastDrawer = manipulator;
                needsHintDraw = false;

                drawHints(event, state, player, playerLocation, manipulator.tier.maxRange);
            }
        }
    }

    private static void renderRegions(RenderWorldLastEvent event, EntityPlayer player, MMState state, ItemMatterManipulator manipulator) {
        boolean markingCopy = state.config.placeMode == PlaceMode.COPYING &&
            (state.config.action == PendingAction.MARK_COPY_A || state.config.action == PendingAction.MARK_COPY_B);

        if (markingCopy && !copySelectionInProgress) {
            // This is a NEW Copy selection, not coordinates restored from item NBT.
            // Enable snapshots only now, and discard any previous source data.
            copySnapshotEnabled = true;
            cachedCopyBlocks = null;
            cachedCopyConfig = null;
            cachedCopyWorld = null;
            analysisCache = null;
            lastAnalyzedConfig = null;
            sourceWasAvailable = false;
            sourceAvailability = false;
            nextSourceCheckMS = 0;
            needsAnalysis = true;
            RenderHints.INSTANCE.reset();
        }
        copySelectionInProgress = markingCopy;

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

        // getPendingBlocks() lazily creates the default Copy transform. Normalize
        // it before comparing configs, otherwise an MM with a null NBT transform
        // invalidates its snapshot on every frame (null != default transform).
        if (state.config.placeMode == PlaceMode.COPYING) {
            state.getTransform();
        }

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

                // The source is read from the client world, which returns incomplete data
                // if the Copy selection is outside the client's loaded chunks.
                boolean copyMode = state.config.placeMode == PlaceMode.COPYING;
                // Avoid scanning a temporary region while Copy A/B are being marked.
                // Check availability at most once per second outside selection mode.
                if (copyMode && !markingCopy && (now >= nextSourceCheckMS || nextSourceCheckMS == 0)) {
                    sourceAvailability = areCopyChunksLoaded(player.worldObj, sourceA, sourceB);
                    nextSourceCheckMS = now + SOURCE_CHECK_INTERVAL_MS;
                }
                boolean sourceAvailable = !copyMode || (sourceAvailability && !markingCopy);
                boolean snapshotMode = copyMode && copySnapshotEnabled;
                boolean viewChanged = lastDrawer != manipulator;

                // Re-equipping the MM always starts with an empty render cache.
                // Only restore a snapshot if a NEW Copy was marked in this world,
                // before the normal reanalysis checks. Do not require object identity with the old
                // render list: other modes and clear() legitimately replace it.
                if (snapshotMode && !markingCopy && (viewChanged || analysisCache == null) && restoreCopyAnalysis(state, player.worldObj)) {
                    lastAnalyzedConfig = state.clone().config;
                    needsHintDraw = true;
                }

                boolean sourceReturned = copyMode && sourceAvailable && !sourceWasAvailable;
                boolean selectionChanged = !Objects.equals(lastAnalyzedConfig, state.config);
                boolean positionChanged = lastPlayerPosition == null || (lastPlayerPosition.distanceTo(playerLocation) > 2 && manipulator.tier.maxRange != -1);

                // A Paste chunk may finish loading while the player stands still.
                // Probe only the player's current chunk once per second; other
                // destination chunks are revisited by the normal timed refresh.
                if (now >= nextPasteChunkCheckMS) {
                    int cx = playerLocation.x >> 4;
                    int cz = playerLocation.z >> 4;
                    boolean loaded = !player.worldObj.getChunkFromChunkCoords(cx, cz).isEmpty();
                    if (cx != lastPasteChunkX || cz != lastPasteChunkZ || loaded != lastPasteChunkLoaded) {
                        needsHintDraw = true;
                    }
                    lastPasteChunkX = cx;
                    lastPasteChunkZ = cz;
                    lastPasteChunkLoaded = loaded;
                    nextPasteChunkCheckMS = now + SOURCE_CHECK_INTERVAL_MS;
                }

                // Snapshot mode reads Copy only when needed; the ten-second timer
                // updates Paste. Fallback mode rereads Copy normally when loaded.
                boolean refreshDue = (now - lastAnalysisMS) >= ANALYSIS_INTERVAL_MS;
                boolean reanalyze = needsAnalysis || selectionChanged ||
                    sourceReturned ||
                    (copyAnalysisDeferred && sourceAvailable) ||
                    (analysisCache == null && sourceAvailable)
                    // With snapshots disabled, use the normal periodic source read.
                    ||
                    (copyMode && !snapshotMode && refreshDue && sourceAvailable);
                if (viewChanged && analysisCache == null) {
                    // No valid snapshot exists: wait for source chunks rather
                    // than reading unloaded Copy data as air.
                    reanalyze = sourceAvailable;
                }

                if (reanalyze) {
                    if (!sourceAvailable) {
                        copyAnalysisDeferred = true;
                        if (!snapshotMode || !restoreCopyAnalysis(state, player.worldObj)) {
                            analysisCache = null;
                            RenderHints.INSTANCE.reset();
                        }
                        // Do not mark this as a fresh analysis: no world blocks were read.
                        // A later render can retry immediately when the chunks arrive.
                    } else {
                        List<PendingBlock> fresh = state.getPendingBlocks(manipulator.tier, player.getEntityWorld());
                        if (fresh != null) {
                            fresh.removeIf(Objects::isNull);
                            fresh.sort(Comparator.comparingInt((PendingBlock block) -> block.renderOrder));
                            analysisCache = fresh;
                            if (snapshotMode) rememberCopyAnalysis(state, player.worldObj);
                        }
                        lastAnalysisMS = now;
                        copyAnalysisDeferred = false;
                    }
                    lastAnalyzedConfig = state.clone().config;
                    needsAnalysis = false;
                    needsHintDraw = true;
                }
                sourceWasAvailable = sourceAvailable;
                // In fallback mode there is NO Copy snapshot to show after the
                // source unloads. Hide stale hints until the source is available.
                if (copyMode && !snapshotMode && !sourceAvailable && analysisCache != null) {
                    analysisCache = null;
                    RenderHints.INSTANCE.reset();
                    needsHintDraw = false;
                }
                // Recheck destination blocks only when the renderer is visible. This
                // never touches Copy and never forces chunks to load.
                if (refreshDue) {
                    lastAnalysisMS = now;
                    needsHintDraw = true;
                }

                needsHintDraw = needsHintDraw || positionChanged;
                // Never try to draw stale hints from a different configuration.
                if (analysisCache == null) {
                    RenderHints.INSTANCE.reset();
                    needsHintDraw = false;
                    lastDrawer = manipulator;
                    lastPlayerPosition = playerLocation;
                }

                if (needsHintDraw) {
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

    private static void drawHints(
        RenderWorldLastEvent event,
        MMState state,
        EntityPlayer player,
        Location playerLocation,
        int maxRange
    ) {
        int buildable = maxRange * maxRange;

        int i = 0;

        BlockSpec pooled = new BlockSpec();

        LongOpenHashSet errors = MMRenderer.errors == null ? null : new LongOpenHashSet(MMRenderer.errors);
        LongOpenHashSet warnings = MMRenderer.warnings == null ? null : new LongOpenHashSet(MMRenderer.warnings);

        World world = player.worldObj;
        // One chunk availability lookup per destination chunk, not per block.
        Long2BooleanOpenHashMap loadedPasteChunks = new Long2BooleanOpenHashMap();

        RenderHints.INSTANCE.start();
        RenderHints.INSTANCE.setDepthTest(!RenderingConfig.hintsOnTop && state.config.placeMode != PlaceMode.EXCHANGING);

        for (PendingBlock pendingBlock : analysisCache) {
            if (!pendingBlock.isInWorld(world)) continue;

            if (maxRange != -1) {
                int dist2 = pendingBlock.distanceTo2(playerLocation);

                if (dist2 > buildable) continue;
            }

            // Never compare an unloaded destination against the expected block: the
            // client reports incomplete world state and can generate phantom X hints.
            int chunkX = pendingBlock.x >> 4;
            int chunkZ = pendingBlock.z >> 4;
            long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
            if (!loadedPasteChunks.containsKey(chunkKey)) {
                loadedPasteChunks.put(chunkKey, !world.getChunkFromChunkCoords(chunkX, chunkZ).isEmpty());
            }
            if (!loadedPasteChunks.get(chunkKey)) continue;

            if (pendingBlock.spec.isAir() && world.isAirBlock(pendingBlock.x, pendingBlock.y, pendingBlock.z)) continue;

            Block block = pendingBlock.getBlock();
            int meta = pendingBlock.spec.getBlockMeta();

            Block previewBlock = pendingBlock.getPreviewBlock();
            if (previewBlock != null) {
                block = previewBlock;
                meta = pendingBlock.getPreviewMeta();
            } else if (pendingBlock.mp != null) {
                block = Blocks.redstone_wire;
                meta = 0;
            }

            if (block == null) continue;

            BlockSpec.fromBlock(pooled, world, pendingBlock.x, pendingBlock.y, pendingBlock.z);

            if (pendingBlock.mp != null) {
                if (pooled.getBlock() == pendingBlock.getBlock()) continue;
            } else {
                if (pooled.isEquivalent(pendingBlock.spec)) continue;
            }

            if (++i > RenderingConfig.maxHints) break;

            long packed = CoordinatePacker.pack(pendingBlock.x, pendingBlock.y, pendingBlock.z);

            short[] tint = WHITE;

            if (warnings != null && warnings.remove(packed)) {
                tint = WARNING;
            }

            if (errors != null && errors.remove(packed)) {
                tint = ERROR;
            }

            if (pendingBlock.spec.isAir()) {
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
