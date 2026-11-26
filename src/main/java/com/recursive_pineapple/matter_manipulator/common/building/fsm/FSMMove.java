package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendErrorToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendInfoToPlayer;

import java.util.ArrayDeque;
import java.util.Deque;

import com.recursive_pineapple.matter_manipulator.common.building.ProxiedWorld;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentNumber;
import com.recursive_pineapple.matter_manipulator.MMMod;
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
        INIT_TO_MOVE,
        GET_TO_MOVE,
        MOVE_BLOCK,
        POST_MOVE,
        COMPLETE,
        FINISH;

        public static class NAMES {

            public static final String INIT_PENDING_MOVE = "INIT_PENDING_MOVE";
            public static final String INIT_MOVES = "INIT_MOVES";
            public static final String PREPARE_FOR_MOVE = "PREPARE_FOR_MOVE";
            public static final String INIT_TO_MOVE = "INIT_TO_MOVE";
            public static final String GET_TO_MOVE = "GET_TO_MOVE";
            public static final String MOVE_BLOCK = "MOVE_BLOCK";
            public static final String POST_MOVE = "POST_MOVE";
            public static final String COMPLETE = "COMPLETE";
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
    protected boolean firstPass = true;
    protected int firstPassRemaining;
    protected ProxiedWorld proxiedWorld;
    protected BlockSpec source;
    protected BlockSpec target;
    protected int movesBudget;
    protected int movedAmount;
    protected boolean finished;
    protected long lastPostMessageTick;

    protected Deque<Pair<Location, Location>> toMove;
    protected int toMoveAmount;
    protected int moveOperation = -1;

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
    }

    public int getMovedAmount() {
        return movedAmount;
    }

    public int getRemainingAmount() {
        if (pendingMove == null) return 0;
        int moveAmount = pendingMove.moves != null ? pendingMove.moves.size() : 0;
        int toMoveAmount = toMove != null ? toMove.size() : 0;
        return moveAmount + toMoveAmount;
    }

    public void finishMove() {
        if (finished) return;
        if (pendingMove != null) {
            pendingMove.actuallyGivePlayerStuff();
            if (!pendingMove.pendingItems.isEmpty() || !pendingMove.pendingFluids.isEmpty()) {
                MMMod.LOG.error("Move stopped without delivering all items! There's a bug somewhere!");
            }
        }
        finished = true;
    }

    public long getLastPostMessageTick() {
        return lastPostMessageTick;
    }

    public void postMessage() {
        sendInfoToPlayer(
            player,
            "mm.info.process_move",
            new ChatComponentNumber(movedAmount),
            new ChatComponentNumber(getRemainingAmount())
        );
        movedAmount = 0;
        lastPostMessageTick = getLastWorkingTick();
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
        lastPostMessageTick = getLastWorkingTick();
        proxiedWorld = new ProxiedWorld(world);

        resetBudget();

        return true;
    }

    @FSM.Transition(from = State.NAMES.PREPARE_FOR_MOVE, to = State.NAMES.INIT_TO_MOVE)
    protected boolean preparedForMove() {
        return source != null;
    }

    @FSM.Event(State.NAMES.INIT_TO_MOVE)
    protected boolean initToMove() {
        if (toMove == null) toMove = new ArrayDeque<>();

        movedAmount = 0;
        toMoveAmount = toMove.size();
        moveOperation = 0;
        firstPass = firstPassRemaining > 0;

        pendingMove.refillPower(stack);
        pendingMove.resetWarnings();

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_TO_MOVE, to = State.NAMES.GET_TO_MOVE)
    protected boolean toMoveInitialized() {
        return toMove != null && toMove.size() == toMoveAmount && moveOperation == 0;
    }

    @FSM.Event(State.NAMES.GET_TO_MOVE)
    protected boolean getToMove() {
        if (movesBudget == 0) return true;
        if (pendingMove.moves.isEmpty()) return true;

        Pair<Location, Location> move = pendingMove.moves.removeLast();

        Location s = move.left();
        Location d = move.right();

        if (firstPass) firstPassRemaining--;

        if (world.isAirBlock(s.x, s.y, s.z)) { return true; }

        // torches, wires and the like go last, so that their support is already in place at the destination
        if (firstPass && PendingMove.isAttached(world, s.x, s.y, s.z)) {
            pendingMove.moves.addFirst(move);
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
            movesBudget--;
            return true;
        }

        BlockSpec.fromBlock(source, world, s.x, s.y, s.z);

        if (source.isAir()) { return true; }

        if (source.getBlock().getBlockHardness(world, s.x, s.y, s.z) < 0) {
            sendErrorToPlayer(
                player,
                "mm.info.error.could_not_move_invulnerable_block.new",
                s.x,
                s.y,
                s.z,
                source.getChatComponent()
            );
            movesBudget--;
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
            movesBudget--;
            return true;
        }

        movesBudget--;
        toMove.addFirst(move);
        toMoveAmount++;

        return true;
    }

    @FSM.Transition(from = State.NAMES.GET_TO_MOVE, to = State.NAMES.MOVE_BLOCK)
    protected boolean timeToMove() {
        if (movesBudget == 0 || pendingMove.moves.isEmpty() || (firstPass && firstPassRemaining == 0)) {
            movesBudget = 0;
            return true;
        }
        return false;
    }

    @FSM.Event(State.NAMES.MOVE_BLOCK)
    protected boolean moveBlock() {
        if (toMove.isEmpty()) return true;
        if (moveOperation == toMoveAmount) return false;

        moveOperation++;

        Pair<Location, Location> move = toMove.removeLast();

        Location s = move.left();
        Location d = move.right();

        BlockSpec.fromBlock(source, world, s.x, s.y, s.z);

        if (source.isAir()) {
            return true;
        }

        BlockSpec.fromBlock(target, world, d.x, d.y, d.z);

        // consume eu first before doing anything
        if (!target.getBlock().isAir(world, d.x, d.y, d.z) && !pendingMove.tryConsumePower(stack, world, d.x, d.y, d.z, target)) {
            sendErrorToPlayer(player, "mm.info.error.out_of_eu");
            toMove.addLast(move);
            moveOperation = toMoveAmount;
            return false;
        }

        proxiedWorld.airX = d.x;
        proxiedWorld.airY = d.y;
        proxiedWorld.airZ = d.z;

        // if we can't move the source block then skip it for now
        if (!PendingMove.canStayAt(proxiedWorld, s, d, source.getBlock()) || !PendingMove.hasMultipartSupport(proxiedWorld, s, d)) {
            toMove.addFirst(move);
            return true;
        }

        if (!pendingMove.tryConsumePower(stack, world, s.x, s.y, s.z, source)) {
            sendErrorToPlayer(player, "mm.info.error.out_of_eu");
            toMove.addLast(move);
            moveOperation = toMoveAmount;
            return false;
        }

        // remove the existing block if needed
        if (!target.getBlock().isAir(world, d.x, d.y, d.z)) {
            pendingMove.removeBlock(world, d.x, d.y, d.z, target);
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

        // one half breaks on the next block update without the other, so the other one follows right away
        if (moved && otherHalf != null) {
            int ox = s.x + otherHalf[0], oy = s.y + otherHalf[1], oz = s.z + otherHalf[2];

            if (pendingMove.isInSourceRegion(ox, oy, oz) && world.getBlock(ox, oy, oz) == source.getBlock()) {
                toMove.addLast(
                    Pair.of(
                        new Location(s.worldId, ox, oy, oz),
                        new Location(d.worldId, d.x + otherHalf[0], d.y + otherHalf[1], d.z + otherHalf[2])
                    )
                );
                toMoveAmount++;
            }
        }

        movedAmount++;

        return true;
    }

    @FSM.Transition(from = State.NAMES.MOVE_BLOCK, to = State.NAMES.POST_MOVE)
    protected boolean blocksMoved() {
        return toMove.isEmpty() || movesBudget != 0;
    }

    @FSM.Event(State.NAMES.POST_MOVE)
    protected boolean postMove() {
        if (moveOperation == -1) return false;

        moveOperation = -1;

        pendingMove.actuallyGivePlayerStuff();
        pendingMove.playSounds();

        postMessage();

        return true;
    }

    @FSM.Transition(from = State.NAMES.POST_MOVE, to = State.NAMES.INIT_TO_MOVE)
    protected boolean moveAgain() {
        return moveOperation == -1 && getRemainingAmount() > 0 && movesBudget != 0;
    }

    @FSM.Transition(from = State.NAMES.POST_MOVE, to = State.NAMES.COMPLETE)
    protected boolean moveComplete() {
        return moveOperation == -1 && getRemainingAmount() == 0;
    }

    @FSM.Event(State.NAMES.COMPLETE)
    protected boolean completeMove() {
        finishMove();
        sendInfoToPlayer(player, "mm.info.finished_move");
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE, to = State.NAMES.FINISH)
    protected boolean moveCompleted() {
        return finished;
    }
}
