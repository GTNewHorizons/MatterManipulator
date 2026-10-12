package com.recursive_pineapple.matter_manipulator.common.building.movers;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.parts.IFacadeContainer;
import appeng.api.parts.IFacadePart;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartItemStack;
import appeng.items.parts.ItemFacade;
import appeng.tile.AEBaseTile;

import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentItemName;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.PendingMove;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
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
            PortableItemStack[] aeFacades = new PortableItemStack[ForgeDirection.VALID_DIRECTIONS.length];

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                IPart part = partHost.getPart(dir);

                if (part != null) {
                    aeParts[transform.apply(dir).ordinal()] = new AEPartData(part);
                }
            }

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                IFacadePart facadePart = facadeContainer.getFacade(dir);

                if (facadePart != null) {
                    aeFacades[transform.apply(dir).ordinal()] = PortableItemStack.withNBT(facadePart.getItemStack());
                }
            }

            // Bypass copy ae-patterns option
            boolean prevShouldCopyAEPatterns = ctx.getConfig().shouldCopyAEPatterns;
            ctx.getConfig().shouldCopyAEPatterns = true;

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                IPart part = partHost.getPart(dir);

                AEPartData data = aeParts[dir.ordinal()];
                ItemStack partStack = data != null ? data.mPart.toStack() : null;

                if (part != null && !part.getItemStack(PartItemStack.Wrench).equals(partStack)) {
                    partHost.removePart(dir, true);
                }

                if (partStack == null) continue;

                if (!partHost.canAddPart(partStack, dir)) {
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
                    ctx.givePlayerItems(partStack);
                    continue;
                }

                data.updatePart(ctx, partHost, dir);
            }

            ctx.getConfig().shouldCopyAEPatterns = prevShouldCopyAEPatterns;

            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
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
}
