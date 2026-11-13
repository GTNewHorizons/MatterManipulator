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
        PLACE_BLOCK,
        COMPLETE,
        FINISH;

        public static class NAMES {

            public static final String INIT_ANALYZER = "INIT_ANALYZER";
            public static final String WAIT_FOR_ANALYZER = "WAIT_FOR_ANALYZER";
            public static final String INIT_BUILD = "INIT_BUILD";
            public static final String INIT_WIRELESS_FIXER = "INIT_WIRELESS_FIXER";
            public static final String PLACE_BLOCK = "PLACE_BLOCK";
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
    protected Deque<PendingBlock> pendingBlocks;
    protected WirelessLinkFixer wirelessLinkFixer;
    protected BlockSpec pooled;
    protected ProxiedWorld proxiedWorld;
    protected FSMBuildApplyContext applyContext;

    protected int placeBudget;
    protected int placedAmount;
    protected int shuffledWithoutProgress;

    private boolean finished;
    private long lastPlaceTick;
    private final Set<Long> visited = new HashSet<>();
    private final LongList errors = new LongArrayList();
    private final LongList warnings = new LongArrayList();
    private final List<PendingBlock> appliedP2POutputs = new ArrayList<>();
    private final Set<Pair<Long, ForgeDirection>> convertedSources = new HashSet<>();

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
        if (getScheduler() == scheduler) return;

        super.onScheduled(scheduler);

        if (scheduler == null || !hasWork()) return;
        if (analyzer != null && analyzer.hasWork()) scheduler.scheduleWork(analyzer);
    }

    @Override
    public void onUnscheduled() {
        unscheduleChild(analyzer);
    }

    public void resetBudget() {
        placeBudget = tier.placeSpeed;
        placedAmount = 0;
    }

    public boolean isStalled() {
        return shuffledWithoutProgress >= pendingBlocks.size();
    }

    public int getAnalyzedAmount() {
        return analyzer == null ? 0 : analyzer.blocks.size();
    }

    public int getPlacedAmount() {
        return placedAmount;
    }

    public int getRemainingAmount() {
        if (pendingBlocks != null) return pendingBlocks.size();
        return analyzer == null ? 0 : analyzer.blocks.size();
    }

    public void finishBuild() {
        if (finished) return;
        if (buildable == null) return;

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

    @FSM.Transition(from = State.NAMES.WAIT_FOR_ANALYZER, to = State.NAMES.INIT_BUILD)
    protected boolean analyzerComplete() {
        return !analyzer.hasWork();
    }

    @FSM.Event(State.NAMES.INIT_BUILD)
    protected boolean initBuild() {
        pendingBlocks = new ArrayDeque<>(analyzer.blocks);
        wirelessLinkFixer = new WirelessLinkFixer(state);
        pooled = new BlockSpec();
        proxiedWorld = new ProxiedWorld(world);
        applyContext = new FSMBuildApplyContext(stack);
        buildable = new AbstractBuildable(player, state, tier);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_BUILD, to = State.NAMES.INIT_WIRELESS_FIXER)
    protected boolean buildInitialized() {
        return pendingBlocks != null;
    }

    @FSM.Event(State.NAMES.INIT_WIRELESS_FIXER)
    protected boolean initWirelessFixer() {
        wirelessLinkFixer.tryInit(world);
        return true;
    }

    @FSM.Transition(from = State.NAMES.INIT_WIRELESS_FIXER, to = State.NAMES.PLACE_BLOCK)
    protected boolean wirelessFixerInitialized() {
        return true;
    }

    @FSM.Event(State.NAMES.PLACE_BLOCK)
    protected boolean placeBlock() {
        if (pendingBlocks.isEmpty()) return true;
        if (placeBudget <= 0) return false;

        if (getLastWorkingTick() != lastPlaceTick) {
            buildable.resetWarnings();
            buildable.refillPower(stack);
            buildable.actuallyGivePlayerStuff();
        }

        lastPlaceTick = getLastWorkingTick();

        PendingBlock next = pendingBlocks.getFirst();
        int x = next.x, y = next.y, z = next.z;

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!world.getChunkProvider().chunkExists(chunkX, chunkZ)) {
            takePendingBlock();
            return true;
        }

        if (y < 0 || y > 255 || (next.spec.isAir() && world.isAirBlock(x, y, z))) {
            takePendingBlock();
            return true;
        }

        if (!buildable.isEditable(world, x, y, z, true)) {
            takePendingBlock();
            return true;
        }

        if (next.mp != null && !isStalled() && !((MultipartAnalysisResult) next.mp).hasSupport(world, x, y, z)) { return rotatePendingBlock(); }

        PendingBlock existing = PendingBlock.fromBlock(world, x, y, z);
        if (next.spec.isAir() && existing.getBlock().isAir(world, x, y, z)) {
            takePendingBlock();
            return true;
        }

        existing.analyze(world.getTileEntity(x, y, z), PendingBlock.ANALYZE_ARCH);
        if (PendingBlock.areEquivalent(existing, next)) {
            PendingBlock block = takePendingBlock();
            if (supportsConfiguring()) {
                applyContext.pendingBlock = block;
                block.apply(applyContext, world);
                rememberP2POutputs(block);
                buildable.playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);
            }
            wirelessLinkFixer.tryApply(world, x, y, z);
            return true;
        }

        boolean canPlace = switch (state.config.removeMode) {
            case NONE -> existing.getBlock().isAir(world, x, y, z);
            case REPLACEABLE -> existing.getBlock().isReplaceable(world, x, y, z);
            case ALL -> true;
        };
        canPlace &= existing.getBlock().getBlockHardness(world, x, y, z) >= 0;
        if (!canPlace) return rotatePendingBlock();

        if (!existing.getBlock().isAir(world, x, y, z)) {
            if (!state.hasCap(ItemMatterManipulator.ALLOW_REMOVING)) {
                takePendingBlock();
                return true;
            }
            if (!buildable.tryConsumePower(stack, world, x, y, z, existing.spec)) {
                sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                return false;
            }
        }

        proxiedWorld.airX = x;
        proxiedWorld.airY = y;
        proxiedWorld.airZ = z;
        if (!next.getBlock().canPlaceBlockAt(proxiedWorld, x, y, z)) return rotatePendingBlock();

        if (!buildable.tryConsumePower(stack, world, x, y, z, next.spec)) {
            sendErrorToPlayer(player, "mm.info.error.out_of_eu");
            return false;
        }

        long coord = CoordinatePacker.pack(x, y, z);
        if (!visited.add(coord)) {
            MMMod.LOG.warn("Tried to place block twice! " + next);
            takePendingBlock();
            return true;
        }

        PendingBlock pending = takePendingBlock();
        buildable.playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);

        int metadata = pending.spec.getBlockMeta();
        BlockSpec existingSpec = BlockSpec.fromBlock(pooled, world, x, y, z);
        if (existingSpec.equals(pending.spec)) {
            if (supportsConfiguring()) {
                applyContext.pendingBlock = pending;
                pending.apply(applyContext, world);
                rememberP2POutputs(pending);
            }
            wirelessLinkFixer.tryApply(world, x, y, z);
            world.notifyBlockOfNeighborChange(x, y, z, Blocks.air);
            finishPlaceBlock();
            return true;
        }

        if (!pending.isFree()) {
            ItemStack perBlock = pending.getStack();
            BigItemStack extracted = MMItemConsumer.consume(applyContext, BigItemStack.create(perBlock));
            if (extracted == null || extracted.stackSize < perBlock.stackSize) {
                if (extracted != null) buildable.givePlayerItems(extracted.toStacks().toArray(new ItemStack[0]));
                sendWarningToPlayer(player, "mm.info.warning.could_not_find", 1);
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.of_item",
                    pending.getDisplayNameChat(),
                    perBlock.stackSize
                );
                pendingBlocks.addFirst(pending);
                visited.remove(coord);
                return false;
            }
        }

        boolean replacing = !existingSpec.isAir() && !pending.spec.isAir();
        if (!existingSpec.isAir()) {
            buildable.removeBlock(world, x, y, z, existingSpec, !replacing);
        }

        if (!pending.spec.isAir()) {
            Block block = pending.getBlock();
            if (pending.getItem() instanceof ItemBlock itemBlock && Block.getBlockFromItem(itemBlock) == block) {
                boolean placed = itemBlock.placeBlockAt(
                    pending.getStack(),
                    player,
                    world,
                    x,
                    y,
                    z,
                    getDefaultPlaceSide(pending.spec).ordinal(),
                    0,
                    0,
                    0,
                    metadata
                );
                if (!placed && replacing) world.notifyBlocksOfNeighborChange(x, y, z, existingSpec.getBlock());
            } else {
                if (!world.setBlock(x, y, z, block, metadata, 3)) {
                    if (replacing) world.notifyBlocksOfNeighborChange(x, y, z, existingSpec.getBlock());
                    if (!pending.isFree()) buildable.givePlayerItems(pending.getStack());
                    return true;
                }
                if (world.getBlock(x, y, z) == block) {
                    block.onBlockPlacedBy(world, x, y, z, player, stack);
                    block.onPostBlockPlaced(world, x, y, z, metadata);
                }
            }
        }

        applyContext.pendingBlock = pending;
        pending.apply(applyContext, world);
        rememberP2POutputs(pending);
        wirelessLinkFixer.tryApply(world, x, y, z);

        finishPlaceBlock();

        return true;
    }

    @FSM.Transition(from = State.NAMES.PLACE_BLOCK, to = State.NAMES.COMPLETE)
    protected boolean blocksPlaced() {
        return pendingBlocks.isEmpty();
    }

    @FSM.Event(State.NAMES.COMPLETE)
    protected boolean completeBuild() {
        finishBuild();
        sendInfoToPlayer(player, "mm.info.finished_placing");
        return true;
    }

    @FSM.Transition(from = State.NAMES.COMPLETE, to = State.NAMES.FINISH)
    protected boolean buildComplete() {
        return true;
    }

    private boolean rotatePendingBlock() {
        pendingBlocks.addLast(pendingBlocks.removeFirst());
        shuffledWithoutProgress++;
        return !isStalled();
    }

    private void finishPlaceBlock() {
        buildable.playSounds();
        if (Mods.AppliedEnergistics2.isModLoaded()) convertSourceInterfaces(world);

        placeBudget--;
        placedAmount++;
    }

    private PendingBlock takePendingBlock() {
        shuffledWithoutProgress = 0;
        return pendingBlocks.removeFirst();
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
