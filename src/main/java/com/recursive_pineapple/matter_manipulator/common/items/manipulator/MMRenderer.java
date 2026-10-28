package com.recursive_pineapple.matter_manipulator.common.items.manipulator;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.RegionAnalysis;
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
    private static final long CHUNK_CHECK_INTERVAL_MS = 1_000;

    private static long lastExceptionPrint = 0;

    private static boolean needsHintDraw = false;
    private static boolean needsAnalysis = false;

    private static LongList errors, warnings;
    private static long statusExpiration = 0;

    private static boolean wasInUse = false;

    private static boolean sourceWasAvailable = false;
    private static boolean copyChunksLoaded = false;
    private static long nextSourceCheckMS = 0;

    // Keep the source relative to Copy A; the rendered blocks depend on Paste.
    private static RegionAnalysis cachedCopySource;
    private static MMConfig cachedCopyConfig;
    private static World cachedCopyWorld;
    private static boolean cachedSmartCopy;

    private static World lastRenderWorld;

    private static boolean copySnapshotEnabled;
    private static boolean copySelectionInProgress;

    private static long nextPasteChunkCheckMS;
    private static int lastPasteChunkX = Integer.MIN_VALUE;
    private static int lastPasteChunkZ = Integer.MIN_VALUE;
    private static boolean lastPasteChunkLoaded;

    private static void clearCopySnapshot() {
        cachedCopySource = null;
        cachedCopyConfig = null;
        cachedCopyWorld = null;
    }

    private static boolean hasCopySnapshot(MMState state, World world) {
        if (!copySnapshotEnabled || cachedCopySource == null || cachedCopyConfig == null || cachedCopyWorld != world) return false;

        MMConfig config = state.config;
        return config.placeMode == PlaceMode.COPYING && Objects.equals(cachedCopyConfig.coordA, config.coordA) &&
            Objects.equals(cachedCopyConfig.coordB, config.coordB) &&
            cachedCopyConfig.replaceCribsWithProxies == config.replaceCribsWithProxies &&
            cachedCopyConfig.interfaceCopyMode == config.interfaceCopyMode &&
            cachedCopyConfig.p2pInterfaceCopyMode == config.p2pInterfaceCopyMode &&
            cachedSmartCopy == state.hasCap(ItemMatterManipulator.ALLOW_SMART_COPY);
    }

    private static void rememberCopySource(MMState state, World world) {
        RegionAnalysis source = state.getCopySource(world);
        if (source == null) return;

        cachedCopySource = source;
        cachedCopyConfig = state.clone().config;
        cachedCopyWorld = world;
        cachedSmartCopy = state.hasCap(ItemMatterManipulator.ALLOW_SMART_COPY);
    }

    private static void resetOnWorldChange(EntityPlayer player, World world) {
        if (lastRenderWorld == world) return;

        lastRenderWorld = world;
        clear(player);
        lastDrawer = null;
        wasValid = false;
        clearCopySnapshot();
        // Coordinates survive reconnects in item NBT, but the client snapshot does not.
        copySnapshotEnabled = false;
        copySelectionInProgress = false;
        sourceWasAvailable = false;
        copyChunksLoaded = false;
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

    private static boolean isChunkLoaded(World world, int chunkX, int chunkZ) {
        return !world.getChunkFromChunkCoords(chunkX, chunkZ).isEmpty();
    }

    private static boolean areCopyChunksLoaded(World world, Location a, Location b) {
        if (a == null || b == null || !a.isInWorld(world) || !b.isInWorld(world)) return false;

        int minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x);
        int minZ = Math.min(a.z, b.z), maxZ = Math.max(a.z, b.z);
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!isChunkLoaded(world, cx, cz)) return false;
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
        lastAnalyzedConfig = null;
        analysisCache = null;
        lastPlayerPosition = null;
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

        state.config.coordA = coordA;
        state.config.coordB = coordB;
        state.config.coordC = coordC;

        boolean isAValid = coordA != null && coordA.isInWorld(player.worldObj);
        boolean isBValid = coordB != null && coordB.isInWorld(player.worldObj);
        boolean isCValid = coordC != null && coordC.isInWorld(player.worldObj);

        boolean isValid = isAValid && isBValid;

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

            Vector3i b = MMState.pinToAxes(coordA.toVec(), coordB.toVec());

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

            BoxRenderer.INSTANCE.drawAround(aabb.toBoundingBox(), new Vector3f(0.15f, 0.6f, 0.75f));

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
            copySnapshotEnabled = true;
            clearCopySnapshot();
            analysisCache = null;
            lastAnalyzedConfig = null;
            sourceWasAvailable = false;
            copyChunksLoaded = false;
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

        // getPendingBlocks() creates the default transform lazily.
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

            long now = System.currentTimeMillis();
            boolean copyMode = state.config.placeMode == PlaceMode.COPYING;
            boolean snapshotMode = copyMode && copySnapshotEnabled;

            if (copyMode && !markingCopy && now >= nextSourceCheckMS) {
                copyChunksLoaded = areCopyChunksLoaded(player.worldObj, sourceA, sourceB);
                nextSourceCheckMS = now + CHUNK_CHECK_INTERVAL_MS;
            }

            boolean sourceAvailable = !copyMode || (copyChunksLoaded && !markingCopy);
            boolean sourceReturned = copyMode && sourceAvailable && !sourceWasAvailable;

            if (snapshotMode && sourceAvailable && (sourceReturned || !hasCopySnapshot(state, player.worldObj) || (needsAnalysis && isPasteValid))) {
                rememberCopySource(state, player.worldObj);
            }
            sourceWasAvailable = sourceAvailable;

            boolean snapshotAvailable = snapshotMode && hasCopySnapshot(state, player.worldObj);

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

                boolean viewChanged = lastDrawer != manipulator;
                boolean selectionChanged = !Objects.equals(lastAnalyzedConfig, state.config);
                boolean positionChanged = lastPlayerPosition == null || (lastPlayerPosition.distanceTo(playerLocation) > 2 && manipulator.tier.maxRange != -1);

                if (now >= nextPasteChunkCheckMS) {
                    int cx = playerLocation.x >> 4;
                    int cz = playerLocation.z >> 4;
                    boolean loaded = isChunkLoaded(player.worldObj, cx, cz);
                    if (cx != lastPasteChunkX || cz != lastPasteChunkZ || loaded != lastPasteChunkLoaded) {
                        needsHintDraw = true;
                    }
                    lastPasteChunkX = cx;
                    lastPasteChunkZ = cz;
                    lastPasteChunkLoaded = loaded;
                    nextPasteChunkCheckMS = now + CHUNK_CHECK_INTERVAL_MS;
                }

                boolean refreshDue = (now - lastAnalysisMS) >= ANALYSIS_INTERVAL_MS;
                boolean canAnalyze = sourceAvailable || snapshotAvailable;
                boolean reanalyze = needsAnalysis || selectionChanged ||
                    viewChanged ||
                    sourceReturned ||
                    (analysisCache == null && canAnalyze) ||
                    (!snapshotAvailable && refreshDue && sourceAvailable);

                if (reanalyze) {
                    if (!canAnalyze) {
                        analysisCache = null;
                        RenderHints.INSTANCE.reset();
                    } else {
                        List<PendingBlock> fresh = snapshotAvailable ?
                            state.getCopyPreview(cachedCopySource) :
                            state.getPendingBlocks(manipulator.tier, player.getEntityWorld());
                        if (fresh != null) {
                            fresh.removeIf(Objects::isNull);
                            fresh.sort(Comparator.comparingInt((PendingBlock block) -> block.renderOrder));
                            analysisCache = fresh;
                        }
                        lastAnalysisMS = now;
                    }
                    lastAnalyzedConfig = state.clone().config;
                    needsAnalysis = false;
                    needsHintDraw = true;
                }

                if (copyMode && !canAnalyze && analysisCache != null) {
                    analysisCache = null;
                    RenderHints.INSTANCE.reset();
                    needsHintDraw = false;
                }

                // Destination blocks can change while the Copy snapshot stays valid.
                if (refreshDue) {
                    lastAnalysisMS = now;
                    needsHintDraw = true;
                }

                needsHintDraw = needsHintDraw || positionChanged;
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
        // Check each destination chunk only once per redraw.
        Long2BooleanOpenHashMap loadedPasteChunks = new Long2BooleanOpenHashMap();

        RenderHints.INSTANCE.start();
        RenderHints.INSTANCE.setDepthTest(!RenderingConfig.hintsOnTop && state.config.placeMode != PlaceMode.EXCHANGING);

        for (PendingBlock pendingBlock : analysisCache) {
            if (!pendingBlock.isInWorld(world)) continue;

            if (maxRange != -1) {
                int dist2 = pendingBlock.distanceTo2(playerLocation);

                if (dist2 > buildable) continue;
            }

            int chunkX = pendingBlock.x >> 4;
            int chunkZ = pendingBlock.z >> 4;
            long chunkKey = (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
            if (!loadedPasteChunks.containsKey(chunkKey)) {
                loadedPasteChunks.put(chunkKey, isChunkLoaded(world, chunkX, chunkZ));
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
