package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import net.minecraftforge.oredict.OreDictionary;

import com.recursive_pineapple.matter_manipulator.common.building.InteropConstants;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;

import net.bdew.ae2stuff.machines.wireless.TileWireless;
import net.bdew.lib.block.BlockRef;

public class WirelessBlockMover extends StandardBlockMover {

    public static final WirelessBlockMover INSTANCE = new WirelessBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return InteropConstants.WIRELESS_CONNECTOR.matches(world.getBlock(x, y, z), OreDictionary.WILDCARD_VALUE);
    }

    @Override
    public void place(PendingMove pendingMove, World world, int x, int y, int z, StandardBlock standardBlock) {
        super.place(pendingMove, world, x, y, z, standardBlock);

        if (pendingMove == null) return;

        TileEntity te = world.getTileEntity(x, y, z);
        if (te == null) return;

        adjustWirelessLink((TileWireless) te, pendingMove);
    }

    private void adjustWirelessLink(TileWireless wireless, PendingMove pendingMove) {
        scala.Option<BlockRef> linkOpt = wireless.link().value();

        if (linkOpt.isEmpty()) return;

        BlockRef ref = linkOpt.get();
        int lx = ref.x();
        int ly = ref.y();
        int lz = ref.z();

        // only adjust if the linked partner was within the source region (i.e. it's also being moved)
        if (
            lx >= pendingMove.getSrcMinX() && lx <= pendingMove.getSrcMaxX() &&
                ly >= pendingMove.getSrcMinY() &&
                ly <= pendingMove.getSrcMaxY() &&
                lz >= pendingMove.getSrcMinZ() &&
                lz <= pendingMove.getSrcMaxZ()
        ) {
        }
    }
}
