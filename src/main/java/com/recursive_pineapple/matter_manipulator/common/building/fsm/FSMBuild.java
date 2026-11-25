package com.recursive_pineapple.matter_manipulator.common.building.fsm;

import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendErrorToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendInfoToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendWarningToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.Mods.GregTech;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

import appeng.api.implementations.tiles.ISegmentedInventory;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartItemStack;
import appeng.me.GridAccessException;
import appeng.parts.AEBasePart;
import appeng.parts.p2p.PartP2PTunnel;
import appeng.util.SettingsFrom;

import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentItemName;
import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentNumber;
import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.AbstractBuildable;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.building.MMItemConsumer;
import com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.building.ProxiedWorld;
import com.recursive_pineapple.matter_manipulator.common.building.SmartCopyIntegration;
import com.recursive_pineapple.matter_manipulator.common.building.WirelessLinkFixer;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PlaceMode;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;
import com.recursive_pineapple.matter_manipulator.common.networking.SoundResource;
import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.ItemId;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.FSM;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Handles all building logic incrementally.
 */
public class FSMBuild extends FSM<FSMBuild.State, FSMBuild> {

    public enum State {

        INIT_ANALYZER,
        WAIT_FOR_ANALYZER,
        INIT_BUILD,
        INIT_WIRELESS_FIXER,
        INIT_TO_PLACE,
        GET_TO_PLACE,
        EXTRACT_ITEMS,
        PLACE_BLOCK,
        POST_PLACE,
        COMPLETE,
        FINISH;

        public static class NAMES {

            public static final String INIT_ANALYZER = "INIT_ANALYZER";
            public static final String WAIT_FOR_ANALYZER = "WAIT_FOR_ANALYZER";
            public static final String INIT_BUILD = "INIT_BUILD";
            public static final String INIT_WIRELESS_FIXER = "INIT_WIRELESS_FIXER";
            public static final String INIT_TO_PLACE = "INIT_TO_PLACE";
            public static final String GET_TO_PLACE = "GET_TO_PLACE";
            public static final String EXTRACT_ITEMS = "EXTRACT_ITEMS";
            public static final String PLACE_BLOCK = "PLACE_BLOCK";
            public static final String POST_PLACE = "POST_PLACE";
            public static final String COMPLETE = "COMPLETE";
            public static final String FINISH = "FINISH";
        }
    }

    public final MMState state;
    public final EntityPlayer player;
    public final World world;
    public final ItemStack stack;
    public final ItemMatterManipulator.ManipulatorTier tier;

    protected AbstractBuildable buildable;
    protected FSMAnalyzer analyzer;
    protected Throwable analyzerError;
    protected Deque<PendingBlock> pendingBlocks;
    protected WirelessLinkFixer wirelessLinkFixer;
    protected BlockSpec pooled;
    protected ProxiedWorld proxiedWorld;
    protected FSMBuildApplyContext applyContext;

    protected int placeBudget;
    protected int placedAmount;
    protected int shuffledWithoutProgress;

    protected Deque<PendingBlock> toPlace;
    protected int toPlaceAmount;
    protected int placeOperation;

    protected int lastChunkX;
    protected int lastChunkZ;
    protected boolean placeUnsupported;
    protected boolean itemsExtracted;
    protected ItemStack perBlock;
    protected BigItemStack extracted;

    private boolean finished;
    private final Set<Long> visited = new HashSet<>();
    private final LongList errors = new LongArrayList();
    private final LongList warnings = new LongArrayList();
    private final List<PendingBlock> appliedP2POutputs = new ArrayList<>();
    private final Set<Pair<Long, ForgeDirection>> convertedSources = new HashSet<>();
    private final BiPredicate<ISchedulable, Throwable> childErrorHandler = this::handleChildError;

    public FSMBuild(MMState state, EntityPlayer player, ItemStack stack, ItemMatterManipulator.ManipulatorTier tier) {
        super(State.INIT_ANALYZER, State.FINISH);

        this.state = state;
        this.player = player;
        this.world = player.worldObj;
        this.stack = stack;
        this.tier = tier;
    }

    @Override
    public void onScheduled(WorkScheduler scheduler) {
        super.onScheduled(scheduler);

        if (scheduler == null || !hasWork()) return;
        scheduler.addErrorHandler(this.childErrorHandler);
        if (analyzer != null && analyzer.hasWork()) scheduler.scheduleWork(analyzer);
    }

    protected boolean handleChildError(ISchedulable schedulable, Throwable e) {
        if (schedulable == analyzer) {
            analyzerError = e;
            return true;
        }

        return false;
    }

    @Override
    public void onUnscheduled() {
        WorkScheduler scheduler = getScheduler();
        if (scheduler != null) scheduler.removeErrorHandler(this.childErrorHandler);
        unscheduleChild(analyzer);
    }

    public void resetBudget() {
        placeBudget = tier.placeSpeed;
    }

    public int getAnalyzedAmount() {
        return analyzer == null ? 0 : analyzer.blocks.size();
    }

    public int getPlacedAmount() {
        return placedAmount;
    }

    public int getRemainingAmount() {
        if (pendingBlocks != null) {
            int blocksAmount = pendingBlocks.size();
            int toPlaceAmount = toPlace != null ? toPlace.size() : 0;
            return blocksAmount + toPlaceAmount;
        }

        return getAnalyzedAmount();
    }

    public void finishBuild() {
        if (finished) return;
        if (buildable == null) return;

        if (extracted != null && extracted.stackSize > 0) {
            buildable.givePlayerItems(extracted.toStacks().toArray(new ItemStack[0]));
        }

        buildable.actuallyGivePlayerStuff();

        if (!buildable.pendingItems.isEmpty() || !buildable.pendingFluids.isEmpty()) {
            MMMod.LOG.error("Build stopped without delivering all items! There's a bug somewhere!");
        }

        Messages.BuildStatus.sendToPlayer((EntityPlayerMP) player, Pair.of(errors, warnings));

        finished = true;
    }

    @FSM.Event(State.NAMES.INIT_ANALYZER)
    protected boolean initAnalyzer() {
        analyzer = new FSMAnalyzer(player, state, world, tier);
        getScheduler().scheduleWork(analyzer);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_ANALYZER, to = State.NAMES.WAIT_FOR_ANALYZER)
    protected boolean analyzerInitialized() {
        return analyzer != null;
    }

    @FSM.Event(State.NAMES.WAIT_FOR_ANALYZER)
    protected boolean propagateAnalyzerError() throws Throwable {
        if (analyzerError != null) throw analyzerError;
        return false;
    }

    @FSM.Transition(from = State.NAMES.WAIT_FOR_ANALYZER, to = State.NAMES.INIT_BUILD)
    protected boolean analyzerComplete() {
        return !analyzer.hasWork();
    }

    @FSM.Event(State.NAMES.INIT_BUILD)
    protected boolean initBuild() {
        pendingBlocks = new ArrayDeque<>(analyzer.blocks);
        pooled = new BlockSpec();
        proxiedWorld = new ProxiedWorld(world);
        applyContext = new FSMBuildApplyContext(stack);
        buildable = new AbstractBuildable(player, state, tier);

        resetBudget();

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_BUILD, to = State.NAMES.INIT_WIRELESS_FIXER)
    protected boolean buildInitialized() {
        return pendingBlocks != null;
    }

    @FSM.Event(State.NAMES.INIT_WIRELESS_FIXER)
    protected boolean initWirelessFixer() {
        wirelessLinkFixer = new WirelessLinkFixer(state);
        wirelessLinkFixer.tryInit(world);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_WIRELESS_FIXER, to = State.NAMES.INIT_TO_PLACE)
    protected boolean wirelessFixerInitialized() {
        return wirelessLinkFixer != null;
    }

    @FSM.Event(State.NAMES.INIT_TO_PLACE)
    protected boolean initToPlace() {
        if (toPlace == null) toPlace = new ArrayDeque<>();

        placedAmount = 0;
        toPlaceAmount = toPlace.size();
        placeOperation = 0;
        shuffledWithoutProgress = 0;
        perBlock = null;
        extracted = null;
        itemsExtracted = false;

        buildable.refillPower(stack);
        buildable.resetWarnings();

        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_TO_PLACE, to = State.NAMES.GET_TO_PLACE)
    protected boolean toPlaceInitialized() {
        return toPlace != null && toPlace.size() == toPlaceAmount && placeOperation == 0;
    }

    @Event(State.NAMES.GET_TO_PLACE)
    protected boolean getToPlace() {
        if (placeBudget == 0) return true;
        if (pendingBlocks.isEmpty()) return true;

        PendingBlock block = pendingBlocks.removeFirst();

        int x = block.x, y = block.y, z = block.z;

        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        // if this block's chunk isn't loaded, ignore it completely
        if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
            if (!world.getChunkProvider().chunkExists(chunkX, chunkZ)) {
                shuffledWithoutProgress = 0;
                return true;
            } else {
                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
            }
        }

        // is there a better non-hardcoded way?
        if (y < 0 || y > 255) {
            shuffledWithoutProgress = 0;
            return true;
        }

        if (block.spec.isAir() && world.isAirBlock(x, y, z)) {
            shuffledWithoutProgress = 0;
            return true;
        }

        // if this block is different from the last one, stop checking blocks
        // since the pending blocks are sorted by their contained block, this is usually true
        if (!toPlace.isEmpty() && !block.spec.isEquivalent(toPlace.getFirst().spec)) {
            pendingBlocks.addFirst(block);
            placeBudget = 0;
            return true;
        }

        // if this block is protected, ignore it completely and print a warning
        if (!buildable.isEditable(world, x, y, z, true)) {
            shuffledWithoutProgress = 0;
            placeBudget--;
            return true;
        }

        // wires and gates drop themselves without their support, which may be a multipart that's placed later
        if (block.mp != null && !placeUnsupported && !((MultipartAnalysisResult) block.mp).hasSupport(world, x, y, z)) {
            pendingBlocks.addLast(block);
            shuffledWithoutProgress++;
            return true;
        }

        PendingBlock existing = PendingBlock.fromBlock(world, x, y, z);

        if (block.spec.isAir() && existing.getBlock().isAir(world, x, y, z)) {
            shuffledWithoutProgress = 0;
            return true;
        }

        // if the existing block is the same as the one we're trying to place, just apply its tile data
        if (PendingBlock.areEquivalent(existing, block)) {
            if (supportsConfiguring()) {
                applyContext.pendingBlock = block;
                block.apply(applyContext, world);
                rememberP2POutputs(block);

                buildable.playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);
                placeBudget--;
            }

            wirelessLinkFixer.tryApply(world, x, y, z);
            shuffledWithoutProgress = 0;

            return true;
        }

        // checks if the existing block is removable
        boolean canPlace = switch (state.config.removeMode) {
            case NONE -> existing.getBlock().isAir(world, x, y, z);
            case REPLACEABLE -> existing.getBlock().isReplaceable(world, x, y, z);
            case ALL -> true;
        };

        canPlace &= existing.getBlock().getBlockHardness(world, x, y, z) >= 0;

        // we don't want to remove these even though they'll never be placed because we want to see how many blocks
        // couldn't be placed
        if (!canPlace) {
            pendingBlocks.addLast(block);
            shuffledWithoutProgress++;
            placeBudget--;
            return true;
        }

        // if there's an existing block then remove it if possible
        if (!existing.getBlock().isAir(world, x, y, z)) {
            if (!state.hasCap(ItemMatterManipulator.ALLOW_REMOVING)) {
                shuffledWithoutProgress = 0;
                return true;
            }
        }

        proxiedWorld.airX = x;
        proxiedWorld.airY = y;
        proxiedWorld.airZ = z;

        // check block dependencies for things like levers
        // if we can't place this block, shuffle it to the back of the list
        if (!block.getBlock().canPlaceBlockAt(proxiedWorld, x, y, z)) {
            pendingBlocks.addLast(block);
            shuffledWithoutProgress++;
            return true;
        }

        long coord = CoordinatePacker.pack(x, y, z);

        if (!visited.add(coord)) {
            MMMod.LOG.warn("Tried to place block twice! " + block);
            shuffledWithoutProgress = 0;
            return true;
        }

        placeBudget--;
        shuffledWithoutProgress = 0;

        toPlaceAmount++;
        toPlace.add(block);

        return true;
    }

    @FSM.Transition(from = State.NAMES.GET_TO_PLACE, to = State.NAMES.EXTRACT_ITEMS)
    protected boolean timeToExtract() {
        if (placeBudget == 0 || pendingBlocks.isEmpty() || shuffledWithoutProgress >= pendingBlocks.size()) {
            placeBudget = 0;
            return true;
        }
        return false;
    }

    @FSM.Event(State.NAMES.EXTRACT_ITEMS)
    protected boolean extractItems() {
        if (itemsExtracted) return true;

        itemsExtracted = true;

        if (toPlace.isEmpty()) {
            if (!pendingBlocks.isEmpty() && shuffledWithoutProgress >= pendingBlocks.size()) {
                shuffledWithoutProgress = 0;
                placeUnsupported = true;
                sendErrorToPlayer(
                    player,
                    "mm.info.error.could_not_place",
                    new ChatComponentNumber(pendingBlocks.size())
                );
            }

            return true;
        }

        shuffledWithoutProgress = 0;
        PendingBlock first = toPlace.getFirst();
        if (first.mp == null) placeUnsupported = false;

        perBlock = first.getStack();

        // if the block we're placing isn't free (ae cable busses) we need to consume it
        if (!first.isFree()) {
            long total = toPlace.size() * (long) perBlock.stackSize;
            extracted = MMItemConsumer.consume(applyContext, BigItemStack.create(perBlock).setStackSize(total));

            if (extracted == null) {
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.could_not_find",
                    new ChatComponentNumber(toPlace.size())
                );
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.of_item",
                    first.getDisplayNameChat(),
                    new ChatComponentNumber(total)
                );

                placeOperation = toPlaceAmount;

                while (!toPlace.isEmpty()) {
                    PendingBlock pending = toPlace.removeFirst();
                    pendingBlocks.add(pending);
                    long coord = CoordinatePacker.pack(pending.x, pending.y, pending.z);
                    visited.remove(coord);
                }
            }
        }

        return true;
    }

    @FSM.Transition(from = State.NAMES.EXTRACT_ITEMS, to = State.NAMES.PLACE_BLOCK)
    protected boolean itemsExtracted() {
        return itemsExtracted;
    }

    @FSM.Event(State.NAMES.PLACE_BLOCK)
    protected boolean placeBlock() {
        if (toPlace.isEmpty()) return true;
        if (placeOperation == toPlaceAmount) return false;

        placeOperation++;

        PendingBlock pending = toPlace.removeFirst();

        int x = pending.x;
        int y = pending.y;
        int z = pending.z;

        buildable.playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);

        int metadata = pending.spec.getBlockMeta();

        BlockSpec existing = BlockSpec.fromBlock(pooled, world, x, y, z);

        if (existing.equals(pending.spec)) {
            // somehow the block already exists, despite us checking to make sure that this shouldn't happen
            // just to be safe, we only consume the item when we actually place something
            if (supportsConfiguring()) {
                applyContext.pendingBlock = pending;
                pending.apply(applyContext, world);
                rememberP2POutputs(pending);
            }

            wirelessLinkFixer.tryApply(world, x, y, z);
            world.notifyBlockOfNeighborChange(x, y, z, Blocks.air);

            return true;
        }

        if (extracted != null && extracted.stackSize < perBlock.stackSize) {
            toPlace.addFirst(pending);
            placeOperation = toPlaceAmount;
            return false;
        }

        // When swapping blocks, only notify the neighbours once the new block is in place, so that things
        // attached to the old block (torches, wires, ...) don't pop off because they briefly see air
        boolean replacing = !existing.isAir() && !pending.spec.isAir();

        // Consume eu first before doing anything
        if (!existing.isAir() && !buildable.tryConsumePower(stack, world, x, y, z, existing)) {
            sendErrorToPlayer(player, "mm.info.error.out_of_eu");
            toPlace.addFirst(pending);
            placeOperation = toPlaceAmount;
            return false;
        }

        if (!pending.spec.isAir() && !buildable.tryConsumePower(stack, world, x, y, z, pending.spec)) {
            sendErrorToPlayer(player, "mm.info.error.out_of_eu");
            toPlace.addFirst(pending);
            placeOperation = toPlaceAmount;
            return false;
        }

        if (!existing.isAir()) {
            buildable.removeBlock(world, x, y, z, existing, !replacing);
        }

        if (!pending.spec.isAir()) {
            Block block = pending.getBlock();

            if (pending.getItem() instanceof ItemBlock itemBlock && Block.getBlockFromItem(itemBlock) == block) {
                boolean placed = itemBlock.placeBlockAt(
                    perBlock,
                    player,
                    player.worldObj,
                    x,
                    y,
                    z,
                    getDefaultPlaceSide(pending.spec).ordinal(),
                    0,
                    0,
                    0,
                    metadata
                );

                if (!placed && replacing) {
                    world.notifyBlocksOfNeighborChange(x, y, z, existing.getBlock());
                }
            } else {
                if (!world.setBlock(x, y, z, block, metadata, 3)) {
                    if (replacing) {
                        world.notifyBlocksOfNeighborChange(x, y, z, existing.getBlock());
                    }

                    return true;
                }

                if (world.getBlock(x, y, z) == block) {
                    block.onBlockPlacedBy(world, x, y, z, player, stack);
                    block.onPostBlockPlaced(world, x, y, z, metadata);
                }
            }
        }

        if (extracted != null) {
            extracted.stackSize -= perBlock.stackSize;
        }

        applyContext.pendingBlock = pending;
        pending.apply(applyContext, world);
        rememberP2POutputs(pending);
        wirelessLinkFixer.tryApply(world, x, y, z);

        placedAmount++;

        return true;
    }

    @FSM.Transition(from = State.NAMES.PLACE_BLOCK, to = State.NAMES.POST_PLACE)
    protected boolean blocksPlaced() {
        return toPlace.isEmpty() || placeBudget != 0;
    }

    @Event(State.NAMES.POST_PLACE)
    protected boolean postPlace() {
        if (placeOperation == -1) return false;

        if (extracted != null && !toPlace.isEmpty()) {
            long missing = toPlace.size() * (long) perBlock.stackSize - extracted.stackSize;

            if (missing > 0) {
                int missingBlock = (int) Math.ceil((double) missing / (double) perBlock.stackSize);

                sendWarningToPlayer(
                    player,
                    "mm.info.warning.could_not_find",
                    new ChatComponentNumber(missingBlock)
                );
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.of_item",
                    toPlace.getFirst().getDisplayNameChat(),
                    new ChatComponentNumber(missing)
                );

                for (int i = 0; i < missingBlock; i++) {
                    PendingBlock pending = toPlace.removeFirst();
                    pendingBlocks.add(pending);
                    long coord = CoordinatePacker.pack(pending.x, pending.y, pending.z);
                    visited.remove(coord);
                }
            }
        }

        placeOperation = -1;

        sendInfoToPlayer(
            player,
            "mm.info.placed_remaining",
            new ChatComponentNumber(placedAmount),
            new ChatComponentNumber(getRemainingAmount())
        );

        if (extracted != null && extracted.stackSize > 0) {
            long leftover = toPlace.size() * (long) perBlock.stackSize;
            if (extracted.stackSize > leftover) {
                long placed = placedAmount * (long) perBlock.stackSize;
                long total = toPlaceAmount * (long) perBlock.stackSize;
                // extra stuff left over somehow
                MMMod.LOG.error(
                    "Didn't consume enough items! " + perBlock
                        .getDisplayName() + "; expected to consume " + placed + ", but consumed " + (total - extracted.stackSize)
                );
            }
            buildable.givePlayerItems(extracted.toStacks().toArray(new ItemStack[0]));
        }

        if (Mods.AppliedEnergistics2.isModLoaded()) convertSourceInterfaces(world);

        buildable.actuallyGivePlayerStuff();
        buildable.playSounds();

        return true;
    }

    @FSM.Transition(from = State.NAMES.POST_PLACE, to = State.NAMES.INIT_TO_PLACE)
    protected boolean placeAgain() {
        return placeOperation == -1 && getRemainingAmount() > 0 && placeBudget != 0;
    }

    @FSM.Transition(from = State.NAMES.POST_PLACE, to = State.NAMES.COMPLETE)
    protected boolean placeComplete() {
        return placeOperation == -1 && getRemainingAmount() == 0;
    }

    @FSM.Event(State.NAMES.COMPLETE)
    protected boolean completeBuild() {
        finishBuild();
        sendInfoToPlayer(player, "mm.info.finished_placing");
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE, to = State.NAMES.FINISH)
    protected boolean buildComplete() {
        return finished;
    }

    private void rememberP2POutputs(PendingBlock applied) {
        if (applied.smartCopy != null && applied.smartCopy.action == SmartCopyIntegration.SmartCopyAction.INTERFACE_TO_P2P) {
            appliedP2POutputs.add(applied);
        }
    }

    /** Array copies share their sources, so each one is converted once, after one of its outputs exists. */
    @Optional(Names.APPLIED_ENERGISTICS2)
    private void convertSourceInterfaces(World world) {
        Set<Pair<Long, ForgeDirection>> triedThisTick = new HashSet<>();
        for (PendingBlock applied : appliedP2POutputs) {
            if (applied.smartCopy.p2pActions == null) continue;
            for (SmartCopyIntegration.P2PInfo info : applied.smartCopy.p2pActions) {
                if (!hasP2POutput(world, applied, info)) continue;
                Pair<Long, ForgeDirection> source = Pair.of(CoordinatePacker.pack(info.srcX, info.srcY, info.srcZ), info.srcSide);
                if (convertedSources.contains(source) || !triedThisTick.add(source)) continue;
                if (convertSourceInterface(world, info)) convertedSources.add(source);
            }
        }
        appliedP2POutputs.clear();
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static boolean hasP2POutput(World world, PendingBlock applied, SmartCopyIntegration.P2PInfo info) {
        ForgeDirection destSide = info.destSide != null ? info.destSide : info.srcSide;
        if (destSide == null) return false;
        if (!(world.getTileEntity(applied.x, applied.y, applied.z) instanceof IPartHost partHost)) return false;
        return partHost.getPart(destSide) instanceof PartP2PTunnel<?> tunnel && tunnel.isOutput() && tunnel.getFrequency() == info.freq;
    }

    /** @return false to try again later */
    @Optional(Names.APPLIED_ENERGISTICS2)
    private boolean convertSourceInterface(World world, SmartCopyIntegration.P2PInfo info) {
        int x = info.srcX, y = info.srcY, z = info.srcZ;
        ForgeDirection side = info.srcSide;
        if (side == null || side == ForgeDirection.UNKNOWN) return true;
        if (!buildable.isEditable(world, x, y, z, false)) return true;

        IPartHost partHost = world.getTileEntity(x, y, z) instanceof IPartHost host ? host : null;
        IPart existing = partHost == null ? null : partHost.getPart(side);
        if (existing == null || info.sourcePart == null || !isSamePart(existing, info.sourcePart)) {
            sendSourceWarning(x, y, z, new ChatComponentTranslation("mm.info.warning.p2p_source_changed"));
            return true;
        }

        ItemStack p2pStack = info.p2pItem.toStack();
        if (!buildable.tryConsumeItems(p2pStack)) {
            sendSourceWarning(x, y, z, new ChatComponentTranslation("mm.info.warning.could_not_find_item", new ChatComponentItemName(p2pStack)));
            return false;
        }

        NBTTagCompound settings = existing instanceof AEBasePart aePart ? aePart.downloadSettings(SettingsFrom.MEMORY_CARD) : null;
        List<ItemStack> upgrades = takeInventory(existing, "upgrades");
        List<ItemStack> patterns = takeInventory(existing, "patterns");
        AEAnalysisResult.removePart(buildable, partHost, side, false);

        if (partHost.addPart(p2pStack, side, player) == null) {
            buildable.givePlayerItems(p2pStack);
            buildable.givePlayerItems(upgrades.toArray(new ItemStack[0]));
            buildable.givePlayerItems(patterns.toArray(new ItemStack[0]));
            sendSourceWarning(x, y, z, new ChatComponentTranslation("mm.info.warning.could_not_place_p2p_source"));
            return true;
        }

        IPart p2p = partHost.getPart(side);
        if (p2p instanceof PartP2PTunnel<?> tunnel) {
            tunnel.output = false;
            try {
                tunnel.getProxy().getP2P().updateFreq(tunnel, info.freq);
            } catch (GridAccessException e) {
                tunnel.setFrequency(info.freq);
            }
            tunnel.onTunnelConfigChange();
        }

        putInventory(p2p, "upgrades", upgrades);
        if (settings != null && p2p instanceof AEBasePart aePart) aePart.uploadSettings(SettingsFrom.MEMORY_CARD, settings);
        putInventory(p2p, "patterns", patterns);
        buildable.playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);
        return true;
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static boolean isSamePart(IPart part, PortableItemStack expected) {
        return ItemId.createWithoutNBT(part.getItemStack(PartItemStack.Break))
            .equals(ItemId.createWithoutNBT(expected.toStack()));
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private static List<ItemStack> takeInventory(IPart part, String name) {
        List<ItemStack> taken = new ArrayList<>();
        IInventory inv = part instanceof ISegmentedInventory segmented ? segmented.getInventoryByName(name) : null;
        if (inv == null) return taken;
        for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
            ItemStack item = inv.getStackInSlot(slot);
            if (item != null) {
                taken.add(item);
                inv.setInventorySlotContents(slot, null);
            }
        }
        return taken;
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private void putInventory(IPart part, String name, List<ItemStack> stacks) {
        IInventory inv = part instanceof ISegmentedInventory segmented ? segmented.getInventoryByName(name) : null;
        for (ItemStack item : stacks) {
            int slot = inv == null ? -1 : findFreeSlot(inv, item);
            if (slot == -1)
                buildable.givePlayerItems(item);
            else
                inv.setInventorySlotContents(slot, item);
        }
    }

    private static int findFreeSlot(IInventory inv, ItemStack stack) {
        for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
            if (inv.getStackInSlot(slot) == null && inv.isItemValidForSlot(slot, stack)) return slot;
        }
        return -1;
    }

    private void sendSourceWarning(int x, int y, int z, IChatComponent message) {
        sendWarningToPlayer(player, "mm.info.warning.only_message", x, y, z, message);
    }

    private boolean supportsConfiguring() {
        if (state.hasCap(ItemMatterManipulator.ALLOW_CONFIGURING)) return true;
        if (state.config.placeMode == PlaceMode.EXCHANGING) return true;
        return state.config.placeMode == PlaceMode.CABLES;
    }

    private ForgeDirection getDefaultPlaceSide(ImmutableBlockSpec spec) {
        if (Mods.GregTech.isModLoaded() && MMUtils.isGTCable(spec)) return ForgeDirection.UNKNOWN;
        return ForgeDirection.NORTH;
    }

    public class FSMBuildApplyContext implements IBlockApplyContext {

        public static final double EU_PER_ACTION = 8192;

        public ItemStack manipulatorItemStack;
        public MMState manipulatorState;
        public PendingBlock pendingBlock;

        public FSMBuildApplyContext(ItemStack manipulatorItemStack) {
            this.manipulatorItemStack = manipulatorItemStack;
            this.manipulatorState = ItemMatterManipulator.getState(manipulatorItemStack);
        }

        @Override
        public World getWorld() {
            return world;
        }

        @Override
        public int getX() {
            return pendingBlock.x;
        }

        @Override
        public int getY() {
            return pendingBlock.y;
        }

        @Override
        public int getZ() {
            return pendingBlock.z;
        }

        @Override
        public TileEntity getTileEntity() {
            if (pendingBlock.isInWorld(world)) return world.getTileEntity(pendingBlock.x, pendingBlock.y, pendingBlock.z);
            return null;
        }

        @Override
        public EntityPlayer getRealPlayer() {
            return player;
        }

        @Override
        public MMConfig getConfig() {
            return manipulatorState.config;
        }

        @Override
        public boolean tryApplyAction(double complexity) {
            return buildable.tryConsumePower(
                manipulatorItemStack,
                pendingBlock.x,
                pendingBlock.y,
                pendingBlock.z,
                EU_PER_ACTION * complexity
            );
        }

        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            return buildable.tryConsumeItems(items, flags);
        }

        @Override
        public void givePlayerItems(List<BigItemStack> items) {
            buildable.givePlayerItems(items);
        }

        @Override
        public void givePlayerFluids(List<BigFluidStack> fluids) {
            buildable.givePlayerFluids(fluids);
        }

        @Override
        public void warn(IChatComponent message) {
            IChatComponent blockNameComponent = null;
            if (pendingBlock.isInWorld(world)) {
                String gtBlockNameKey = GregTech.isModLoaded() ? getGTBlockUnlocalizedName(pendingBlock) : null;
                if (gtBlockNameKey == null) {
                    BlockSpec spec = BlockSpec.fromBlock(null, world, pendingBlock.x, pendingBlock.y, pendingBlock.z);
                    blockNameComponent = InteropConstants.AE_BLOCK_CABLE.matches(spec) ?
                        InteropConstants.AE_BLOCK_CABLE.toSpec().getChatComponent() :
                        spec.getChatComponent();
                } else {
                    blockNameComponent = new ChatComponentText(gtBlockNameKey);
                }
            }
            if (blockNameComponent != null) {
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.with_block",
                    pendingBlock.x,
                    pendingBlock.y,
                    pendingBlock.z,
                    blockNameComponent,
                    message
                );
            } else {
                sendWarningToPlayer(player, "mm.info.warning.only_message", pendingBlock.x, pendingBlock.y, pendingBlock.z, message);
            }
            warnings.add(CoordinatePacker.pack(pendingBlock.x, pendingBlock.y, pendingBlock.z));
        }

        @Override
        public void error(IChatComponent message) {
            IChatComponent blockNameComponent = null;
            if (pendingBlock.isInWorld(world)) {
                String gtBlockNameKey = GregTech.isModLoaded() ? getGTBlockUnlocalizedName(pendingBlock) : null;
                BlockSpec spec = BlockSpec.fromBlock(null, world, pendingBlock.x, pendingBlock.y, pendingBlock.z);
                if (gtBlockNameKey == null) {
                    blockNameComponent = InteropConstants.AE_BLOCK_CABLE.matches(spec) ?
                        InteropConstants.AE_BLOCK_CABLE.toSpec().getChatComponent() :
                        spec.getChatComponent();
                } else {
                    blockNameComponent = new ChatComponentText(gtBlockNameKey);
                }
            }
            if (blockNameComponent != null) {
                sendErrorToPlayer(
                    player,
                    "mm.info.error.with_block",
                    pendingBlock.x,
                    pendingBlock.y,
                    pendingBlock.z,
                    blockNameComponent,
                    message
                );
            } else {
                sendErrorToPlayer(player, "mm.info.error.only_message", pendingBlock.x, pendingBlock.y, pendingBlock.z, message);
            }
            errors.add(CoordinatePacker.pack(pendingBlock.x, pendingBlock.y, pendingBlock.z));
        }
    }

    @Optional(Names.GREG_TECH_NH)
    private String getGTBlockUnlocalizedName(PendingBlock pendingBlock) {
        if (world.getTileEntity(pendingBlock.x, pendingBlock.y, pendingBlock.z) instanceof IGregTechTileEntity igte) {
            IMetaTileEntity imte = igte.getMetaTileEntity();
            if (imte != null) {
                String key = "gt.blockmachines." + imte.getMetaName() + ".name";
                return StatCollector.canTranslate(key) ? key : imte.getLocalName();
            }
        }
        return null;
    }

}
