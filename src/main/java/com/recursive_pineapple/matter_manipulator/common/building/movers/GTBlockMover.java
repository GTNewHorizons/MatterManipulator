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
        List<MTEHatchCraftingInputSlave> proxies = getProxies(world.getTileEntity(x, y, z));

        // Because GT uses this to call MTE.onRemoval() :doom:
        world.getBlock(x, y, z).getDrops(world, x, y, z, world.getBlockMetadata(x, y, z), 0);

        StandardBlock standardBlock = super.remove(pendingMove, world, x, y, z);

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

            if (imte instanceof MTEHatchCraftingInputSlave proxy) {
                linkToSavedMaster(proxy, standardBlock);
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
    private static void linkToSavedMaster(MTEHatchCraftingInputSlave proxy, StandardBlock standardBlock) {
        NBTTagCompound tileData = standardBlock.tileData();
        if (tileData == null || !tileData.hasKey("master")) return;

        NBTTagCompound master = tileData.getCompoundTag("master");
        proxy.trySetMasterFromCoord(master.getInteger("x"), master.getInteger("y"), master.getInteger("z"));
    }
}
