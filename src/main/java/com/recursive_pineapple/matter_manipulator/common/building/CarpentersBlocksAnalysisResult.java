package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;

import net.minecraftforge.common.util.ForgeDirection;

import com.carpentersblocks.data.Stairs;
import com.carpentersblocks.tileentity.TEBase;
import com.google.gson.annotations.SerializedName;
import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentItemName;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

public class CarpentersBlocksAnalysisResult implements ITileAnalysisIntegration {

    private static final byte TYPE_UNKNOWN = 0;
    private static final byte TYPE_STAIRS = 1;
    private static final byte TYPE_SLAB = 2;

    // Slab data to ForgeDirection ordinal mapping, from Slab.DIR_MAP.
    private static final int[] SLAB_DIR_MAP = {
        4, 5, 0, 1, 2, 3
    };

    @SerializedName("d")
    public int data;

    @SerializedName("t")
    public byte blockType;

    @SerializedName("c")
    public PortableItemStack[] covers;

    public static CarpentersBlocksAnalysisResult analyze(TileEntity te) {
        if (!(te instanceof TEBase cbTE)) return null;

        var result = new CarpentersBlocksAnalysisResult();

        result.data = cbTE.getData();

        String blockName = te.getBlockType()
            .getUnlocalizedName();
        if (blockName.contains("Stairs")) {
            result.blockType = TYPE_STAIRS;
        } else if (blockName.contains("blockCarpentersBlock")) {
            result.blockType = TYPE_SLAB;
        }

        PortableItemStack[] covers = new PortableItemStack[7];
        boolean hasCovers = false;
        for (int i = 0; i < 7; i++) {
            ItemStack cover = cbTE.getAttribute(TEBase.ATTR_COVER[i]);
            if (cover != null) {
                covers[i] = new PortableItemStack(cover);
                hasCovers = true;
            }
        }
        if (hasCovers) {
            result.covers = covers;
        }

        return result;
    }

    @Override
    public boolean apply(IBlockApplyContext ctx) {
        TileEntity te = ctx.getTileEntity();

        if (!(te instanceof TEBase cbTE)) return false;

        cbTE.setData(data);

        if (covers != null) {
            for (int i = 0; i < covers.length && i < 7; i++) {
                if (covers[i] == null) continue;

                ItemStack coverStack = covers[i].toStack();
                if (coverStack == null) continue;

                if (cbTE.hasAttribute(TEBase.ATTR_COVER[i])) {
                    ItemStack existing = cbTE.getAttribute(TEBase.ATTR_COVER[i]);
                    if (existing != null) {
                        ctx.givePlayerItems(existing.copy());
                    }
                    cbTE.onAttrDropped(TEBase.ATTR_COVER[i]);
                }

                if (ctx.tryConsumeItems(coverStack)) {
                    cbTE.addAttribute(TEBase.ATTR_COVER[i], coverStack);
                }
            }
        }

        return true;
    }

    @Override
    public boolean getRequiredItemsForExistingBlock(IBlockApplyContext context) {
        TileEntity te = context.getTileEntity();

        if (!(te instanceof TEBase cbTE)) return false;

        if (covers != null) {
            for (int i = 0; i < covers.length && i < 7; i++) {
                if (covers[i] == null) continue;

                ItemStack existing = cbTE.getAttribute(TEBase.ATTR_COVER[i]);
                if (existing != null) {
                    PortableItemStack existingPortable = new PortableItemStack(existing);
                    if (
                        existingPortable.item != null && existingPortable.item.equals(covers[i].item) &&
                            java.util.Objects.equals(existingPortable.metadata, covers[i].metadata)
                    ) {
                        continue;
                    }
                    context.givePlayerItems(existing.copy());
                }
                ItemStack needed = covers[i].toStack();
                if (needed != null) context.tryConsumeItems(needed);
            }
        }

        return true;
    }

    @Override
    public boolean getRequiredItemsForNewBlock(IBlockApplyContext context) {
        if (covers != null) {
            for (int i = 0; i < covers.length && i < 7; i++) {
                if (covers[i] != null) {
                    ItemStack needed = covers[i].toStack();
                    if (needed != null) context.tryConsumeItems(needed);
                }
            }
        }
        return true;
    }

    @Override
    public void getItemTag(ItemStack stack) {}

    @Override
    public void getItemDetailsChat(List<IChatComponent> details) {
        if (covers != null && covers[6] != null) {
            ItemStack stack = covers[6].toStack();
            if (stack != null) details.add(new ChatComponentItemName(stack));
        }
    }

    @Override
    public void transform(Transform transform) {
        switch (blockType) {
            case TYPE_STAIRS -> transformStairs(transform);
            case TYPE_SLAB -> transformSlab(transform);
            default -> {}
        }
    }

    private void transformStairs(Transform transform) {
        if (data < 0 || data >= Stairs.stairsList.length) return;

        Stairs stairs = Stairs.stairsList[data];
        if (stairs == null) return;

        List<ForgeDirection> newFacings = new ArrayList<>();
        for (ForgeDirection facing : stairs.facings) {
            newFacings.add(transform.apply(facing));
        }

        for (Stairs candidate : Stairs.stairsList) {
            if (
                candidate != null && candidate.stairsType == stairs.stairsType &&
                    candidate.facings.size() == newFacings.size() &&
                    candidate.facings.containsAll(newFacings)
            ) {
                data = candidate.stairsID;
                return;
            }
        }
    }

    private void transformSlab(Transform transform) {
        if (data < 1 || data > 6) return;

        ForgeDirection dir = ForgeDirection.getOrientation(SLAB_DIR_MAP[data - 1]);
        ForgeDirection newDir = transform.apply(dir);

        for (int i = 0; i < SLAB_DIR_MAP.length; i++) {
            if (SLAB_DIR_MAP[i] == newDir.ordinal()) {
                data = i + 1;
                return;
            }
        }
    }

    @Override
    public void migrate() {}

    @Override
    public CarpentersBlocksAnalysisResult clone() {
        var dup = new CarpentersBlocksAnalysisResult();

        dup.data = data;
        dup.blockType = blockType;
        if (covers != null) {
            dup.covers = new PortableItemStack[covers.length];
            for (int i = 0; i < covers.length; i++) {
                dup.covers[i] = covers[i] == null ? null : covers[i].clone();
            }
        }

        return dup;
    }

    @Override
    public int hashCode() {
        int result = data;
        result = 31 * result + blockType;
        result = 31 * result + Arrays.hashCode(covers);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CarpentersBlocksAnalysisResult other)) return false;
        return data == other.data && blockType == other.blockType && Arrays.equals(covers, other.covers);
    }

    @Override
    public String toString() {
        return "CarpentersBlocksAnalysisResult [data=" + data
            + ", blockType="
            + blockType
            + ", covers="
            + Arrays.toString(covers)
            + "]";
    }
}
