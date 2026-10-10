package com.recursive_pineapple.matter_manipulator.common.building.movers;

import static com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult.PartData;
import static com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult.capturePart;
import static com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult.getPartOrder;
import static com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult.hasSupportBlock;
import static com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult.syncTileToClient;

import java.util.Arrays;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import com.recursive_pineapple.matter_manipulator.common.building.FMPPartTransforms;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

import codechicken.lib.vec.BlockCoord;
import codechicken.multipart.MultiPartRegistry;
import codechicken.multipart.TMultiPart;
import codechicken.multipart.TileMultipart;

public class FMPBlockMover implements BlockMover<FMPBlock> {

    public static final FMPBlockMover INSTANCE = new FMPBlockMover();

    @Override
    public boolean canMove(World world, int x, int y, int z) {
        return world.getTileEntity(x, y, z) instanceof TileMultipart;
    }

    @Override
    public FMPBlock snapshot(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z) {
        TileMultipart te = (TileMultipart) world.getTileEntity(x, y, z);

        List<TMultiPart> partList = te.jPartList();
        PartData[] parts = new PartData[partList.size()];

        for (int i = 0; i < partList.size(); i++) {
            parts[i] = capturePart(partList.get(i));
        }

        return new FMPBlock(parts);
    }

    @Override
    public void place(PendingMove.PendingMoveApplyContext ctx, World world, int x, int y, int z, FMPBlock fmpBlock) {
        BlockCoord coord = new BlockCoord(x, y, z);

        PartData[] sortedParts = fmpBlock.parts().clone();
        Arrays.sort(sortedParts, (a, b) -> Integer.compare(getPartOrder(a), getPartOrder(b)));

        for (PartData data : sortedParts) {
            if (data == null) continue;

            if (!hasSupportBlock(world, data, x, y, z)) {
                refundParts(ctx, data);
                ctx.warn(new ChatComponentText("Could not place " + data.typeId + ": no support block"));
            }

            TMultiPart part = MultiPartRegistry.loadPart(data.typeId, data.nbt);
            if (part == null) continue;

            part.load(data.nbt);

            try {
                TileMultipart tile = TileMultipart.addPart(world, coord, part);
                if (tile == null) {
                    refundParts(ctx, data);
                    ctx.warn(new ChatComponentText("Could not place multipart: " + data.typeId));
                }
            } catch (Exception e) {
                refundParts(ctx, data);
                ctx.error(new ChatComponentText("Failed to add multipart: " + data.typeId));
                return;
            }
        }

        syncTileToClient(world, x, y, z);
    }

    @Override
    public void transform(PendingMove.PendingMoveApplyContext ctx, Transform transform, FMPBlock fmpBlock) {
        for (PartData data : fmpBlock.parts()) {
            if (data == null || data.nbt == null) continue;

            FMPPartTransforms.transformSide(data.nbt, transform);
            FMPPartTransforms.transformOrient(data.nbt, transform);
            FMPPartTransforms.transformMicroblockShape(data.typeId, data.nbt, transform);
            FMPPartTransforms.clearConnMap(data.nbt);
        }
    }

    @Override
    public boolean snapshotEarly() {
        return true;
    }

    private static void refundParts(PendingMove.PendingMoveApplyContext ctx, PartData data) {
        for (PortableItemStack drop : data.drops) {
            ItemStack refund = drop.toStack();
            if (refund != null) {
                ctx.givePlayerItems(refund);
            }
        }
    }
}
