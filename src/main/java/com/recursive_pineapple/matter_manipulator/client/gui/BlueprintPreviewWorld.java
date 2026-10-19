package com.recursive_pineapple.matter_manipulator.client.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldInfo;

import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import gregtech.api.GregTechAPI;

import com.mojang.authlib.GameProfile;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.blueprint.StructureBlueprint;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.RegionAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Detached, in-memory world used only to render a blueprint preview. Blocks are placed and configured through the same
 * ItemBlock.placeBlockAt + PendingBlock.apply path as a real build, so tile-backed blocks (GT machines, cables, AE
 * parts, ...) get real tile entities to render from. It never ticks, never touches the client or server world, has no
 * neighbor updates and reports full brightness everywhere.
 */
@SideOnly(Side.CLIENT)
final class BlueprintPreviewWorld extends World {

    /** Lowest Y used for the structure, so most mods' 0..255 height checks pass. */
    private static final int BASE_Y = 64;
    private static boolean registeredWithGT;

    static final class Entry {

        final int x, y, z;
        final Block block;
        int meta;
        TileEntity tile;

        Entry(int x, int y, int z, Block block, int meta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
        }
    }

    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<PendingBlock> sources = new Long2ObjectOpenHashMap<>();
    private final List<PendingBlock> pending = new ArrayList<>();
    private final ApplyContext context = new ApplyContext();
    private EntityPlayer placer;
    private int placed, applied, failures;

    int minX, minY, minZ, maxX, maxY, maxZ;
    /** Blocks above this Y read as air, so a layer cut renders its exposed top faces. */
    private int visibleMaxY = Integer.MAX_VALUE;

    BlueprintPreviewWorld(StructureBlueprint blueprint) {
        super(new NullSaveHandler(), "MM_BLUEPRINT_PREVIEW", new WorldProviderSurface(), settings(), new Profiler());
        provider.worldObj = this;
        provider.terrainType = WorldType.FLAT;
        Arrays.fill(provider.lightBrightnessTable, 1f);
        mapStorage = new MapStorage(null);
        chunkProvider = createChunkProvider();
        if (Mods.GregTech.isModLoaded()) registerDummyWorld();

        RegionAnalysis analysis = blueprint.instantiate(this);
        int lowX = Integer.MAX_VALUE, lowY = Integer.MAX_VALUE, lowZ = Integer.MAX_VALUE;
        for (PendingBlock block : analysis.blocks) {
            if (block == null || block.spec == null || block.spec.isAir() || block.getBlock() == null) continue;
            pending.add(block);
            lowX = Math.min(lowX, block.x);
            lowY = Math.min(lowY, block.y);
            lowZ = Math.min(lowZ, block.z);
        }
        pending.sort((a, b) -> Integer.compare(a.buildOrder, b.buildOrder));

        minX = minY = minZ = Integer.MAX_VALUE;
        maxX = maxY = maxZ = Integer.MIN_VALUE;
        for (PendingBlock block : pending) {
            block.x -= lowX;
            block.y += BASE_Y - lowY;
            block.z -= lowZ;
            minX = Math.min(minX, block.x);
            minY = Math.min(minY, block.y);
            minZ = Math.min(minZ, block.z);
            maxX = Math.max(maxX, block.x);
            maxY = Math.max(maxY, block.y);
            maxZ = Math.max(maxZ, block.z);
            sources.put(BlueprintPreviewGeometry.pack(block.x, block.y, block.z), block);
        }
        if (pending.isEmpty()) {
            minX = minZ = maxX = maxZ = 0;
            minY = maxY = BASE_Y;
        }
    }

    /** Keeps GT's asynchronous machine-block updates away from this detached, single-threaded world. */
    @Optional(Names.GREG_TECH_NH)
    private static void registerDummyWorld() {
        if (registeredWithGT) return;
        registeredWithGT = true;
        GregTechAPI.addDummyWorld(BlueprintPreviewWorld.class);
    }

    private static WorldSettings settings() {
        return new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    int blockCount() {
        return pending.size();
    }

    int failures() {
        return failures;
    }

    /** 0..1 over both the placement and the configuration passes. */
    float progress() {
        return pending.isEmpty() ? 1 : (placed + applied) / (2f * pending.size());
    }

    boolean isBuilt() {
        return applied >= pending.size();
    }

    /**
     * Places, then configures, as many blocks as fit in the time budget. All blocks are placed before any is
     * configured, because connections and covers look at neighboring tiles.
     */
    boolean build(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        if (placer == null) placer = createPlacer();
        while (placed < pending.size()) {
            place(pending.get(placed++));
            if (System.nanoTime() > deadline) return false;
        }
        while (applied < pending.size()) {
            configure(pending.get(applied++));
            if (System.nanoTime() > deadline) return isBuilt();
        }
        return true;
    }

    private EntityPlayer createPlacer() {
        GameProfile profile = Minecraft.getMinecraft().thePlayer.getGameProfile();
        // Entity construction events see a client-side player, so no mod registers server-side player data for it.
        isRemote = true;
        try {
            EntityPlayer player = new EntityOtherPlayerMP(this, profile);
            player.capabilities.isCreativeMode = true;
            return player;
        } finally {
            isRemote = false;
        }
    }

    private void place(PendingBlock block) {
        Block type = block.getBlock();
        int meta = block.spec.getBlockMeta();
        try {
            ItemStack stack = block.getStack();
            if (stack != null && stack.getItem() instanceof ItemBlock itemBlock && Block.getBlockFromItem(itemBlock) == type) {
                itemBlock.placeBlockAt(stack, placer, this, block.x, block.y, block.z, placeSide(block).ordinal(), 0, 0, 0, meta);
            } else if (setBlock(block.x, block.y, block.z, type, meta, 3)) {
                type.onBlockPlacedBy(this, block.x, block.y, block.z, placer, stack);
                type.onPostBlockPlaced(this, block.x, block.y, block.z, meta);
            }
        } catch (Throwable t) {
            fail(block, "place", t);
        }
        Entry entry = entries.get(BlueprintPreviewGeometry.pack(block.x, block.y, block.z));
        // Keep at least the bare block visible when its item placement logic refuses this world.
        if (entry == null || entry.block != type) setBlock(block.x, block.y, block.z, type, meta, 3);
    }

    private static ForgeDirection placeSide(PendingBlock block) {
        // Mirrors PendingBuild: GT cables must not auto-connect toward the clicked side.
        if (Mods.GregTech.isModLoaded() && MMUtils.isGTCable(block.spec)) return ForgeDirection.UNKNOWN;
        return ForgeDirection.NORTH;
    }

    private void configure(PendingBlock block) {
        context.block = block;
        try {
            block.apply(context, this);
        } catch (Throwable t) {
            fail(block, "configure", t);
        }
    }

    private void fail(PendingBlock block, String phase, Throwable t) {
        if (failures++ < 5) {
            MMMod.LOG.warn("Blueprint preview could not {} {} at {} {} {}", phase, block.spec, block.x, block.y, block.z, t);
        }
    }

    Entry entry(int x, int y, int z) {
        return y > visibleMaxY ? null : entries.get(BlueprintPreviewGeometry.pack(x, y, z));
    }

    /** The blueprint block captured at a position, regardless of the layer cut. */
    PendingBlock source(int x, int y, int z) {
        return sources.get(BlueprintPreviewGeometry.pack(x, y, z));
    }

    Iterable<Entry> entries() {
        return entries.values();
    }

    void setVisibleMaxY(int y) {
        visibleMaxY = y;
    }

    // ---- World storage ----

    @Override
    public Block getBlock(int x, int y, int z) {
        Entry entry = entry(x, y, z);
        return entry == null ? Blocks.air : entry.block;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        Entry entry = entry(x, y, z);
        return entry == null ? 0 : entry.meta;
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        Entry entry = entry(x, y, z);
        return entry == null ? null : entry.tile;
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        Entry entry = entry(x, y, z);
        return entry == null || entry.block.isAir(this, x, y, z);
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
        long key = BlueprintPreviewGeometry.pack(x, y, z);
        Entry old = entries.get(key);
        if (block == null || block == Blocks.air) {
            entries.remove(key);
            return old != null;
        }
        Entry entry = new Entry(x, y, z, block, meta);
        entries.put(key, entry);
        if (block.hasTileEntity(meta)) {
            TileEntity tile = block.createTileEntity(this, meta);
            if (tile != null) setTileEntity(x, y, z, tile);
        }
        return true;
    }

    @Override
    public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
        Entry entry = entries.get(BlueprintPreviewGeometry.pack(x, y, z));
        if (entry == null) return false;
        entry.meta = meta;
        if (entry.tile != null) entry.tile.updateContainingBlockInfo();
        return true;
    }

    @Override
    public void setTileEntity(int x, int y, int z, TileEntity tile) {
        Entry entry = entries.get(BlueprintPreviewGeometry.pack(x, y, z));
        if (entry == null || tile == null) return;
        tile.setWorldObj(this);
        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        tile.validate();
        entry.tile = tile;
    }

    @Override
    public void removeTileEntity(int x, int y, int z) {
        Entry entry = entries.get(BlueprintPreviewGeometry.pack(x, y, z));
        if (entry != null) entry.tile = null;
    }

    // ---- No simulation: the snapshot is shown exactly as captured ----

    @Override
    public void notifyBlocksOfNeighborChange(int x, int y, int z, Block block) {}

    @Override
    public void notifyBlocksOfNeighborChange(int x, int y, int z, Block block, int side) {}

    @Override
    public void notifyBlockOfNeighborChange(int x, int y, int z, Block block) {}

    @Override
    public void notifyBlockChange(int x, int y, int z, Block block) {}

    @Override
    public void func_147453_f(int x, int y, int z, Block block) {}

    @Override
    public void markBlockForUpdate(int x, int y, int z) {}

    @Override
    public void func_147479_m(int x, int y, int z) {}

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}

    @Override
    public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}

    @Override
    public void scheduleBlockUpdate(int x, int y, int z, Block block, int delay) {}

    @Override
    public boolean func_147451_t(int x, int y, int z) {
        return false;
    }

    @Override
    public boolean updateLightByType(EnumSkyBlock type, int x, int y, int z) {
        return false;
    }

    @Override
    public boolean spawnEntityInWorld(Entity entity) {
        return false;
    }

    @Override
    public void playSoundEffect(double x, double y, double z, String sound, float volume, float pitch) {}

    @Override
    public void playSound(double x, double y, double z, String sound, float volume, float pitch, boolean distanceDelay) {}

    @Override
    public void playSoundAtEntity(Entity entity, String sound, float volume, float pitch) {}

    @Override
    public void spawnParticle(String particle, double x, double y, double z, double vx, double vy, double vz) {}

    // ---- Constant full brightness ----

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int minLight) {
        return 15 << 20 | 15 << 4;
    }

    @Override
    public float getLightBrightness(int x, int y, int z) {
        return 1f;
    }

    @Override
    public int getSavedLightValue(EnumSkyBlock type, int x, int y, int z) {
        return 15;
    }

    @Override
    public int getSkyBlockTypeBrightness(EnumSkyBlock type, int x, int y, int z) {
        return 15;
    }

    @Override
    public int getBlockLightValue(int x, int y, int z) {
        return 15;
    }

    @Override
    public int getBlockLightValue_do(int x, int y, int z, boolean useNeighbors) {
        return 15;
    }

    @Override
    public int getFullBlockLightValue(int x, int y, int z) {
        return 15;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return BiomeGenBase.plains;
    }

    @Override
    public boolean extendedLevelsInChunkCache() {
        return false;
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        // Only ever hands out empty chunks; storage above never goes through chunks.
        return new ChunkProviderClient(this);
    }

    @Override
    public Entity getEntityByID(int id) {
        return null;
    }

    @Override
    protected int func_152379_p() {
        return 0;
    }

    /** Configuration-only context: unlimited free items, no power, no player inventory and no chat. */
    private final class ApplyContext implements IBlockApplyContext {

        private final MMConfig config = new MMConfig();
        private PendingBlock block;

        @Override
        public World getWorld() {
            return BlueprintPreviewWorld.this;
        }

        @Override
        public int getX() {
            return block.x;
        }

        @Override
        public int getY() {
            return block.y;
        }

        @Override
        public int getZ() {
            return block.z;
        }

        @Override
        public TileEntity getTileEntity() {
            return BlueprintPreviewWorld.this.getTileEntity(block.x, block.y, block.z);
        }

        @Override
        public EntityPlayer getRealPlayer() {
            return placer;
        }

        @Override
        public MMConfig getConfig() {
            return config;
        }

        @Override
        public boolean tryApplyAction(double complexity) {
            return true;
        }

        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            List<BigItemStack> extracted = new ArrayList<>(items.size());
            for (BigItemStack item : items)
                extracted.add(item.copy());
            return BooleanObjectImmutablePair.of(true, extracted);
        }

        @Override
        public void givePlayerItems(List<BigItemStack> items) {}

        @Override
        public void givePlayerFluids(List<BigFluidStack> fluids) {}

        @Override
        public void warn(IChatComponent message) {}

        @Override
        public void error(IChatComponent message) {}
    }

    private static final class NullSaveHandler implements ISaveHandler {

        @Override
        public WorldInfo loadWorldInfo() {
            return null;
        }

        @Override
        public void checkSessionLock() {}

        @Override
        public IChunkLoader getChunkLoader(WorldProvider provider) {
            return null;
        }

        @Override
        public void saveWorldInfoWithPlayer(WorldInfo info, NBTTagCompound player) {}

        @Override
        public void saveWorldInfo(WorldInfo info) {}

        @Override
        public IPlayerFileData getSaveHandler() {
            return null;
        }

        @Override
        public void flush() {}

        @Override
        public File getWorldDirectory() {
            return null;
        }

        @Override
        public File getMapFileFromName(String name) {
            return null;
        }

        @Override
        public String getWorldDirectoryName() {
            return null;
        }
    }
}
