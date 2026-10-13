package com.recursive_pineapple.matter_manipulator.common.building.movers;

import java.util.Iterator;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.implementations.tiles.ISegmentedInventory;
import appeng.api.parts.IFacadeContainer;
import appeng.api.parts.IFacadePart;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.items.parts.ItemFacade;
import appeng.tile.AEBaseTile;
import appeng.tile.crafting.TileCraftingTile;

import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentItemName;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.InventoryAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.building.providers.IItemProvider;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

public class AEBlockMover implements BlockMover<AEBlock> {

    public static final AEBlockMover INSTANCE = new AEBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return world.getTileEntity(x, y, z) instanceof AEBaseTile;
    }

    @Override
    public AEBlock snapshot(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        AEBaseTile tile = (AEBaseTile) world.getTileEntity(x, y, z);

        if (tile instanceof TileCraftingTile tileCrafting) {
            if (tileCrafting.isCoreBlock() && tileCrafting.getCluster() != null) {
                // Preserve the crafting cluster

                NBTTagCompound tag = new NBTTagCompound();
                tileCrafting.writeToNBT_TileCraftingTile(tag);

                ctx.pendingMove.getMoverState().put(tile, tag);
            }
        }

        return new AEBlock(block, meta, tile);
    }

    @Override
    public void place(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z, AEBlock aeBlock) {
        world.setBlock(x, y, z, aeBlock.block(), aeBlock.meta(), 3);

        aeBlock.tile().xCoord = x;
        aeBlock.tile().yCoord = y;
        aeBlock.tile().zCoord = z;
        aeBlock.tile().validate();

        world.setTileEntity(x, y, z, aeBlock.tile());

        aeBlock.tile().markForUpdate();

        if (aeBlock.tile() instanceof IPartHost partHost) {
            partHost.partChanged();
        }
    }

    @Override
    public void transform(PendingMove.PendingMoveApplyContext ctx, Transform transform, AEBlock aeBlock) {
        ForgeDirection up = MMUtils.nullIfUnknown(aeBlock.tile().getUp());
        ForgeDirection forward = MMUtils.nullIfUnknown(aeBlock.tile().getForward());

        if (up != null && forward != null) {
            up = transform.apply(up);
            forward = transform.apply(forward);

            aeBlock.tile().setOrientation(forward, up);
        }

        if (aeBlock.tile() instanceof IPartHost partHost) {
            IFacadeContainer facadeContainer = partHost.getFacadeContainer();

            AEPartData[] aeParts = new AEPartData[ForgeDirection.VALID_DIRECTIONS.length];
            ForgeDirection[] aePartsDir = new ForgeDirection[ForgeDirection.VALID_DIRECTIONS.length];
            PortableItemStack[] aeFacades = new PortableItemStack[ForgeDirection.VALID_DIRECTIONS.length];
            ForgeDirection[] aeFacadesDir = new ForgeDirection[ForgeDirection.VALID_DIRECTIONS.length];

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                IPart part = partHost.getPart(dir);

                if (part != null) {
                    ForgeDirection newDir = transform.apply(dir);

                    aePartsDir[dir.ordinal()] = newDir;
                    aeParts[newDir.ordinal()] = new AEPartData(part);

                    if (dir != newDir) {
                        partHost.removePart(dir, true);
                    }
                }
            }

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                IFacadePart facadePart = facadeContainer.getFacade(dir);

                if (facadePart != null) {
                    ForgeDirection newDir = transform.apply(dir);

                    aeFacadesDir[dir.ordinal()] = newDir;
                    aeFacades[newDir.ordinal()] = PortableItemStack.withNBT(facadePart.getItemStack());

                    if (dir != newDir) {
                        facadeContainer.removeFacade(partHost, dir);
                    }
                }
            }

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                if (aePartsDir[dir.ordinal()] == dir) continue;

                AEPartData data = aeParts[dir.ordinal()];
                ItemStack partStack = data != null ? data.mPart.toStack() : null;

                if (partStack == null) continue;

                if (!partHost.canAddPart(partStack, dir)) {
                    refundUpgrades(ctx, data.mAEUpgrades);
                    refundPatterns(ctx, data.mAEPatterns);
                    ctx.givePlayerItems(partStack);
                    ctx.error(
                        new ChatComponentTranslation(
                            "mm.info.error.invalid_location",
                            new ChatComponentTranslation(MMUtils.getDirectionUnlocalizedName(dir, true)),
                            new ChatComponentItemName(partStack)
                        )
                    );
                    continue;
                }

                if (partHost.addPart(partStack, dir, ctx.getRealPlayer()) == null) {
                    refundUpgrades(ctx, data.mAEUpgrades);
                    refundPatterns(ctx, data.mAEPatterns);
                    ctx.givePlayerItems(partStack);
                    continue;
                }

                IPart part = partHost.getPart(dir);

                if (part instanceof ISegmentedInventory inv) {
                    // Apply upgrades and patterns

                    if (data.mAEUpgrades != null) {
                        IInventory upgrades = inv.getInventoryByName("upgrades");

                        if (upgrades != null) {
                            for (int i = 0; i < upgrades.getSizeInventory(); i++) {
                                upgrades.setInventorySlotContents(i, null);
                            }

                            for (int i = 0; i < data.mAEUpgrades.length; i++) {
                                if (i >= upgrades.getSizeInventory()) break;

                                PortableItemStack ps = data.mAEUpgrades[i];
                                upgrades.setInventorySlotContents(i, ps.toStack());
                            }
                        } else {
                            refundUpgrades(ctx, data.mAEUpgrades);
                        }

                        data.mAEUpgrades = null;
                    }

                    if (data.mAEPatterns != null) {
                        IInventory patterns = inv.getInventoryByName("patterns");

                        if (patterns != null) {
                            for (int i = 0; i < patterns.getSizeInventory(); i++) {
                                patterns.setInventorySlotContents(i, null);
                            }

                            for (int i = 0; i < data.mAEPatterns.mItems.length; i++) {
                                if (i >= patterns.getSizeInventory()) break;

                                IItemProvider provider = data.mAEPatterns.mItems[i];
                                if (provider == null) continue;

                                ItemStack stack = provider.getStack(ctx, false);
                                patterns.setInventorySlotContents(i, stack);
                            }
                        } else {
                            refundPatterns(ctx, data.mAEPatterns);
                        }

                        data.mAEPatterns = null;
                    }
                }

                data.updatePart(ctx, partHost, dir);
            }

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                if (aeFacadesDir[dir.ordinal()] == dir) continue;

                facadeContainer.removeFacade(partHost, dir);

                PortableItemStack facade = aeFacades[dir.ordinal()];

                if (facade == null) continue;

                if (!(facade.getItem() instanceof ItemFacade itemFacade)) continue;

                IFacadePart facadePart = itemFacade.createPartFromItemStack(facade.toStack(), dir);

                if (facadePart == null) continue;

                if (!facadeContainer.addFacade(facadePart)) {
                    ctx.givePlayerItems(facade.toStack());
                }
            }
        }
    }

    @Override
    public boolean snapshotEarly(World world, int x, int y, int z) {
        if (!(world.getTileEntity(x, y, z) instanceof TileCraftingTile tile)) return false;
        return tile.isCoreBlock() && tile.getCluster() != null;
    }

    private void refundUpgrades(PendingMove.PendingMoveApplyContext ctx, PortableItemStack[] upgrades) {
        if (upgrades == null) return;

        for (PortableItemStack ps : upgrades) {
            ctx.givePlayerItems(ps.toStack());
        }
    }

    private void refundPatterns(PendingMove.PendingMoveApplyContext ctx, InventoryAnalysis patterns) {
        if (patterns == null) return;

        for (IItemProvider provider : patterns.mItems) {
            if (provider == null) continue;

            ItemStack stack = provider.getStack(ctx, false);
            ctx.givePlayerItems(stack);
        }
    }

    public static void updateCraftingClusters(PendingMove pendingMove) {
        Map<Object, Object> states = pendingMove.getMoverState();
        Iterator<Object> iterator = states.keySet().iterator();

        while (iterator.hasNext()) {
            Object key = iterator.next();
            if (!(key instanceof TileCraftingTile tileCrafting)) continue;

            NBTTagCompound state = (NBTTagCompound) states.get(key);
            tileCrafting.readFromNBT_TileCraftingTile(state);

            iterator.remove();
        }
    }
}
