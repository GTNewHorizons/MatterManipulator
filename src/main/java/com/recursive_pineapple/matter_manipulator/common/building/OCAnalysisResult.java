package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

import li.cil.oc.common.item.data.TransposerData;
import li.cil.oc.common.tileentity.Transposer;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public class OCAnalysisResult implements ITileAnalysisIntegration {

    public int transposerRate = 0;

    private static final OCAnalysisResult NO_OP = new OCAnalysisResult();

    public static OCAnalysisResult analyze(TileEntity te) {
        OCAnalysisResult result = new OCAnalysisResult();

        if (te instanceof Transposer transposer) {
            result.transposerRate = transposer.info().fluidTransferRate();
        }

        return result.equals(NO_OP) ? null : result;
    }

    public OCAnalysisResult() {}

    @Override
    public boolean apply(IBlockApplyContext ctx) {
        TileEntity te = ctx.getTileEntity();

        if (transposerRate != 0 && te instanceof Transposer transposer) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger(TransposerData.FLUID_TRANSFER_RATE(), transposerRate);

            transposer.info().load(tag);
        }

        return true;
    }

    @Override
    public boolean getRequiredItemsForExistingBlock(IBlockApplyContext context) {
        return true;
    }

    @Override
    public boolean getRequiredItemsForNewBlock(IBlockApplyContext context) {
        return true;
    }

    @Override
    public void getItemTag(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound() != null ? stack.getTagCompound() : new NBTTagCompound();

        if (transposerRate != 0) {
            tag.setInteger(TransposerData.FLUID_TRANSFER_RATE(), transposerRate);
        }

        stack.setTagCompound(tag);
    }

    @Override
    public void getItemDetailsChat(List<IChatComponent> details) {}

    @Override
    public void transform(Transform transform) {}

    @Override
    public void migrate() {}

    @Override
    public OCAnalysisResult clone() {
        OCAnalysisResult dup = new OCAnalysisResult();

        dup.transposerRate = transposerRate;

        return dup;
    }
}
