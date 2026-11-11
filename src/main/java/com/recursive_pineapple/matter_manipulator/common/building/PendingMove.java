package com.recursive_pineapple.matter_manipulator.common.building;

import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendErrorToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendInfoToPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBasePressurePlate;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockSign;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.GlobalMMConfig.BuildingConfig;
import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.movers.BlockMover;
import com.recursive_pineapple.matter_manipulator.common.building.movers.BlockMovers;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator.ManipulatorTier;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.networking.SoundResource;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;
import com.recursive_pineapple.matter_manipulator.mixin.BlockCaptureDrops;

import WayofTime.alchemicalWizardry.api.event.TeleposeEvent;
import it.unimi.dsi.fastutil.Pair;

/**
 * Handles all moving logic.
 */
public class PendingMove extends AbstractBuildable {

    private Deque<Pair<Location, Location>> moves = null;
    private final long airFilterBudgetNanos;
    private int airFilterRemaining;
    /** How many moves haven't been looked at yet. Attached blocks wait until every other block went through. */
    private int firstPassRemaining = -1;

    private int moveOffsetX, moveOffsetY, moveOffsetZ;
    private int srcMinX, srcMinY, srcMinZ, srcMaxX, srcMaxY, srcMaxZ;

    /** State that block movers carry from remove() to place(), keyed by the removed block's state object. */
    private final Map<Object, Object> moverState = new IdentityHashMap<>();

    public PendingMove(EntityPlayer player, MMState state, ManipulatorTier tier) {
        super(player, state, tier);
        this.airFilterBudgetNanos = BuildingConfig.airFilterBudgetMicros * 1_000L;
    }

    public int getMoveOffsetX() {
        return moveOffsetX;
    }

    public int getMoveOffsetY() {
        return moveOffsetY;
    }

    public int getMoveOffsetZ() {
        return moveOffsetZ;
    }

    public int getSrcMinX() {
        return srcMinX;
    }

    public int getSrcMinY() {
        return srcMinY;
    }

    public int getSrcMinZ() {
        return srcMinZ;
    }

    public int getSrcMaxX() {
        return srcMaxX;
    }

    public int getSrcMaxY() {
        return srcMaxY;
    }

    public int getSrcMaxZ() {
        return srcMaxZ;
    }

    public boolean isInSourceRegion(int x, int y, int z) {
        return x >= srcMinX && x <= srcMaxX && y >= srcMinY && y <= srcMaxY && z >= srcMinZ && z <= srcMaxZ;
    }

    public Map<Object, Object> getMoverState() {
        return moverState;
    }

    @Override
    public void tryPlaceBlocks(ItemStack stack, EntityPlayer player) {
        resetWarnings();
        refillPower(stack);

        if (moves == null) {
            initMoves();
            airFilterRemaining = airFilterBudgetNanos > 0 ? moves.size() : 0;
        }

        World world = player.worldObj;
        filterSourceAir(world);
        if (airFilterRemaining > 0) {
            sendInfoToPlayer(player, "mm.info.process_move", 0, moves.size());
            return;
        }

        if (firstPassRemaining < 0) firstPassRemaining = moves.size();

        BlockSpec source = new BlockSpec();
        BlockSpec target = new BlockSpec();

        int ops = 0;
        for (int attempts = Math.min(tier.placeSpeed, moves.size()); attempts > 0; attempts--) {
            Pair<Location, Location> move = moves.getLast();

            Location s = move.left();
            Location d = move.right();

            boolean firstPass = firstPassRemaining > 0;
            if (firstPass) firstPassRemaining--;

            if (world.isAirBlock(s.x, s.y, s.z)) {
                moves.removeLast();
                continue;
            }

            // torches, wires and the like go last, so that their support is already in place at the destination
            if (firstPass && isAttached(world, s.x, s.y, s.z)) {
                moves.addFirst(moves.removeLast());
                continue;
            }

            // if either block is protected, ignore them completely and print a warning
            if (!isEditable(world, s.x, s.y, s.z, false) || !isEditable(world, d.x, d.y, d.z, true)) {
                sendErrorToPlayer(
                    player,
                    "mm.info.error.could_not_move_protected_block.new",
                    s.x,
                    s.y,
                    s.z,
                    source.getChatComponent()
                );
                moves.removeLast();
                continue;
            }

            BlockSpec.fromBlock(source, world, s.x, s.y, s.z);

            if (source.isAir()) {
                moves.removeLast();
                continue;
            }

            if (source.getBlock().getBlockHardness(world, s.x, s.y, s.z) < 0) {
                sendErrorToPlayer(
                    player,
                    "mm.info.error.could_not_move_invulnerable_block.new",
                    s.x,
                    s.y,
                    s.z,
                    source.getChatComponent()
                );
                moves.removeLast();
                continue;
            }

            BlockSpec.fromBlock(target, world, d.x, d.y, d.z);

            // check if we can remove the existing target block
            boolean canPlace = switch (state.config.removeMode) {
                case NONE -> target.getBlock().isAir(world, d.x, d.y, d.z);
                case REPLACEABLE -> target.getBlock().isReplaceable(world, d.x, d.y, d.z);
                case ALL -> true;
            };

            canPlace &= target.getBlock().getBlockHardness(world, d.x, d.y, d.z) >= 0;

            if (!canPlace) {
                sendErrorToPlayer(
                    player,
                    "mm.info.error.could_not_move_blocked_block.new",
                    d.x,
                    d.y,
                    d.z,
                    source.getChatComponent()
                );
                moves.removeLast();
                continue;
            }

            // remove the existing block if needed
            if (!target.getBlock().isAir(world, d.x, d.y, d.z)) {
                if (!tryConsumePower(stack, world, d.x, d.y, d.z, target)) {
                    sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                    if (firstPass) firstPassRemaining++;
                    break;
                }

                removeBlock(world, d.x, d.y, d.z, target);
            }

            // if we can't move the source block then skip it for now
            if (!source.getBlock().canPlaceBlockAt(world, d.x, d.y, d.z) || !hasMultipartSupport(world, s, d)) {
                moves.addFirst(moves.removeLast());
                continue;
            }

            if (!tryConsumePower(stack, world, s.x, s.y, s.z, source)) {
                sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                if (firstPass) firstPassRemaining++;
                break;
            }

            // try to move the source block into the (now empty) target block
            if (!moveBlock(this, world, s, source, d, target)) {
                sendErrorToPlayer(
                    player,
                    "mm.info.error.could_not_move_block.new",
                    s.x,
                    s.y,
                    s.z,
                    source.getChatComponent()
                );
            }

            playSound(world, s.x, s.y, s.z, SoundResource.MOB_ENDERMEN_PORTAL);
            playSound(world, d.x, d.y, d.z, SoundResource.MOB_ENDERMEN_PORTAL);

            moves.removeLast();
            ops++;
        }

        playSounds();
        actuallyGivePlayerStuff();

        if (ops > 0 || !moves.isEmpty()) {
            sendInfoToPlayer(
                player,
                "mm.info.process_move",
                ops,
                moves.size()
            );
        } else if (moves.isEmpty()) {
            sendInfoToPlayer(
                player,
                "mm.info.finished_move"
            );
        }
    }

    @Override
    public void onStopped() {

    }

    /**
     * Whether a block needs another block to stay in place. The blocks it's attached to are removed without telling
     * it, so it has to be moved after them.
     */
    private static boolean isAttached(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);

        if (Mods.ForgeMultipart.isModLoaded()) {
            TileEntity te = world.getTileEntity(x, y, z);

            // only wires, gates and such are attached, microblocks are what they sit on
            if (MultipartAnalysisResult.isMultipart(te)) return MultipartAnalysisResult.hasAttachedParts(te);
        }

        Material material = block.getMaterial();

        if (!material.isSolid() && !material.isLiquid() && material != Material.air) return true;

        return block instanceof BlockDoor || block instanceof BlockSign ||
            block instanceof BlockTrapDoor ||
            block instanceof BlockBasePressurePlate;
    }

    /** Wires and gates drop themselves on the next block update when the block they sit on is missing. */
    private static boolean hasMultipartSupport(World world, Location s, Location d) {
        if (!Mods.ForgeMultipart.isModLoaded()) return true;

        return MultipartAnalysisResult.hasSupportAt(world.getTileEntity(s.x, s.y, s.z), world, d.x, d.y, d.z);
    }

    /**
     * The movers remove blocks without block updates. Attached blocks in the source region are moved later and must
     * not notice that their support is gone, everything else gets its update here.
     */
    private static void notifyRemoval(PendingMove pendingMove, World world, int x, int y, int z, Block removed) {
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            int nx = x + dir.offsetX, ny = y + dir.offsetY, nz = z + dir.offsetZ;

            if (pendingMove != null && pendingMove.isInSourceRegion(nx, ny, nz) && isAttached(world, nx, ny, nz)) continue;

            world.notifyBlockOfNeighborChange(nx, ny, nz, removed);
        }
    }

    private void filterSourceAir(World world) {
        if (airFilterRemaining == 0) return;

        IChunkProvider chunkProvider = world.getChunkProvider();
        long start = System.nanoTime();
        do {
            Pair<Location, Location> move = moves.getLast();
            Location source = move.left();
            boolean sourceAir = chunkProvider.chunkExists(source.x >> 4, source.z >> 4) &&
                world.isAirBlock(source.x, source.y, source.z);

            moves.removeLast();
            if (!sourceAir) moves.addFirst(move);
            airFilterRemaining--;
        } while (airFilterRemaining > 0 && System.nanoTime() - start < airFilterBudgetNanos);
    }

    private void initMoves() {
        moves = new ArrayDeque<>();

        Location startA = state.config.coordA;
        Location startB = state.config.coordB;
        Location dest = state.config.coordC;

        MMConfig.VoxelAABB cut = new MMConfig.VoxelAABB(startA.toVec(), startB.toVec());
        MMConfig.VoxelAABB paste = cut.clone().moveOrigin(dest.toVec());

        if (cut.toBoundingBox().intersectsWith(paste.toBoundingBox())) {
            MMUtils.sendErrorToPlayer(player, "mm.info.error.move_overlapping");
            return;
        }

        int x1 = startA.x;
        int y1 = startA.y;
        int z1 = startA.z;
        int x2 = startB.x;
        int y2 = startB.y;
        int z2 = startB.z;

        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);

        int worldId = startA.worldId;

        moveOffsetX = dest.x - startA.x;
        moveOffsetY = dest.y - startA.y;
        moveOffsetZ = dest.z - startA.z;
        srcMinX = minX;
        srcMinY = minY;
        srcMinZ = minZ;
        srcMaxX = maxX;
        srcMaxY = maxY;
        srcMaxZ = maxZ;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int dX = x - x1;
                    int dY = y - y1;
                    int dZ = z - z1;

                    moves.add(
                        Pair.of(
                            new Location(worldId, x, y, z),
                            new Location(worldId, dest.x + dX, dest.y + dY, dest.z + dZ)
                        )
                    );
                }
            }
        }
    }

    // 'borrowed' from
    // https://github.com/GTNewHorizons/BloodMagic/blob/master/src/main/java/WayofTime/alchemicalWizardry/common/block/BlockTeleposer.java#L158
    @SuppressWarnings("unchecked")
    public static boolean moveBlock(
        PendingMove pendingMove,
        World world,
        Location s,
        BlockSpec spec1,
        Location d,
        BlockSpec spec2
    ) {

        World worldS = world.provider.dimensionId == s.worldId ? world : s.getWorld();
        int sx = s.x;
        int sy = s.y;
        int sz = s.z;
        World worldD = world.provider.dimensionId == d.worldId ? world : d.getWorld();
        int dx = d.x;
        int dy = d.y;
        int dz = d.z;

        Block blockS = worldS.getBlock(sx, sy, sz);
        Block blockD = worldD.getBlock(dx, dy, dz);

        if (blockS.equals(Blocks.air) && blockD.equals(Blocks.air)) return false;

        if (Mods.BloodMagic.isModLoaded()) {
            if (!allowTelepose(worldS, worldD, s, spec1, d, spec2)) return false;
        }

        BlockMover<Object> source = (BlockMover<Object>) BlockMovers.getBlockMover(worldS, sx, sy, sz);
        BlockMover<Object> dest = (BlockMover<Object>) BlockMovers.getBlockMover(worldD, dx, dy, dz);

        try {
            BlockCaptureDrops.captureDrops(worldS);
            if (worldS != worldD) BlockCaptureDrops.captureDrops(worldD);

            Object sourceState = source.remove(pendingMove, worldS, sx, sy, sz);
            Object destState = dest.remove(pendingMove, worldD, dx, dy, dz);

            source.place(pendingMove, worldD, dx, dy, dz, sourceState);
            dest.place(pendingMove, worldS, sx, sy, sz, destState);
        } finally {
            // delete any items that were dropped
            BlockCaptureDrops.stopCapturingDrops(worldS);
            if (worldS != worldD) BlockCaptureDrops.stopCapturingDrops(worldD);
        }

        // after the capture, so that whatever pops off outside the moved region drops normally
        if (worldS.isAirBlock(sx, sy, sz)) notifyRemoval(pendingMove, worldS, sx, sy, sz, blockS);

        return true;
    }

    @Optional(Names.BLOOD_MAGIC)
    private static boolean allowTelepose(World worldI, World worldF, Location s, BlockSpec spec1, Location d, BlockSpec spec2) {
        TeleposeEvent evt = new TeleposeEvent(
            worldI,
            s.x,
            s.y,
            s.z,
            spec1.getBlock(),
            spec2.getBlockMeta(),
            worldF,
            d.x,
            d.y,
            d.z,
            spec2.getBlock(),
            spec2.getBlockMeta()
        );
        return !MinecraftForge.EVENT_BUS.post(evt);
    }
}
