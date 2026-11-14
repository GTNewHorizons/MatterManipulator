package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import com.google.gson.annotations.SerializedName;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import codechicken.lib.vec.BlockCoord;
import codechicken.microblock.BlockMicroMaterial;
import codechicken.microblock.MicroMaterialRegistry;
import codechicken.multipart.MultiPartRegistry;
import codechicken.multipart.TMultiPart;
import codechicken.multipart.TSlottedPart;
import codechicken.multipart.TileMultipart;

/**
 * Stores and restores ForgeMultipart (FMP) tile data.
 */
public class MultipartAnalysisResult implements ITileAnalysisIntegration {

    @SerializedName("p")
    public PartData[] parts;

    public static class PartData {

        @SerializedName("t")
        public String typeId;
        @SerializedName("n")
        public NBTTagCompound nbt;
        @SerializedName("d")
        public PortableItemStack[] drops;

        public PartData() {}

        public PartData clone() {
            PartData dup = new PartData();
            dup.typeId = typeId;
            dup.nbt = nbt == null ? null : (NBTTagCompound) nbt.copy();
            if (drops != null) {
                dup.drops = new PortableItemStack[drops.length];
                for (int i = 0; i < drops.length; i++) {
                    dup.drops[i] = drops[i] == null ? null : drops[i].clone();
                }
            }
            return dup;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((typeId == null) ? 0 : typeId.hashCode());
            result = prime * result + ((nbt == null) ? 0 : nbt.hashCode());
            result = prime * result + Arrays.hashCode(drops);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            PartData other = (PartData) obj;
            if (typeId == null) {
                if (other.typeId != null) return false;
            } else if (!typeId.equals(other.typeId)) return false;
            if (nbt == null) {
                if (other.nbt != null) return false;
            } else if (!nbt.equals(other.nbt)) return false;
            if (!Arrays.equals(drops, other.drops)) return false;
            return true;
        }
    }

    public static MultipartAnalysisResult analyze(TileEntity tile) {
        if (!(tile instanceof TileMultipart multipart)) return null;

        var partList = multipart.jPartList();

        if (partList.isEmpty()) return null;

        MultipartAnalysisResult result = new MultipartAnalysisResult();
        result.parts = new PartData[partList.size()];

        for (int i = 0; i < partList.size(); i++) {
            result.parts[i] = capturePart(partList.get(i));
        }

        return result;
    }

    private static PartData capturePart(TMultiPart part) {
        PartData data = new PartData();

        data.typeId = part.getType();

        data.nbt = new NBTTagCompound();
        data.nbt.setString("id", data.typeId);
        part.save(data.nbt);

        List<ItemStack> dropList = new ArrayList<>();
        for (ItemStack drop : part.getDrops()) {
            dropList.add(drop);
        }
        data.drops = new PortableItemStack[dropList.size()];
        for (int j = 0; j < dropList.size(); j++) {
            data.drops[j] = PortableItemStack.withNBT(dropList.get(j));
        }

        return data;
    }

    @Override
    public boolean apply(IBlockApplyContext ctx) {
        if (parts == null || parts.length == 0) return true;

        World world = ctx.getWorld();
        int x = ctx.getX();
        int y = ctx.getY();
        int z = ctx.getZ();

        removeExistingParts(ctx);

        PartData[] sorted = getSortedParts();

        for (PartData data : sorted) {
            if (!hasSupportBlock(world, data, x, y, z)) {
                ctx.warn(new ChatComponentText("Could not place " + data.typeId + ": no support block"));
                continue;
            }

            if (!tryConsumePartItems(ctx, data)) {
                continue;
            }

            if (!tryPlacePart(ctx, world, new BlockCoord(x, y, z), data)) { return false; }
        }

        syncTileToClient(world, x, y, z);

        return true;
    }

    private static void removeExistingParts(IBlockApplyContext ctx) {
        TileEntity te = ctx.getTileEntity();

        if (!(te instanceof TileMultipart existingTile)) return;

        var existingParts = new ArrayList<>(existingTile.jPartList());

        for (TMultiPart existingPart : existingParts) {
            for (ItemStack drop : existingPart.getDrops()) {
                ctx.givePlayerItems(drop.copy());
            }
            existingTile.remPart(existingPart);
        }
    }

    private PartData[] getSortedParts() {
        PartData[] sorted = parts.clone();
        Arrays.sort(sorted, (a, b) -> Integer.compare(getPartOrder(a), getPartOrder(b)));
        return sorted;
    }

    public static boolean isMultipart(TileEntity tile) {
        return tile instanceof TileMultipart;
    }

    /** Same as {@link #hasAttachedParts()}, for a multipart in the world. */
    public static boolean hasAttachedParts(TileEntity tile) {
        if (!(tile instanceof TileMultipart multipart)) return false;

        for (TMultiPart part : multipart.jPartList()) {
            if (!part.getType().startsWith("mcr_")) return true;
        }

        return false;
    }

    /** Whether every part of a multipart in the world would have its support block if it was at the given position. */
    public static boolean hasSupportAt(TileEntity tile, World world, int x, int y, int z) {
        if (!(tile instanceof TileMultipart multipart)) return true;

        for (TMultiPart part : multipart.jPartList()) {
            if (part.getType().startsWith("mcr_") || !(part instanceof TSlottedPart slotted)) continue;

            // parts sitting on a face take that face's slot and nothing else
            int faces = slotted.getSlotMask() & 0x3F;
            if (Integer.bitCount(faces) != 1) continue;

            // AE2 cable buses also take the slot of a lone bus or facade, but they don't need support
            NBTTagCompound nbt = new NBTTagCompound();
            part.save(nbt);
            if (!nbt.hasKey("side") && !nbt.hasKey("orient")) continue;

            ForgeDirection dir = ForgeDirection.getOrientation(Integer.numberOfTrailingZeros(faces));
            if (world.isAirBlock(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ)) return false;
        }

        return true;
    }

    /** Whether any part needs a neighbouring block to stay, such as a wire or gate. Microblocks don't. */
    public boolean hasAttachedParts() {
        if (parts == null) return false;

        for (PartData data : parts) {
            if (data != null && data.typeId != null && !data.typeId.startsWith("mcr_")) return true;
        }

        return false;
    }

    /** Whether every part would have its support block at the given position. */
    public boolean hasSupport(World world, int x, int y, int z) {
        if (parts == null) return true;

        for (PartData data : parts) {
            if (data != null && !hasSupportBlock(world, data, x, y, z)) return false;
        }

        return true;
    }

    private static boolean hasSupportBlock(World world, PartData data, int x, int y, int z) {
        if (data.typeId == null || data.typeId.startsWith("mcr_")) return true;
        if (data.nbt == null) return true;

        int side = -1;

        if (data.nbt.hasKey("side")) {
            side = data.nbt.getByte("side") & 0xFF;
        } else if (data.nbt.hasKey("orient")) {
            side = (data.nbt.getByte("orient") & 0xFF) >> 2;
        }

        if (side < 0 || side >= 6) return true;

        ForgeDirection dir = ForgeDirection.getOrientation(side);
        return !world.isAirBlock(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ);
    }

    private static boolean tryConsumePartItems(IBlockApplyContext ctx, PartData data) {
        if (data.drops == null) return true;

        List<ItemStack> consumed = new ArrayList<>();

        for (PortableItemStack drop : data.drops) {
            ItemStack stack = drop.toStack();
            if (stack == null) continue;

            boolean ok = ctx
                .tryConsumeItems(
                    Collections.singletonList(BigItemStack.create(stack)),
                    data.typeId != null && data.typeId.startsWith("mcr_") ? 0 : IPseudoInventory.CONSUME_FUZZY
                )
                .leftBoolean();

            if (!ok) {
                ctx.warn(new ChatComponentText("Could not find item: " + stack.getDisplayName()));

                for (ItemStack refund : consumed) {
                    ctx.givePlayerItems(refund);
                }
                return false;
            }

            consumed.add(stack);
        }

        return true;
    }

    private static boolean tryPlacePart(IBlockApplyContext ctx, World world, BlockCoord coord, PartData data) {
        TMultiPart part = MultiPartRegistry.loadPart(data.typeId, data.nbt);

        if (part == null) {
            refundPartItems(ctx, data);
            ctx.error(new ChatComponentText("Could not create multipart: " + data.typeId));
            return false;
        }

        part.load(data.nbt);

        try {
            TileMultipart result = TileMultipart.addPart(world, coord, part);

            if (result == null) {
                MMMod.LOG.warn(
                    "addPart returned null for {} (shape={}) at {},{},{}",
                    data.typeId,
                    data.nbt.hasKey("shape") ? (data.nbt.getByte("shape") & 0xFF) : "N/A",
                    coord.x,
                    coord.y,
                    coord.z
                );
                refundPartItems(ctx, data);
                ctx.warn(new ChatComponentText("Could not place multipart: " + data.typeId));
            }
        } catch (Exception e) {
            MMMod.LOG.error("Failed to add multipart " + data.typeId, e);
            refundPartItems(ctx, data);
            ctx.error(new ChatComponentText("Failed to add multipart: " + data.typeId));
            return false;
        }

        return true;
    }

    private static void refundPartItems(IBlockApplyContext ctx, PartData data) {
        if (data.drops == null) return;

        for (PortableItemStack drop : data.drops) {
            ItemStack refund = drop.toStack();
            if (refund != null) {
                ctx.givePlayerItems(refund);
            }
        }
    }

    // Don't use sendDescPacket here, it causes null-bounds crashes from redundant tile reconstruction.
    private static void syncTileToClient(World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (te instanceof TileMultipart tile) {
            tile.markDirty();
            world.markBlockForUpdate(x, y, z);
        }
    }

    @Override
    public boolean getRequiredItemsForExistingBlock(IBlockApplyContext context) {
        TileEntity te = context.getTileEntity();

        if (te instanceof TileMultipart multipart) {
            for (TMultiPart part : multipart.jPartList()) {
                for (ItemStack drop : part.getDrops()) {
                    context.givePlayerItems(drop.copy());
                }
            }
        }

        consumeAllPartDrops(context);

        return true;
    }

    @Override
    public boolean getRequiredItemsForNewBlock(IBlockApplyContext context) {
        consumeAllPartDrops(context);
        return true;
    }

    private void consumeAllPartDrops(IBlockApplyContext context) {
        if (parts == null) return;

        for (PartData data : parts) {
            if (data.drops == null) continue;

            for (PortableItemStack drop : data.drops) {
                ItemStack stack = drop.toStack();
                if (stack != null) {
                    context.tryConsumeItems(
                        Collections.singletonList(BigItemStack.create(stack)),
                        data.typeId != null && data.typeId.startsWith("mcr_") ? 0 : IPseudoInventory.CONSUME_FUZZY
                    );
                }
            }
        }
    }

    private BlockMicroMaterial getFirstMicroblockMaterial() {
        if (parts == null) return null;

        for (PartData data : parts) {
            if (data == null || data.nbt == null || data.typeId == null) continue;
            if (!data.typeId.startsWith("mcr_")) continue;

            // Material registry keys can differ from block IDs, including legacy remaps.
            var material = MicroMaterialRegistry.getMaterial(data.nbt.getString("material"));
            if (material instanceof BlockMicroMaterial blockMaterial) return blockMaterial;
        }

        return null;
    }

    @Override
    public Block getPreviewBlock() {
        BlockMicroMaterial material = getFirstMicroblockMaterial();
        return material != null ? material.block() : null;
    }

    @Override
    public int getPreviewMeta() {
        BlockMicroMaterial material = getFirstMicroblockMaterial();
        return material != null ? material.meta() : 0;
    }

    @Override
    public void getItemTag(ItemStack stack) {
        if (parts != null) {
            NBTTagCompound tag = stack.getTagCompound() != null ? stack.getTagCompound() : new NBTTagCompound();
            tag.setInteger("FMPParts", parts.length);
            stack.setTagCompound(tag);
        }
    }

    @Override
    public void getItemDetailsChat(List<IChatComponent> details) {
        if (parts != null && parts.length > 0) {
            details.add(new ChatComponentText(parts.length + (parts.length == 1 ? " part" : " parts")));
        }
    }

    private static int getPartOrder(PartData data) {
        if (data == null || data.typeId == null) return 1;

        return switch (data.typeId) {
            case "mcr_face", "mcr_hllw", "mcr_edge", "mcr_cnr" -> 0;
            default -> 1;
        };
    }

    @Override
    public void transform(Transform transform) {
        if (parts == null) return;

        for (PartData data : parts) {
            if (data == null || data.nbt == null) continue;

            FMPPartTransforms.transformSide(data.nbt, transform);
            FMPPartTransforms.transformOrient(data.nbt, transform);
            FMPPartTransforms.transformMicroblockShape(data.typeId, data.nbt, transform);
            FMPPartTransforms.clearConnMap(data.nbt);
        }
    }

    @Override
    public void migrate() {}

    @Override
    public MultipartAnalysisResult clone() {
        MultipartAnalysisResult dup = new MultipartAnalysisResult();

        if (parts != null) {
            dup.parts = new PartData[parts.length];
            for (int i = 0; i < parts.length; i++) {
                dup.parts[i] = parts[i] == null ? null : parts[i].clone();
            }
        }

        return dup;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(parts);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        MultipartAnalysisResult other = (MultipartAnalysisResult) obj;
        return Arrays.equals(parts, other.parts);
    }
}
