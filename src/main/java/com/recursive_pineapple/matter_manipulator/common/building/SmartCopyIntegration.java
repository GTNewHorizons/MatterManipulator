package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;

import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

public class SmartCopyIntegration implements ITileAnalysisIntegration {

    public enum SmartCopyAction {
        NONE,
        CRIB_TO_PROXY,
        INTERFACE_TO_P2P
    }

    public static class P2PInfo {

        public long freq;
        public int srcX, srcY, srcZ;
        public ForgeDirection srcSide;
        public ForgeDirection destSide;
        public PortableItemStack p2pItem;

        public P2PInfo clone() {
            P2PInfo dup = new P2PInfo();
            dup.freq = freq;
            dup.srcX = srcX;
            dup.srcY = srcY;
            dup.srcZ = srcZ;
            dup.srcSide = srcSide;
            dup.destSide = destSide;
            dup.p2pItem = p2pItem;
            return dup;
        }
    }

    public SmartCopyAction action = SmartCopyAction.NONE;
    public int sourceX, sourceY, sourceZ;
    public List<P2PInfo> p2pActions;

    @Override
    public boolean apply(IBlockApplyContext ctx) {
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

    }

    @Override
    public void getItemDetailsChat(List<IChatComponent> details) {

    }

    @Override
    public void transform(Transform transform) {
        if (p2pActions != null) {
            for (P2PInfo info : p2pActions) {
                if (info.destSide != null && info.destSide != ForgeDirection.UNKNOWN) {
                    info.destSide = transform.apply(info.destSide);
                }
            }
        }
    }

    @Override
    public SmartCopyIntegration clone() {
        SmartCopyIntegration dup = new SmartCopyIntegration();
        dup.action = action;
        dup.sourceX = sourceX;
        dup.sourceY = sourceY;
        dup.sourceZ = sourceZ;
        if (p2pActions != null) {
            dup.p2pActions = new ArrayList<>(p2pActions.size());
            for (P2PInfo info : p2pActions) {
                dup.p2pActions.add(info.clone());
            }
        }
        return dup;
    }

    @Override
    public void migrate() {

    }
}
