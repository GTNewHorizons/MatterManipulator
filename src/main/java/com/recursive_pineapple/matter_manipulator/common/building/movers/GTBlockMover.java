package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IIC2Enet;
import gregtech.api.metatileentity.BaseMetaTileEntity;

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
        // Because GT uses this to call MTE.onRemoval() :doom:
        world.getBlock(x, y, z).getDrops(world, x, y, z, world.getBlockMetadata(x, y, z), 0);

        return super.remove(pendingMove, world, x, y, z);
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
    }
}
