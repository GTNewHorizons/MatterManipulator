package com.recursive_pineapple.matter_manipulator.common.building;

import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendErrorToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendInfoToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendWarningToPlayer;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import net.minecraftforge.common.MinecraftForge;

import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.movers.BlockMover;
import com.recursive_pineapple.matter_manipulator.common.building.movers.BlockMovers;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator.ManipulatorTier;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.networking.SoundResource;
import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;
import com.recursive_pineapple.matter_manipulator.common.utils.SortedArrayList;
import com.recursive_pineapple.matter_manipulator.mixin.BlockCaptureDrops;

import org.joml.Vector3i;

import WayofTime.alchemicalWizardry.api.event.TeleposeEvent;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;

/**
 * Handles all moving logic.
 */
public class PendingMove extends AbstractBuildable {

    private SortedArrayList<IntObjectImmutablePair<Pair<MoveSnapshot, MoveSnapshot>>> moves = null;
    private PendingMoveApplyContext context = null;

    private int moveOffsetX, moveOffsetY, moveOffsetZ;
    private int srcMinX, srcMinY, srcMinZ, srcMaxX, srcMaxY, srcMaxZ;

    /** State that block movers carry from remove() to place(), keyed by the removed block's state object. */
    private final Map<Object, Object> moverState = new IdentityHashMap<>();

    public PendingMove(EntityPlayer player, MMState state, ManipulatorTier tier) {
        super(player, state, tier);
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

        Transform transform = state.getTransform();
        if (transform.equals(Transform.DEFAULT_TRANSFORM)) transform = null;

        if (moves == null || context == null) {
            initMoves(transform);
        }

        World world = player.worldObj;

        BlockSpec sourceSpec = new BlockSpec();
        BlockSpec targetSpec = new BlockSpec();

        int ops = 0;
        var iter = moves.listIterator(moves.size());

        SortedArrayList<IntObjectImmutablePair<Pair<MoveSnapshot, MoveSnapshot>>> shuffled = new SortedArrayList<>(
            Comparator.comparingInt(IntObjectImmutablePair::leftInt)
        );

        // try to move `placeSpeed` blocks from here to there
        while (ops < tier.placeSpeed && iter.hasPrevious()) {
            IntObjectImmutablePair<Pair<MoveSnapshot, MoveSnapshot>> intPair = iter.previous();
            Pair<MoveSnapshot, MoveSnapshot> move = intPair.right();

            Location s = move.left().loc;
            Location d = move.right().loc;

            // if either block is protected, ignore them completely and print a warning
            if (!isEditable(world, s.x, s.y, s.z, false) || !isEditable(world, d.x, d.y, d.z, true)) {
                sendErrorToPlayer(
                    player,
                    "mm.info.error.could_not_move_protected_block.new",
                    s.x,
                    s.y,
                    s.z,
                    sourceSpec.getChatComponent()
                );
                iter.remove();
                continue;
            }

            BlockSpec source = move.left().spec;
            if (source == null || source == sourceSpec) {
                source = BlockSpec.fromBlock(sourceSpec, world, s.x, s.y, s.z);
                move.left().spec = source;
            }

            if (source.isAir()) {
                iter.remove();
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
                iter.remove();
                continue;
            }

            BlockSpec target = move.right().spec;
            if (target == null || target == targetSpec) {
                target = BlockSpec.fromBlock(targetSpec, world, d.x, d.y, d.z);
                move.right().spec = target;
            }

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
                iter.remove();
                continue;
            }

            // remove the existing block if needed
            if (!target.getBlock().isAir(world, d.x, d.y, d.z)) {
                if (!tryConsumePower(stack, world, d.x, d.y, d.z, target)) {
                    sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                    break;
                }

                removeBlock(world, d.x, d.y, d.z, target);
            }

            // if we can't move the source block then skip it for now
            if (!source.getBlock().canPlaceBlockAt(world, d.x, d.y, d.z)) {
                shuffled.add(intPair);
                iter.remove();
                continue;
            }

            if (!tryConsumePower(stack, world, s.x, s.y, s.z, source)) {
                sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                break;
            }

            // try to move the source block into the (now empty) target block
            if (!moveBlock(this, world, move.left(), move.right(), transform)) {
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

            iter.remove();
            ops++;
        }

        context.loc = null;
        context.spec = null;

        moves.addAll(shuffled);

        playSounds();
        actuallyGivePlayerStuff();

        if (ops > 0) {
            sendInfoToPlayer(
                player,
                "mm.info.process_move",
                ops,
                moves.size()
            );
        } else {
            sendInfoToPlayer(
                player,
                "mm.info.finished_move"
            );
        }
    }

    @Override
    public void onStopped() {

    }

    private void initMoves(Transform transform) {
        moves = new SortedArrayList<>(
            Comparator.comparingInt(IntObjectImmutablePair::leftInt)
        );

        context = new PendingMoveApplyContext(this, null, null);

        Location startA = state.config.coordA;
        Location startB = state.config.coordB;
        Location dest = state.config.coordC;

        MMConfig.VoxelAABB cut = new MMConfig.VoxelAABB(startA.toVec(), startB.toVec());
        MMConfig.VoxelAABB paste = cut.clone().moveOrigin(dest.toVec());

        if (transform != null) {
            paste = transform.apply(paste);
        }

        if (cut.toBoundingBox().intersectsWith(paste.toBoundingBox())) {
            sendErrorToPlayer(player, "mm.info.error.move_overlapping");
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

                    Vector3i vec = new Vector3i(dX, dY, dZ);
                    if (transform != null) vec = transform.apply(vec);

                    Location locSrc = new Location(worldId, x, y, z);
                    Location locDst = new Location(worldId, dest.x + vec.x, dest.y + vec.y, dest.z + vec.z);

                    World world = startA.getWorld();

                    BlockMover<?> srcMover = BlockMovers.getBlockMover(world, locSrc.x, locSrc.y, locSrc.z);
                    BlockMover<?> dstMover = BlockMovers.getBlockMover(world, locDst.x, locDst.y, locDst.z);

                    Object srcState = null;
                    Object dstState = null;
                    BlockSpec srcSpec = null;
                    BlockSpec dstSpec = null;

                    if (srcMover.snapshotEarly()) {
                        context.loc = locSrc;

                        srcSpec = BlockSpec.fromBlock(null, world, locSrc.x, locSrc.y, locSrc.z);
                        context.spec = srcSpec;

                        srcState = srcMover.snapshot(context, world, locSrc.x, locSrc.y, locSrc.z);
                    }

                    if (dstMover.snapshotEarly()) {
                        context.loc = locDst;

                        dstSpec = BlockSpec.fromBlock(null, world, locDst.x, locDst.y, locDst.z);
                        context.spec = dstSpec;

                        dstState = dstMover.snapshot(context, world, locDst.x, locDst.y, locDst.z);
                    }

                    Pair<MoveSnapshot, MoveSnapshot> pair = Pair.of(
                        new MoveSnapshot(locSrc, srcState, srcSpec, (BlockMover<Object>) srcMover),
                        new MoveSnapshot(locDst, dstState, dstSpec, (BlockMover<Object>) dstMover)
                    );

                    int priority = srcMover.snapshotEarly() || dstMover.snapshotEarly() ? Integer.MIN_VALUE : 0;

                    // FMP needs support blocks so we move them last
                    moves.add(new IntObjectImmutablePair<>(priority, pair));
                }
            }
        }
    }

    private static void remove(World world, int x, int y, int z) {
        world.removeTileEntity(x, y, z);
        world.setBlockToAir(x, y, z);
    }

    private static PendingMoveApplyContext ctx(PendingMove pendingMove, MoveSnapshot snapshot, BlockSpec spec) {
        pendingMove.context.loc = snapshot.loc;
        pendingMove.context.spec = spec == null ? snapshot.spec : spec;

        return pendingMove.context;
    }

    // 'borrowed' from
    // https://github.com/GTNewHorizons/BloodMagic/blob/master/src/main/java/WayofTime/alchemicalWizardry/common/block/BlockTeleposer.java#L158
    @SuppressWarnings("unchecked")
    public static boolean moveBlock(
        PendingMove pendingMove,
        World world,
        MoveSnapshot s,
        MoveSnapshot d,
        Transform transform
    ) {
        World worldS = world.provider.dimensionId == s.loc.worldId ? world : s.loc.getWorld();
        int sx = s.loc.x;
        int sy = s.loc.y;
        int sz = s.loc.z;
        World worldD = world.provider.dimensionId == d.loc.worldId ? world : d.loc.getWorld();
        int dx = d.loc.x;
        int dy = d.loc.y;
        int dz = d.loc.z;

        if (s.spec.isAir() && d.spec.isAir()) return false;

        if (Mods.BloodMagic.isModLoaded()) {
            if (!allowTelepose(worldS, worldD, s.loc, s.spec, d.loc, d.spec)) return false;
        }

        try {
            BlockCaptureDrops.captureDrops(worldS);
            if (worldS != worldD) BlockCaptureDrops.captureDrops(worldD);

            PendingBlock pendingBlockSource = null;
            PendingBlock pendingBlockDest = null;

            if (transform != null) {
                pendingBlockSource = s.spec.instantiate(worldD, dx, dy, dz);
                pendingBlockDest = d.spec.instantiate(worldS, sx, sy, sz);
            }

            if (s.state == null) s.state = s.mover.snapshot(ctx(pendingMove, s, s.spec), worldS, sx, sy, sz);
            if (d.state == null) d.state = d.mover.snapshot(ctx(pendingMove, d, d.spec), worldD, dx, dy, dz);

            if (transform != null) {
                s.mover.transform(ctx(pendingMove, d, s.spec), transform, s.state);
                d.mover.transform(ctx(pendingMove, s, d.spec), transform, d.state);

                pendingBlockSource.transform(transform);
                pendingBlockDest.transform(transform);
            }

            PendingMove.remove(worldS, sx, sy, sz);
            PendingMove.remove(worldD, dx, dy, dz);

            s.mover.place(ctx(pendingMove, d, s.spec), worldD, dx, dy, dz, s.state);
            d.mover.place(ctx(pendingMove, s, d.spec), worldS, sx, sy, sz, d.state);

            if (transform != null) {
                pendingBlockSource.apply(ctx(pendingMove, d, s.spec), worldD);
                pendingBlockDest.apply(ctx(pendingMove, s, d.spec), worldS);
            }
        } finally {
            // delete any items that were dropped
            BlockCaptureDrops.stopCapturingDrops(worldS);
            if (worldS != worldD) BlockCaptureDrops.stopCapturingDrops(worldD);
        }

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

    public static class MoveSnapshot {

        public Location loc;
        public Object state;
        public BlockSpec spec;
        public BlockMover<Object> mover;

        public MoveSnapshot(Location loc, Object state, BlockSpec spec, BlockMover<Object> mover) {
            this.loc = loc;
            this.state = state;
            this.spec = spec;
            this.mover = mover;
        }
    }

    public static class PendingMoveApplyContext implements BlockAnalyzer.IBlockApplyContext {

        public PendingMove pendingMove;
        public Location loc;
        public BlockSpec spec;

        public PendingMoveApplyContext(PendingMove pendingMove, Location loc, BlockSpec spec) {
            this.pendingMove = pendingMove;
            this.loc = loc;
            this.spec = spec;
        }

        @Override
        public World getWorld() {
            return loc.getWorld();
        }

        @Override
        public int getX() {
            return loc.x;
        }

        @Override
        public int getY() {
            return loc.y;
        }

        @Override
        public int getZ() {
            return loc.z;
        }

        @Override
        public TileEntity getTileEntity() {
            return getWorld().getTileEntity(getX(), getY(), getZ());
        }

        @Override
        public EntityPlayer getRealPlayer() {
            return pendingMove.player;
        }

        @Override
        public MMConfig getConfig() {
            return pendingMove.state.config;
        }

        @Override
        public boolean tryApplyAction(double complexity) {
            return false;
        }

        @Override
        public void warn(IChatComponent message) {
            if (loc == null) return;
            if (spec != null) {
                IChatComponent blockNameComponent = spec.getChatComponent();
                sendWarningToPlayer(
                    getRealPlayer(),
                    "mm.info.warning.with_block",
                    getX(),
                    getY(),
                    getZ(),
                    blockNameComponent,
                    message
                );
            } else {
                sendWarningToPlayer(
                    getRealPlayer(),
                    "mm.info.warning.with_block",
                    getX(),
                    getY(),
                    getZ(),
                    message
                );
            }
        }

        @Override
        public void error(IChatComponent message) {
            if (loc == null) return;
            if (spec != null) {
                IChatComponent blockNameComponent = spec.getChatComponent();
                sendErrorToPlayer(
                    getRealPlayer(),
                    "mm.info.error.with_block",
                    getX(),
                    getY(),
                    getZ(),
                    blockNameComponent,
                    message
                );
            } else {
                sendErrorToPlayer(
                    getRealPlayer(),
                    "mm.info.error.with_block",
                    getX(),
                    getY(),
                    getZ(),
                    message
                );
            }
        }

        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            return pendingMove.tryConsumeItems(items, flags);
        }

        @Override
        public void givePlayerItems(List<BigItemStack> items) {
            pendingMove.givePlayerItems(items);
        }

        @Override
        public void givePlayerFluids(List<BigFluidStack> fluids) {
            pendingMove.givePlayerFluids(fluids);
        }
    }
}
