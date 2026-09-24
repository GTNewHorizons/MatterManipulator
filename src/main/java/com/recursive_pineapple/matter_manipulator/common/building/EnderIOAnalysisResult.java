package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.enderio.core.common.util.DyeColor;
import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentItemName;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.mixin.interfaces.ConduitExt;
import com.recursive_pineapple.matter_manipulator.mixin.interfaces.ConduitExt.InsulatedExt;
import com.recursive_pineapple.matter_manipulator.mixin.interfaces.ConduitExt.SwitchExt;

import crazypants.enderio.EnderIO;
import crazypants.enderio.conduit.ConnectionMode;
import crazypants.enderio.conduit.IConduit;
import crazypants.enderio.conduit.IConduitBundle;
import crazypants.enderio.conduit.IConduitItem;
import crazypants.enderio.conduit.IExtractor;
import crazypants.enderio.conduit.facade.ItemConduitFacade.FacadeType;
import crazypants.enderio.conduit.gui.item.InventoryUpgrades;
import crazypants.enderio.conduit.item.FilterRegister;
import crazypants.enderio.conduit.item.IItemConduit;
import crazypants.enderio.conduit.item.ItemExtractSpeedUpgrade;
import crazypants.enderio.conduit.item.filter.IItemFilter;
import crazypants.enderio.conduit.liquid.AbstractEnderLiquidConduit;
import crazypants.enderio.conduit.liquid.AbstractTankConduit;
import crazypants.enderio.machine.RedstoneControlMode;
import crazypants.enderio.machine.painter.PainterUtil;

public class EnderIOAnalysisResult implements ITileAnalysisIntegration {

    public List<ConduitData> conduits = new ArrayList<>();
    public PortableItemStack facade;

    public static EnderIOAnalysisResult analyze(TileEntity te) {
        if (!(te instanceof IConduitBundle bundle)) return null;

        EnderIOAnalysisResult result = new EnderIOAnalysisResult();
        for (IConduit conduit : bundle.getConduits()) {
            result.conduits.add(new ConduitData(conduit));
        }
        ItemStack facade = getFacade(bundle);
        result.facade = facade == null ? null : PortableItemStack.withNBT(facade);
        return result;
    }

    private static ItemStack getFacade(IConduitBundle bundle) {
        if (!bundle.hasFacade()) return null;
        ItemStack stack = new ItemStack(EnderIO.itemConduitFacade, 1, bundle.getFacadeType().ordinal());
        PainterUtil.setSourceBlock(stack, bundle.getFacadeId(), bundle.getFacadeMetadata());
        return stack;
    }

    @Override
    public boolean apply(IBlockApplyContext ctx) {
        if (!(ctx.getTileEntity() instanceof IConduitBundle bundle)) return false;
        boolean success = update(ctx, bundle, false);
        bundle.dirty();
        if (bundle.getConduits().isEmpty() && !bundle.hasFacade()) {
            ctx.getWorld().setBlockToAir(ctx.getX(), ctx.getY(), ctx.getZ());
        }
        return success;
    }

    private boolean update(IBlockApplyContext ctx, IConduitBundle bundle, boolean simulate) {
        List<IConduit> remaining = bundle == null ? new ArrayList<>() : new ArrayList<>(bundle.getConduits());
        boolean success = true;
        for (ConduitData data : conduits) {
            ItemStack stack = data.item.toStack();
            if (stack == null || !(stack.getItem() instanceof IConduitItem item)) {
                success = false;
                continue;
            }
            IConduit actual = bundle == null ? null : bundle.getConduit(item.getBaseConduitType());
            remaining.remove(actual);
            if (!data.apply(ctx, bundle, actual, simulate)) success = false;
        }
        for (IConduit conduit : remaining) {
            ctx.givePlayerItems(getDrops(conduit).toArray(new ItemStack[0]));
            removeConduit(ctx, bundle, conduit, simulate);
        }

        ItemStack actualFacade = bundle == null ? null : getFacade(bundle);
        ItemStack expectedFacade = facade == null ? null : facade.toStack();
        if (!ItemStack.areItemStacksEqual(actualFacade, expectedFacade)) {
            if (expectedFacade == null || ctx.tryConsumeItems(expectedFacade)) {
                if (actualFacade != null) ctx.givePlayerItems(actualFacade);
                if (!simulate) {
                    bundle.setFacadeId(expectedFacade == null ? null : PainterUtil.getSourceBlock(expectedFacade));
                    bundle.setFacadeMetadata(expectedFacade == null ? 0 : PainterUtil.getSourceBlockMetadata(expectedFacade));
                    bundle.setFacadeType(expectedFacade == null ? FacadeType.BASIC : FacadeType.VALUES[expectedFacade.getItemDamage()]);
                }
            } else {
                warn(ctx, expectedFacade);
                success = false;
            }
        }
        return success;
    }

    private static void removeConduit(IBlockApplyContext ctx, IConduitBundle bundle, IConduit conduit, boolean simulate) {
        // Drain only this conduit's buffer, not the connected fluid network.
        if (conduit instanceof AbstractTankConduit tank) {
            FluidStack fluid = tank.getTank().drain(Integer.MAX_VALUE, !simulate);
            if (fluid != null && fluid.amount > 0) ctx.givePlayerFluids(fluid);
        }
        if (!simulate) bundle.removeConduit(conduit);
    }

    private static void warn(IBlockApplyContext ctx, ItemStack item) {
        ctx.warn(new ChatComponentTranslation("mm.info.warning.could_not_find_item", new ChatComponentItemName(item)));
    }

    // Filter configuration lives separately from its upgrade item while installed.
    private static ItemStack getUpgrade(InventoryUpgrades inventory, IItemConduit conduit, ForgeDirection side, int slot) {
        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null) return null;
        IItemFilter filter = stack == conduit.getInputFilterUpgrade(side) ?
            conduit.getInputFilter(side) :
            stack == conduit.getOutputFilterUpgrade(side) ? conduit.getOutputFilter(side) : null;
        stack = stack.copy();
        FilterRegister.writeFilterToStack(filter, stack);
        return stack;
    }

    private static List<ItemStack> getDrops(IConduit conduit) {
        if (!(conduit instanceof IItemConduit items)) { return MMUtils.mapToList(conduit.getDrops(), ItemStack::copy); }
        List<ItemStack> drops = new ArrayList<>();
        drops.add(conduit.createItem());
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            InventoryUpgrades inventory = new InventoryUpgrades(items, side);
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                ItemStack stack = getUpgrade(inventory, items, side, slot);
                if (stack != null) drops.add(stack);
            }
        }
        return drops;
    }

    /** Reuse installed items before requesting new ones, without touching the live bundle. */
    static int take(List<ItemStack> available, ItemStack wanted) {
        int missing = wanted.stackSize;
        for (ItemStack stack : available) {
            if (!stack.isItemEqual(wanted)) continue;
            int amount = Math.min(missing, stack.stackSize);
            stack.stackSize -= amount;
            missing -= amount;
        }
        return missing;
    }

    public static class ConduitData {

        public PortableItemStack item;
        public SideData[] sides = new SideData[ForgeDirection.VALID_DIRECTIONS.length];
        public Boolean switchOn;

        public ConduitData() {}

        public ConduitData(IConduit conduit) {
            item = new PortableItemStack(conduit.createItem());
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                sides[side.ordinal()] = new SideData(conduit, side);
            }
            if (conduit instanceof SwitchExt sw) switchOn = sw.mm$isOn();
        }

        boolean apply(IBlockApplyContext ctx, IConduitBundle bundle, IConduit actual, boolean simulate) {
            if (!simulate && actual != null && equals(new ConduitData(actual))) return true;
            List<ItemStack> available = actual == null ? new ArrayList<>() : getDrops(actual);
            List<ItemStack> required = new ArrayList<>();
            required.add(item.toStack());
            for (SideData side : sides) {
                if (side.upgrades == null) continue;
                for (PortableItemStack upgrade : side.upgrades) {
                    if (upgrade != null && !(upgrade.getItem() instanceof ItemExtractSpeedUpgrade)) required.add(upgrade.toStack());
                }
            }
            List<BigItemStack> missing = new ArrayList<>();
            for (ItemStack stack : required) {
                int count = take(available, stack);
                if (count > 0) missing.add(BigItemStack.create(stack).setStackSize(count));
            }
            // All filters and function upgrades must be available before changing this conduit.
            if (!ctx.tryConsumeItems(missing, IPseudoInventory.CONSUME_FUZZY).leftBoolean()) {
                ctx.warn(new ChatComponentTranslation("mm.info.warning.conduit_missing_items", new ChatComponentItemName(item.toStack())));
                return false;
            }

            IConduit target = actual;
            if (!simulate && (actual == null || !actual.createItem().isItemEqual(item.toStack()))) {
                target = ((IConduitItem) item.getItem()).createConduit(item.toStack(), ctx.getRealPlayer());
                if (actual != null) removeConduit(ctx, bundle, actual, false);
                bundle.addConduit(target);
            }
            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                SideData side = sides[dir.ordinal()];
                if (side.upgrades != null) {
                    InventoryUpgrades inventory = !simulate && target instanceof IItemConduit items ? new InventoryUpgrades(items, dir) : null;
                    for (int slot = 0; slot < side.upgrades.length; slot++) {
                        ItemStack stack = side.upgrades[slot] == null ? null : side.upgrades[slot].toStack();
                        if (stack != null && stack.getItem() instanceof ItemExtractSpeedUpgrade) {
                            int missingSpeed = take(available, stack);
                            int installed = stack.stackSize - missingSpeed;
                            if (missingSpeed > 0) {
                                var result = ctx.tryConsumeItems(
                                    Arrays.asList(BigItemStack.create(stack).setStackSize(missingSpeed)),
                                    IPseudoInventory.CONSUME_PARTIAL
                                );
                                if (result.right() != null) {
                                    for (BigItemStack extracted : result.right())
                                        installed += (int) extracted.stackSize;
                                }
                                if (installed < stack.stackSize) warn(ctx, stack);
                            }
                            stack.stackSize = installed;
                            if (installed == 0) stack = null;
                        }
                        if (inventory != null) inventory.setInventorySlotContents(slot, stack);
                    }
                }
                if (!simulate) side.apply(target, dir);
            }
            if (!simulate) {
                if (switchOn != null && target instanceof SwitchExt sw) sw.mm$setOn(switchOn);
                // Rebuild connections after all faces are configured, including conduit-to-conduit connections.
                target.onRemovedFromBundle();
                target.onAddedToBundle();
            }
            for (ItemStack stack : available) {
                if (stack.stackSize > 0) ctx.givePlayerItems(stack);
            }
            return true;
        }

        public ConduitData clone() {
            ConduitData copy = new ConduitData();
            copy.item = item.clone();
            copy.sides = MMUtils.mapToArray(sides, SideData[]::new, SideData::clone);
            copy.switchOn = switchOn;
            return copy;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ConduitData other && Objects.equals(item, other.item) &&
                Arrays.equals(sides, other.sides) &&
                Objects.equals(switchOn, other.switchOn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(item, Arrays.hashCode(sides), switchOn);
        }
    }

    public static class SideData {

        public ConnectionMode mode;
        public ConnectionMode forcedConnection;
        public NBTTagCompound settings;
        public PortableItemStack[] upgrades;
        public RedstoneControlMode extractionMode;
        public DyeColor extractionColor;

        public SideData() {}

        public SideData(IConduit conduit, ForgeDirection side) {
            mode = conduit.getConnectionMode(side);
            if (conduit instanceof InsulatedExt insulated) forcedConnection = insulated.mm$getForcedConnection(side);
            // Use the probe's virtual settings hooks, including on disconnected faces.
            settings = ((ConduitExt) conduit).mm$getSettings(side);
            if (conduit instanceof IExtractor extractor) {
                extractionMode = extractor.getExtractionRedstoneMode(side);
                extractionColor = extractor.getExtractionSignalColor(side);
            }
            if (conduit instanceof IItemConduit items) {
                InventoryUpgrades inventory = new InventoryUpgrades(items, side);
                upgrades = new PortableItemStack[inventory.getSizeInventory()];
                for (int slot = 0; slot < upgrades.length; slot++) {
                    ItemStack stack = getUpgrade(inventory, items, side, slot);
                    upgrades[slot] = stack == null ? null : PortableItemStack.withNBT(stack);
                }
            }
        }

        public void apply(IConduit conduit, ForgeDirection side) {
            // The probe reader leaves absent filter tags unchanged; copying must also clear old filters.
            if (conduit instanceof AbstractEnderLiquidConduit liquid) {
                liquid.setFilter(side, null, true);
                liquid.setFilter(side, null, false);
            }
            ((ConduitExt) conduit).mm$setSettings(side, (NBTTagCompound) settings.copy());
            if (conduit instanceof IExtractor extractor && extractionMode != null) {
                extractor.setExtractionRedstoneMode(extractionMode, side);
                extractor.setExtractionSignalColor(side, extractionColor);
            }
            if (conduit instanceof InsulatedExt insulated) insulated.mm$setForcedConnection(side, forcedConnection);
            conduit.setConnectionMode(side, mode);
        }

        public SideData clone() {
            SideData copy = new SideData();
            copy.mode = mode;
            copy.forcedConnection = forcedConnection;
            copy.settings = (NBTTagCompound) settings.copy();
            copy.upgrades = upgrades == null ? null : MMUtils.mapToArray(upgrades, PortableItemStack[]::new, x -> x == null ? null : x.clone());
            copy.extractionMode = extractionMode;
            copy.extractionColor = extractionColor;
            return copy;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof SideData other && mode == other.mode &&
                forcedConnection == other.forcedConnection &&
                Objects.equals(settings, other.settings) &&
                Arrays.equals(upgrades, other.upgrades) &&
                extractionMode == other.extractionMode &&
                extractionColor == other.extractionColor;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, forcedConnection, settings, Arrays.hashCode(upgrades), extractionMode, extractionColor);
        }
    }

    @Override
    public boolean getRequiredItemsForExistingBlock(IBlockApplyContext ctx) {
        return update(ctx, ctx.getTileEntity() instanceof IConduitBundle bundle ? bundle : null, true);
    }

    @Override
    public boolean getRequiredItemsForNewBlock(IBlockApplyContext ctx) {
        return update(ctx, null, true);
    }

    @Override
    public void transform(Transform transform) {
        for (ConduitData conduit : conduits) {
            SideData[] transformed = new SideData[conduit.sides.length];
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                transformed[transform.apply(side).ordinal()] = conduit.sides[side.ordinal()];
            }
            conduit.sides = transformed;
        }
    }

    @Override
    public EnderIOAnalysisResult clone() {
        EnderIOAnalysisResult copy = new EnderIOAnalysisResult();
        copy.conduits = MMUtils.mapToList(conduits, ConduitData::clone);
        copy.facade = facade == null ? null : facade.clone();
        return copy;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof EnderIOAnalysisResult other && conduits.equals(other.conduits) && Objects.equals(facade, other.facade);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conduits, facade);
    }

    @Override
    public void getItemTag(ItemStack stack) {}

    @Override
    public void getItemDetailsChat(List<IChatComponent> details) {}

    @Override
    public void migrate() {}
}
