package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendErrorToPlayer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.networking.SoundResource;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;

import it.unimi.dsi.fastutil.Pair;

/**
 * Handles all moving logic incrementally.
 */
public class FSMMove extends FSM<FSMMove.State, FSMMove> {

    public enum State {

        INIT_PENDING_MOVE,
        INIT_MOVES,
        PREPARE_FOR_MOVE,
        MOVE_BLOCK,
        FINISH;

        public static class NAMES {

            public static final String INIT_PENDING_MOVE = "INIT_PENDING_MOVE";
            public static final String INIT_MOVES = "INIT_MOVES";
            public static final String PREPARE_FOR_MOVE = "PREPARE_FOR_MOVE";
            public static final String MOVE_BLOCK = "MOVE_BLOCK";
            public static final String FINISH = "FINISH";
        }
    }

    public final MMState state;
    public final EntityPlayer player;
    public final World world;
    public final ItemStack stack;
    public final ItemMatterManipulator.ManipulatorTier tier;

    /**
     * TODO:
     * We're still using PendingMove because
     * block movers still depends on it
     * instead of a standalone ctx
     */
    protected PendingMove pendingMove;
    protected int firstPassRemaining;
    protected BlockSpec source;
    protected BlockSpec target;
    protected int movesBudget;
    protected int movedAmount;
    protected long lastMoveTick;

    public FSMMove(MMState state, EntityPlayer player, ItemStack stack, ItemMatterManipulator.ManipulatorTier tier) {
        super(State.INIT_PENDING_MOVE, State.FINISH);

        this.state = state;
        this.player = player;
        this.world = player.worldObj;
        this.stack = stack;
        this.tier = tier;
    }

    public void resetBudget() {
        movesBudget = tier.placeSpeed;
        movedAmount = 0;
    }

    public int getMovedAmount() {
        return movedAmount;
    }

    public int getRemainingAmount() {
        return pendingMove != null ? pendingMove.moves.size() : 0;
    }

    @FSM.Event(State.NAMES.INIT_PENDING_MOVE)
    protected boolean initPendingMove() {
        pendingMove = new PendingMove(player, state, tier);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_PENDING_MOVE, to = State.NAMES.INIT_MOVES)
    protected boolean pendingMoveInitialized() {
        return pendingMove != null;
    }

    @FSM.Event(State.NAMES.INIT_MOVES)
    protected boolean initMoves() {
        // TODO: Implement schedulable moves analyzer
        pendingMove.initMoves();
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_MOVES, to = State.NAMES.PREPARE_FOR_MOVE)
    protected boolean movesInitialized() {
        return pendingMove.moves != null;
    }

    @FSM.Event(State.NAMES.PREPARE_FOR_MOVE)
    protected boolean prepareForMove() {
        firstPassRemaining = pendingMove.moves.size();
        source = new BlockSpec();
        target = new BlockSpec();

        resetBudget();

        return true;
    }

    @FSM.Transition(from = State.NAMES.PREPARE_FOR_MOVE, to = State.NAMES.MOVE_BLOCK)
    protected boolean preparedForMove() {
        return source != null;
    }

    @FSM.Event(State.NAMES.MOVE_BLOCK)
    protected boolean moveBlock() {
        if (movesBudget <= 0) return false;
        if (pendingMove.moves.isEmpty()) return true;

        if (getLastWorkingTick() != lastMoveTick) {
            pendingMove.resetWarnings();
            pendingMove.refillPower(stack);
        }

        lastMoveTick = getLastWorkingTick();

        Pair<Location, Location> move = pendingMove.moves.getLast();

        Location s = move.left();
        Location d = move.right();

        boolean firstPass = firstPassRemaining > 0;
        if (firstPass) firstPassRemaining--;

        if (world.isAirBlock(s.x, s.y, s.z)) {
            pendingMove.moves.removeLast();
            return true;
        }

        // torches, wires and the like go last, so that their support is already in place at the destination
        if (firstPass && PendingMove.isAttached(world, s.x, s.y, s.z)) {
            pendingMove.moves.addFirst(pendingMove.moves.removeLast());
            return true;
        }

        // if either block is protected, ignore them completely and print a warning
        if (!pendingMove.isEditable(world, s.x, s.y, s.z, false) || !pendingMove.isEditable(world, d.x, d.y, d.z, true)) {
            sendErrorToPlayer(
                player,
                "mm.info.error.could_not_move_protected_block.new",
                s.x,
                s.y,
                s.z,
                source.getChatComponent()
            );
            pendingMove.moves.removeLast();
            return true;
        }

        BlockSpec.fromBlock(source, world, s.x, s.y, s.z);

        if (source.isAir()) {
            pendingMove.moves.removeLast();
            return true;
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
            pendingMove.moves.removeLast();
            return true;
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
            pendingMove.moves.removeLast();
            return true;
        }

        // remove the existing block if needed
        if (!target.getBlock().isAir(world, d.x, d.y, d.z)) {
            if (!pendingMove.tryConsumePower(stack, world, d.x, d.y, d.z, target)) {
                sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                if (firstPass) firstPassRemaining++;
                return false;
            }

            pendingMove.removeBlock(world, d.x, d.y, d.z, target);
        }

        // if we can't move the source block then skip it for now
        if (!PendingMove.canStayAt(world, s, d, source.getBlock()) || !PendingMove.hasMultipartSupport(world, s, d)) {
            pendingMove.moves.addFirst(pendingMove.moves.removeLast());
            return true;
        }

        if (!pendingMove.tryConsumePower(stack, world, s.x, s.y, s.z, source)) {
            sendErrorToPlayer(player, "mm.info.error.out_of_eu");
            if (firstPass) firstPassRemaining++;
            return false;
        }

        int[] otherHalf = PendingMove.getOtherHalf(world, s.x, s.y, s.z);

        // try to move the source block into the (now empty) target block
        boolean moved = PendingMove.moveBlock(pendingMove, world, s, source, d, target);

        if (!moved) {
            sendErrorToPlayer(
                player,
                "mm.info.error.could_not_move_block.new",
                s.x,
                s.y,
                s.z,
                source.getChatComponent()
            );
        }

        pendingMove.playSound(world, s.x, s.y, s.z, SoundResource.MOB_ENDERMEN_PORTAL);
        pendingMove.playSound(world, d.x, d.y, d.z, SoundResource.MOB_ENDERMEN_PORTAL);

        pendingMove.moves.removeLast();

        // one half breaks on the next block update without the other, so the other one follows right away
        if (moved && otherHalf != null) {
            int ox = s.x + otherHalf[0], oy = s.y + otherHalf[1], oz = s.z + otherHalf[2];

            if (pendingMove.isInSourceRegion(ox, oy, oz) && world.getBlock(ox, oy, oz) == source.getBlock()) {
                pendingMove.moves.addLast(
                    Pair.of(
                        new Location(s.worldId, ox, oy, oz),
                        new Location(d.worldId, d.x + otherHalf[0], d.y + otherHalf[1], d.z + otherHalf[2])
                    )
                );
            }
        }

        movesBudget--;
        movedAmount++;

        return true;
    }

    @FSM.Transition(from = State.NAMES.MOVE_BLOCK, to = State.NAMES.FINISH)
    protected boolean blocksMoved() {
        return pendingMove.moves.isEmpty();
    }
}
