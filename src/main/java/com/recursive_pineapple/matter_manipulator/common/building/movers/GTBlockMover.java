package com.recursive_pineapple.matter_manipulator.common.building.movers;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IIC2Enet;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputSlave;

import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;

import tectech.thing.metaTileEntity.pipe.MTEPipeData;
import tectech.thing.metaTileEntity.pipe.MTEPipeLaser;

public class GTBlockMover extends StandardBlockMover {

    public static final GTBlockMover INSTANCE = new GTBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return world.getTileEntity(x, y, z) instanceof IGregTechTileEntity;
    }

    @Override
    public StandardBlock remove(PendingMove pendingMove, World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        boolean isProxy = te instanceof IGregTechTileEntity igte && igte.getMetaTileEntity() instanceof MTEHatchCraftingInputSlave;
        List<MTEHatchCraftingInputSlave> proxies = getProxies(te);

        // Because GT uses this to call MTE.onRemoval() :doom:
        world.getBlock(x, y, z).getDrops(world, x, y, z, world.getBlockMetadata(x, y, z), 0);

        StandardBlock standardBlock = super.remove(pendingMove, world, x, y, z);

        if (isProxy && pendingMove != null) {
            adjustProxyMasterNbt(standardBlock, pendingMove);
        }

        if (!proxies.isEmpty() && pendingMove != null) {
            pendingMove.getMoverState().put(standardBlock, proxies);
        }

        return standardBlock;
    }

    @Override
    public void place(PendingMove pendingMove, World world, int x, int y, int z, StandardBlock standardBlock) {
        super.place(pendingMove, world, x, y, z, standardBlock);

        TileEntity te = world.getTileEntity(x, y, z);

        if (te instanceof IGregTechTileEntity igte) {
            if (igte instanceof BaseMetaTileEntity bmte) {
                bmte.setCableUpdateDelay(100);
            }

            IMetaTileEntity imte = igte.getMetaTileEntity();

            if (imte instanceof MTEPipeLaser laserPipe) {
                laserPipe.updateNeighboringNetworks();
            }

            if (imte instanceof MTEPipeData dataPipe) {
                dataPipe.updateNeighboringNetworks();
            }
        }

        if (te instanceof IIC2Enet enet) {
            enet.doEnetUpdate();
        }

        @SuppressWarnings("unchecked")
        List<MTEHatchCraftingInputSlave> proxies = pendingMove == null ?
            null :
            (List<MTEHatchCraftingInputSlave>) pendingMove.getMoverState().remove(standardBlock);

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

    /**
     * All proxies of a buffer get relinked once the buffer has been placed, also the ones inside the moved area: a
     * proxy that was already moved has been removed from the buffer's list (and is dead), one that is moved later
     * keeps the new position (it lies outside the source area), and one that can't be moved stays linked.
     */
    private static List<MTEHatchCraftingInputSlave> getProxies(TileEntity te) {
        List<MTEHatchCraftingInputSlave> proxies = new ArrayList<>();

        if (!(te instanceof IGregTechTileEntity igte)) return proxies;
        if (!(igte.getMetaTileEntity() instanceof MTEHatchCraftingInputME buffer)) return proxies;

        for (MTEHatchCraftingInputSlave proxy : buffer.getProxyHatches()) {
            IGregTechTileEntity proxyTE = proxy.getBaseMetaTileEntity();

            if (proxyTE == null) continue;

            proxies.add(proxy);
        }

        return proxies;
    }

    /** A proxy that is moved before its buffer has to point to the buffer's new position. */
    private static void adjustProxyMasterNbt(StandardBlock standardBlock, PendingMove pendingMove) {
        NBTTagCompound tileData = standardBlock.tileData();
        if (tileData == null || !tileData.hasKey("master")) return;

        NBTTagCompound master = tileData.getCompoundTag("master");
        int mx = master.getInteger("x");
        int my = master.getInteger("y");
        int mz = master.getInteger("z");

        if (pendingMove.isInSourceRegion(mx, my, mz)) {
            master.setInteger("x", mx + pendingMove.getMoveOffsetX());
            master.setInteger("y", my + pendingMove.getMoveOffsetY());
            master.setInteger("z", mz + pendingMove.getMoveOffsetZ());
        }
    }
}
