package com.recursive_pineapple.matter_manipulator.common.building;

import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendErrorToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendInfoToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.MMUtils.sendWarningToPlayer;
import static com.recursive_pineapple.matter_manipulator.common.utils.Mods.GregTech;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator.ManipulatorTier;
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

import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Handles all building logic.
 */
public class PendingBuild extends AbstractBuildable {

    private final Deque<PendingBlock> pendingBlocks;
    private final HashSet<Long> visited = new HashSet<>();

    private final LongList errors = new LongArrayList();
    private final LongList warnings = new LongArrayList();

    private final WirelessLinkFixer wirelessLinkFixer;

    private final List<PendingBlock> appliedP2POutputs = new ArrayList<>();
    private final Set<Pair<Long, ForgeDirection>> convertedSources = new HashSet<>();

    public PendingBuild(
        EntityPlayer player,
        MMState state,
        ManipulatorTier tier,
        List<PendingBlock> pendingBlocks
    ) {
        super(player, state, tier);
        this.pendingBlocks = new ArrayDeque<>(pendingBlocks);
        this.wirelessLinkFixer = new WirelessLinkFixer(state);
    }

    @Override
    public void tryPlaceBlocks(ItemStack stack, EntityPlayer player) {
        resetWarnings();
        refillPower(stack);

        List<PendingBlock> toPlace = new ArrayList<>(tier.placeSpeed);

        Integer lastChunkX = null, lastChunkZ = null;
        int shuffleCount = 0;

        World world = player.worldObj;

        wirelessLinkFixer.tryInit(world);

        ProxiedWorld proxiedWorld = new ProxiedWorld(world);

        PendingBuildApplyContext applyContext = new PendingBuildApplyContext(stack);

        BlockSpec pooled = new BlockSpec();

        // check every pending block that's left
        while (toPlace.size() < tier.placeSpeed && !pendingBlocks.isEmpty()) {
            PendingBlock next = pendingBlocks.getFirst();

            int x = next.x, y = next.y, z = next.z;

            int chunkX = x >> 4;
            int chunkZ = z >> 4;

            // if this block's chunk isn't loaded, ignore it completely
            if (!Objects.equals(chunkX, lastChunkX) || !Objects.equals(chunkZ, lastChunkZ)) {
                if (!world.getChunkProvider().chunkExists(chunkX, chunkZ)) {
                    pendingBlocks.removeFirst();
                    continue;
                } else {
                    lastChunkX = chunkX;
                    lastChunkZ = chunkZ;
                }
            }

            if (y < 0 || y > 255) {
                pendingBlocks.removeFirst();
                continue;
            }

            // if this block is protected, ignore it completely and print a warning
            if (!isEditable(world, x, y, z, true)) {
                pendingBlocks.removeFirst();
                continue;
            }

            // if this block is different from the last one, stop checking blocks
            // since the pending blocks are sorted by their contained block, this is usually true
            if (!toPlace.isEmpty() && !next.spec.isEquivalent(toPlace.get(0).spec)) {
                break;
            }

            PendingBlock existing = PendingBlock.fromBlock(world, x, y, z);

            if (next.spec.isAir() && existing.getBlock().isAir(world, x, y, z)) {
                pendingBlocks.removeFirst();
                continue;
            }

            existing.analyze(world.getTileEntity(x, y, z), PendingBlock.ANALYZE_ARCH);

            // if the existing block is the same as the one we're trying to place, just apply its tile data
            if (PendingBlock.areEquivalent(existing, next)) {
                PendingBlock block = pendingBlocks.removeFirst();

                if (supportsConfiguring()) {
                    applyContext.pendingBlock = block;
                    block.apply(applyContext, world);
                    rememberP2POutputs(block);
                    playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);
                }

                wirelessLinkFixer.tryApply(world, x, y, z);

                continue;
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
                pendingBlocks.addLast(pendingBlocks.removeFirst());
                shuffleCount++;

                if (shuffleCount > pendingBlocks.size()) {
                    break;
                } else {
                    continue;
                }
            }

            // if there's an existing block then remove it if possible
            if (!existing.getBlock().isAir(world, x, y, z)) {
                if (!state.hasCap(ItemMatterManipulator.ALLOW_REMOVING)) {
                    pendingBlocks.removeFirst();
                    continue;
                }

                if (!tryConsumePower(stack, world, x, y, z, existing.spec)) {
                    sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                    break;
                }
            }

            proxiedWorld.airX = x;
            proxiedWorld.airY = y;
            proxiedWorld.airZ = z;

            // check block dependencies for things like levers
            // if we can't place this block, shuffle it to the back of the list
            if (!next.getBlock().canPlaceBlockAt(proxiedWorld, next.x, next.y, next.z)) {
                pendingBlocks.addLast(pendingBlocks.removeFirst());
                shuffleCount++;

                // if we've shuffled every block, then we'll never be able to place any of them
                if (shuffleCount > pendingBlocks.size()) {
                    break;
                } else {
                    continue;
                }
            }

            if (!tryConsumePower(stack, world, x, y, z, next.spec)) {
                sendErrorToPlayer(player, "mm.info.error.out_of_eu");
                break;
            }

            long coord = CoordinatePacker.pack(x, y, z);

            if (!visited.add(coord)) {
                MMMod.LOG.warn("Tried to place block twice! " + next);
                pendingBlocks.removeFirst();
                continue;
            }

            toPlace.add(pendingBlocks.removeFirst());
        }

        // check if we could place any blocks
        if (toPlace.isEmpty()) {
            if (!pendingBlocks.isEmpty()) {
                sendErrorToPlayer(
                    player,
                    "mm.info.error.could_not_place",
                    pendingBlocks.size()
                );
            } else {
                sendInfoToPlayer(player, "mm.info.finished_placing");
            }

            if (Mods.AppliedEnergistics2.isModLoaded()) convertSourceInterfaces(world);

            actuallyGivePlayerStuff();
            playSounds();
            return;
        }

        PendingBlock first = toPlace.get(0);

        ItemStack perBlock = first.getStack();
        long total = 0;
        BigItemStack extracted = null;

        // if the block we're placing isn't free (ae cable busses) we need to consume it
        if (!first.isFree()) {
            total = toPlace.size() * (long) perBlock.stackSize;
            extracted = MMItemConsumer.consume(applyContext, BigItemStack.create(perBlock).setStackSize(total));

            if (extracted == null) {
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.could_not_find",
                    toPlace.size()
                );
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.of_item",
                    first.getDisplayNameChat(),
                    total
                );

                for (PendingBlock pending : toPlace) {
                    pendingBlocks.add(pending);

                    long coord = CoordinatePacker.pack(pending.x, pending.y, pending.z);

                    visited.remove(coord);
                }

                toPlace.clear();
            }
        }

        int i = 0;
        for (; i < toPlace.size(); i++) {
            PendingBlock pending = toPlace.get(i);

            int x = pending.x;
            int y = pending.y;
            int z = pending.z;

            playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);

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
                continue;
            }

            if (extracted != null && extracted.stackSize < perBlock.stackSize) {
                break;
            }

            if (!existing.isAir()) {
                removeBlock(world, x, y, z, existing);
            }

            if (!pending.spec.isAir()) {
                Block block = pending.getBlock();

                if (pending.getItem() instanceof ItemBlock itemBlock && Block.getBlockFromItem(itemBlock) == block) {
                    itemBlock.placeBlockAt(
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
                } else {
                    if (!world.setBlock(x, y, z, block, metadata, 3)) {
                        continue;
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
        }

        if (extracted != null && i < toPlace.size()) {
            sendWarningToPlayer(
                player,
                "mm.info.warning.could_not_find",
                toPlace.size() - i
            );
            sendWarningToPlayer(
                player,
                "mm.info.warning.of_item",
                first.getDisplayNameChat(),
                total - (toPlace.size() - i) * perBlock.stackSize
            );
        }

        sendInfoToPlayer(
            player,
            "mm.info.placed_remaining",
            i,
            pendingBlocks.size()
        );

        if (extracted != null && extracted.stackSize >= perBlock.stackSize) {
            // extra stuff left over somehow
            MMMod.LOG.error(
                "Didn't consume enough items! " + perBlock
                    .getDisplayName() + "; expected to consume " + total + ", but consumed " + (total - extracted.stackSize)
            );
            givePlayerItems(extracted.toStacks().toArray(new ItemStack[0]));
        }

        for (; i < toPlace.size(); i++) {
            PendingBlock pending = toPlace.get(i);

            pendingBlocks.add(pending);

            long coord = CoordinatePacker.pack(pending.x, pending.y, pending.z);

            visited.remove(coord);
        }

        if (Mods.AppliedEnergistics2.isModLoaded()) convertSourceInterfaces(world);

        actuallyGivePlayerStuff();
        playSounds();
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
        int x = info.srcX;
        int y = info.srcY;
        int z = info.srcZ;
        ForgeDirection side = info.srcSide;

        if (side == null || side == ForgeDirection.UNKNOWN) return true;

        if (!isEditable(world, x, y, z, false)) return true;

        IPartHost partHost = world.getTileEntity(x, y, z) instanceof IPartHost host ? host : null;
        IPart existing = partHost == null ? null : partHost.getPart(side);

        // An earlier paste of the same copy may have converted it already
        if (existing == null || info.sourcePart == null || !isSamePart(existing, info.sourcePart)) {
            sendSourceWarning(x, y, z, new ChatComponentTranslation("mm.info.warning.p2p_source_changed"));
            return true;
        }

        ItemStack p2pStack = info.p2pItem.toStack();

        if (!tryConsumeItems(p2pStack)) {
            sendSourceWarning(x, y, z, new ChatComponentTranslation("mm.info.warning.could_not_find_item", new ChatComponentItemName(p2pStack)));
            return false;
        }

        NBTTagCompound settings = existing instanceof AEBasePart aePart ? aePart.downloadSettings(SettingsFrom.MEMORY_CARD) : null;
        List<ItemStack> upgrades = takeInventory(existing, "upgrades");
        List<ItemStack> patterns = takeInventory(existing, "patterns");

        AEAnalysisResult.removePart(this, partHost, side, false);

        if (partHost.addPart(p2pStack, side, player) == null) {
            givePlayerItems(p2pStack);
            givePlayerItems(upgrades.toArray(new ItemStack[0]));
            givePlayerItems(patterns.toArray(new ItemStack[0]));
            sendSourceWarning(x, y, z, new ChatComponentTranslation("mm.info.warning.could_not_place_p2p_source"));
            return true;
        }

        IPart p2p = partHost.getPart(side);

        if (p2p instanceof PartP2PTunnel<?> tunnel) {
            tunnel.output = false;

            try {
                tunnel.getProxy()
                    .getP2P()
                    .updateFreq(tunnel, info.freq);
            } catch (GridAccessException e) {
                tunnel.setFrequency(info.freq);
            }

            tunnel.onTunnelConfigChange();
        }

        // Capacity cards add pattern slots
        putInventory(p2p, "upgrades", upgrades);
        if (settings != null && p2p instanceof AEBasePart aePart) aePart.uploadSettings(SettingsFrom.MEMORY_CARD, settings);
        putInventory(p2p, "patterns", patterns);

        playSound(world, x, y, z, SoundResource.MOB_ENDERMEN_PORTAL);

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
            ItemStack stack = inv.getStackInSlot(slot);

            if (stack != null) {
                taken.add(stack);
                inv.setInventorySlotContents(slot, null);
            }
        }

        return taken;
    }

    @Optional(Names.APPLIED_ENERGISTICS2)
    private void putInventory(IPart part, String name, List<ItemStack> stacks) {
        IInventory inv = part instanceof ISegmentedInventory segmented ? segmented.getInventoryByName(name) : null;

        for (ItemStack stack : stacks) {
            int slot = inv == null ? -1 : findFreeSlot(inv, stack);

            if (slot == -1) {
                givePlayerItems(stack);
            } else {
                inv.setInventorySlotContents(slot, stack);
            }
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

    @Override
    public void onStopped() {
        if (pendingItems.size() > 0 || pendingFluids.size() > 0) {
            MMMod.LOG.error("Build stopped without delivering all items! There's a bug somewhere!");
        }

        actuallyGivePlayerStuff();

        Messages.BuildStatus.sendToPlayer((EntityPlayerMP) player, Pair.of(errors, warnings));
    }

    private boolean supportsConfiguring() {
        // self-explanatory
        if (state.hasCap(ItemMatterManipulator.ALLOW_CONFIGURING)) return true;

        // lower tiers support cables, but not copying
        // since exchanging or placing cables requires configuring, we need to return true for these two
        if (state.config.placeMode == PlaceMode.EXCHANGING) return true;
        if (state.config.placeMode == PlaceMode.CABLES) return true;

        return false;
    }

    private ForgeDirection getDefaultPlaceSide(ImmutableBlockSpec spec) {
        if (Mods.GregTech.isModLoaded() && MMUtils.isGTCable(spec)) { return ForgeDirection.UNKNOWN; }

        return ForgeDirection.NORTH;
    }

    public class PendingBuildApplyContext implements IBlockApplyContext {

        public static final double EU_PER_ACTION = 8192;

        public ItemStack manipulatorItemStack;
        public MMState manipulatorState;
        public PendingBlock pendingBlock;

        public PendingBuildApplyContext(ItemStack manipulatorItemStack) {
            this.manipulatorItemStack = manipulatorItemStack;
            this.manipulatorState = ItemMatterManipulator.getState(manipulatorItemStack);
        }

        @Override
        public World getWorld() {
            return player.worldObj;
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
            if (pendingBlock.isInWorld(player.worldObj)) {
                return player.worldObj.getTileEntity(pendingBlock.x, pendingBlock.y, pendingBlock.z);
            } else {
                return null;
            }
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
            return PendingBuild.this.tryConsumePower(
                manipulatorItemStack,
                pendingBlock.x,
                pendingBlock.y,
                pendingBlock.z,
                EU_PER_ACTION * complexity
            );
        }

        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            return PendingBuild.this.tryConsumeItems(items, flags);
        }

        @Override
        public void givePlayerItems(List<BigItemStack> items) {
            PendingBuild.this.givePlayerItems(items);
        }

        @Override
        public void givePlayerFluids(List<BigFluidStack> fluids) {
            PendingBuild.this.givePlayerFluids(fluids);
        }

        @Override
        public void warn(IChatComponent message) {
            IChatComponent blockNameComponent = null;
            if (pendingBlock.isInWorld(player.worldObj)) {
                String gtBlockNameKey = null;
                if (GregTech.isModLoaded()) gtBlockNameKey = getGTBlockUnlocalizedName(pendingBlock);

                if (gtBlockNameKey == null) {
                    BlockSpec spec = BlockSpec.fromBlock(null, player.worldObj, pendingBlock.x, pendingBlock.y, pendingBlock.z);
                    if (InteropConstants.AE_BLOCK_CABLE.matches(spec)) {
                        blockNameComponent = InteropConstants.AE_BLOCK_CABLE.toSpec().getChatComponent();
                    } else {
                        blockNameComponent = spec.getChatComponent();
                    }
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
                sendWarningToPlayer(
                    player,
                    "mm.info.warning.only_message",
                    pendingBlock.x,
                    pendingBlock.y,
                    pendingBlock.z,
                    message
                );
            }

            PendingBuild.this.warnings.add(CoordinatePacker.pack(pendingBlock.x, pendingBlock.y, pendingBlock.z));
        }

        @Override
        public void error(IChatComponent message) {
            IChatComponent blockNameComponent = null;
            if (pendingBlock.isInWorld(player.worldObj)) {
                String gtBlockNameKey = null;

                if (GregTech.isModLoaded()) gtBlockNameKey = getGTBlockUnlocalizedName(pendingBlock);

                BlockSpec spec = BlockSpec.fromBlock(null, player.worldObj, pendingBlock.x, pendingBlock.y, pendingBlock.z);

                if (gtBlockNameKey == null) {
                    if (InteropConstants.AE_BLOCK_CABLE.matches(spec)) {
                        blockNameComponent = InteropConstants.AE_BLOCK_CABLE.toSpec().getChatComponent();
                    } else {
                        blockNameComponent = spec.getChatComponent();
                    }
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
                sendErrorToPlayer(
                    player,
                    "mm.info.error.only_message",
                    pendingBlock.x,
                    pendingBlock.y,
                    pendingBlock.z,
                    message
                );
            }

            PendingBuild.this.errors.add(CoordinatePacker.pack(pendingBlock.x, pendingBlock.y, pendingBlock.z));
        }
    }

    @Optional(Names.GREG_TECH_NH)
    private String getGTBlockUnlocalizedName(PendingBlock pendingBlock) {
        if (player.worldObj.getTileEntity(pendingBlock.x, pendingBlock.y, pendingBlock.z) instanceof IGregTechTileEntity igte) {
            IMetaTileEntity imte = igte.getMetaTileEntity();
            if (imte != null) {
                // FIXME: should use more robust method to get unlocalized name
                String key = "gt.blockmachines." + imte.getMetaName() + ".name";
                if (StatCollector.canTranslate(key)) {
                    return key;
                } else {
                    return imte.getLocalName();
                }
            }
        }

        return null;
    }
}
