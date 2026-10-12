package com.recursive_pineapple.matter_manipulator.common.building.movers;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.metatileentity.IConnectable;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IIC2Enet;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEFluidPipe;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputSlave;

import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentItemName;
import com.gtnewhorizon.structurelib.alignment.IAlignment;
import com.gtnewhorizon.structurelib.alignment.IAlignmentProvider;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.recursive_pineapple.matter_manipulator.common.building.CoverData;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import gtnhlanth.common.beamline.MTEBeamlinePipe;
import tectech.thing.metaTileEntity.pipe.MTEPipeData;
import tectech.thing.metaTileEntity.pipe.MTEPipeLaser;

public class GTBlockMover implements BlockMover<GTBlock> {

    public static final GTBlockMover INSTANCE = new GTBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return world.getTileEntity(x, y, z) instanceof IGregTechTileEntity;
    }

    @Override
    public GTBlock snapshot(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z) {
        List<MTEHatchCraftingInputSlave> proxies = getProxies(world.getTileEntity(x, y, z));

        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        IGregTechTileEntity te = (IGregTechTileEntity) world.getTileEntity(x, y, z);

        GTBlock gtBlock = new GTBlock(block, meta, te, te.getMetaTileEntity());

        // Because GT uses this to call MTE.onRemoval() :doom:
        world.getBlock(x, y, z).getDrops(world, x, y, z, world.getBlockMetadata(x, y, z), 0);

        if (gtBlock.mte() instanceof MTEMultiBlockBase mb) {
            // Avoid premature shutdown
            mb.mStartUpCheck = 100;
            mb.mMachine = false;
        }

        if (!proxies.isEmpty() && ctx != null) {
            ctx.pendingMove.getMoverState().put(gtBlock, proxies);
        }

        return gtBlock;
    }

    @Override
    public void place(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z, GTBlock gtBlock) {
        world.setBlock(x, y, z, gtBlock.block(), gtBlock.meta(), 3);

        IGregTechTileEntity igte = gtBlock.igte();
        IMetaTileEntity imte = gtBlock.mte();

        if (imte == null) return;

        imte.setBaseMetaTileEntity(igte);

        if (igte instanceof TileEntity te) {
            te.xCoord = x;
            te.yCoord = y;
            te.zCoord = z;
            te.validate();
            world.setTileEntity(x, y, z, te);
        }

        if (igte instanceof BaseMetaTileEntity bmte) {
            bmte.setCableUpdateDelay(100);
        }

        if (imte instanceof MTEPipeLaser laserPipe) {
            laserPipe.updateNeighboringNetworks();
        }

        if (imte instanceof MTEPipeData dataPipe) {
            dataPipe.updateNeighboringNetworks();
        }

        if (imte instanceof MTEHatchCraftingInputSlave proxy) {
            linkToSavedMaster(proxy, gtBlock);
        }

        if (igte instanceof IIC2Enet enet) {
            enet.doEnetUpdate();
        }

        @SuppressWarnings("unchecked")
        List<MTEHatchCraftingInputSlave> proxies = ctx == null ?
            null :
            (List<MTEHatchCraftingInputSlave>) ctx.pendingMove.getMoverState().remove(gtBlock);

        if (proxies != null) {
            for (MTEHatchCraftingInputSlave proxy : proxies) {
                IGregTechTileEntity proxyTE = proxy.getBaseMetaTileEntity();

                if (proxyTE == null || proxyTE.isDead()) continue;

                if (proxy.trySetMasterFromCoord(x, y, z) != null) {
                    proxyTE.markDirty();
                }
            }
        }
    }

    @Override
    public void transform(PendingMove.PendingMoveApplyContext ctx, Transform transform, GTBlock gtBlock) {
        IGregTechTileEntity igte = gtBlock.igte();
        IMetaTileEntity mte = gtBlock.mte();

        if (mte instanceof MTEBasicMachine bm) {
            bm.setMainFacing(transform.apply(bm.mMainFacing));
        }

        if (mte instanceof IAlignmentProvider provider) {
            IAlignment alignment = provider.getAlignment();
            if (alignment != null) {
                ExtendedFacing facing = transform.apply(alignment.getExtendedFacing());

                // maintenance hatches can be rotated but not flipped
                if (!alignment.isNewExtendedFacingValid(facing)) {
                    facing = ExtendedFacing.of(facing.getDirection(), facing.getRotation(), Flip.NONE);
                }

                if (alignment.isNewExtendedFacingValid(facing)) {
                    igte.setFrontFacing(facing.getDirection());
                    alignment.toolSetExtendedFacing(facing);
                } else {
                    ctx.error(
                        new ChatComponentTranslation(
                            "mm.info.error.could_not_set_direction_to",
                            new ChatComponentTranslation(MMUtils.getFacingUnlocalizedName(facing))
                        )
                    );
                }
            }
        } else {
            ForgeDirection front = MMUtils.nullIfUnknown(igte.getFrontFacing());
            if (front != null) {
                igte.setFrontFacing(transform.apply(front));
            }
        }

        // check for connections
        if (mte instanceof IConnectable connectable && shouldMutateConnections(connectable)) {
            byte connections = 0;

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                if (connectable.isConnectedAtSide(dir)) {
                    connections |= dir.flag;
                }
            }

            connections = transform.applyBits(connections);

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                boolean connect = (connections & dir.flag) != 0;
                if (connectable.isConnectedAtSide(dir) != connect) {
                    if (connect) {
                        connectable.connect(dir);
                    } else {
                        connectable.disconnect(dir);
                    }
                }
            }
        }

        if (mte instanceof MTEFluidPipe fluidPipe) {
            fluidPipe.mDisableInput = transform.applyBits(fluidPipe.mDisableInput);
        }

        CoverData[] covers = new CoverData[6];
        boolean[] coverExists = new boolean[6];
        ForgeDirection[] coversDir = new ForgeDirection[6];
        byte strongRedstone = 0;

        // check each side for covers
        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            ForgeDirection newDir = transform.apply(dir);

            coversDir[dir.ordinal()] = newDir;

            if (igte.hasCoverAtSide(dir)) {
                CoverData cover = CoverData.fromMachine(igte, dir);

                coverExists[dir.ordinal()] = true;
                covers[newDir.ordinal()] = cover;

                if (igte.getRedstoneOutputStrength(dir)) {
                    strongRedstone |= (byte) dir.flag;
                }
            }
        }

        strongRedstone = transform.applyBits(strongRedstone);

        for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
            if (coversDir[dir.ordinal()] == dir) continue;

            boolean coverExist = coverExists[dir.ordinal()];
            CoverData cover = covers[dir.ordinal()];

            if (!coverExist && cover == null) continue;
            if (coverExist && cover == null)
                igte.detachCover(dir);
            else if (!coverExist)
                installCover(ctx, igte, dir, cover);
            else {
                igte.detachCover(dir);
                installCover(ctx, igte, dir, cover);
            }

            igte.setRedstoneOutputStrength(dir, (strongRedstone & dir.flag) != 0);
        }
    }

    /**
     * The proxies that are linked to a buffer. They are relinked once the buffer has been placed, which also covers
     * proxies inside the moved area: one that was moved before the buffer has linked itself again from its new spot
     * (see linkToSavedMaster), one that is moved after it takes the new position along, and one that can't be moved
     * stays linked.
     */
    private static List<MTEHatchCraftingInputSlave> getProxies(TileEntity te) {
        if (!(te instanceof IGregTechTileEntity igte)) return new ArrayList<>();
        if (!(igte.getMetaTileEntity() instanceof MTEHatchCraftingInputME buffer)) return new ArrayList<>();

        // copy: relinking a proxy removes it from the old buffer's list
        return new ArrayList<>(buffer.getProxyHatches());
    }

    /**
     * A moved proxy links to its buffer at its saved position right away instead of on its next 100 tick retry, so a
     * buffer that is moved later in this move still takes it along. If the buffer doesn't get moved, it stays linked.
     */
    private static void linkToSavedMaster(MTEHatchCraftingInputSlave proxy, GTBlock gtBlock) {
        IMetaTileEntity imte = gtBlock.mte();
        if (!(imte instanceof MTEHatchCraftingInputSlave slave)) return;

        MTEHatchCraftingInputME master = slave.getMaster();
        if (master == null) return;

        proxy.trySetMasterFromCoord(master.getTileEntity().xCoord, master.getTileEntity().yCoord, master.getTileEntity().zCoord);
    }

    private static void installCover(PendingMove.PendingMoveApplyContext ctx, IGregTechTileEntity igte, ForgeDirection side, CoverData cover) {
        ItemStack stack = cover.getCoverStack();

        if (!igte.getMetaTileEntity().allowCoverOnSide(side, stack)) {
            ctx.error(
                new ChatComponentTranslation(
                    "mm.info.error.was_not_allowed_to_put_cover_on",
                    new ChatComponentTranslation(MMUtils.getDirectionUnlocalizedName(side, true)),
                    new ChatComponentItemName(stack)
                )
            );
            ctx.givePlayerItems(stack);
            return;
        }

        CoverRegistry.getCoverPlacer(stack).placeCover(ctx.getRealPlayer(), stack, igte, side);

        if (igte.getCoverAtSide(side).allowsCopyPasteTool()) {
            igte.updateAttachedCover(
                cover.coverID,
                side,
                cover.coverData
            );
        }
    }

    private boolean shouldMutateConnections(IConnectable conn) {
        if (conn instanceof MTEPipeLaser) return false;
        if (conn instanceof MTEPipeData) return false;
        if (conn instanceof MTEBeamlinePipe) return false;

        return true;
    }
}
