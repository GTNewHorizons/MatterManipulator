package com.recursive_pineapple.matter_manipulator.common.building.schedulable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.tileentities.machines.MTEHatchCraftingInputME;

import appeng.api.AEApi;
import appeng.parts.p2p.PartP2PInterface;

import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.GTAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.building.SmartCopyIntegration;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.ISchedulable;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.scheduler.WorkScheduler;

public class SchedulableSmartCopyApplyAnalyzer implements ISchedulable {

    public final World world;
    public final MMState mmState;
    public final List<PendingBlock> blocks;

    protected boolean isDone;
    protected BlockSpec spec;
    protected Iterator<PendingBlock> blockIterator;

    private WorkScheduler scheduler;

    public SchedulableSmartCopyApplyAnalyzer(World world, MMState mmState, List<PendingBlock> blocks) {

        this.world = world;
        this.mmState = mmState;
        this.blocks = blocks;
    }

    @Override
    public boolean hasWork() {
        return !isDone;
    }

    @Override
    public boolean doWork(long tick) {
        if (isDone) return false;

        if (spec == null) {
            spec = new BlockSpec();
        }

        if (blockIterator == null) {
            blockIterator = blocks.iterator();
        } else if (!blockIterator.hasNext()) {
            isDone = true;
            return false;
        }

        PendingBlock block = blockIterator.next();

        if (Mods.GregTech.isModLoaded() && mmState.config.replaceCribsWithProxies && mmState.hasCap(ItemMatterManipulator.ALLOW_SMART_COPY)) {
            applySmartCopyCrib(world, block, mmState.config.coordA, spec);
        }

        if (Mods.AppliedEnergistics2.isModLoaded() && block.ae instanceof AEAnalysisResult ae && ae.mAEParts != null) {
            if (mmState.config.p2pInterfaceCopyMode == MMState.P2PInterfaceCopyMode.LINK_TO_ORIGINAL) {
                applyP2PInterfaceCopyMode(ae.mAEParts);
            }

            if (mmState.config.interfaceCopyMode == MMState.InterfaceCopyMode.CONVERT_TO_P2P) {
                applySmartCopyP2P(world, block, mmState.config.coordA, ae.mAEParts);
            }
        }

        return true;
    }

    @Override
    public void setScheduler(WorkScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public WorkScheduler getScheduler() {
        return scheduler;
    }

    @Optional(Mods.Names.GREG_TECH_NH)
    protected static ItemStack PROXY_STACK;

    @Optional(Mods.Names.GREG_TECH_NH)
    public static void applySmartCopyCrib(World world, PendingBlock block, Location coordA, BlockSpec spec) {
        if (PROXY_STACK == null) {
            PROXY_STACK = ItemList.Hatch_CraftingInput_Bus_Slave.get(1L);
        }

        int srcX = coordA.x + block.x;
        int srcY = coordA.y + block.y;
        int srcZ = coordA.z + block.z;

        TileEntity te = world.getTileEntity(srcX, srcY, srcZ);
        if (!(te instanceof IGregTechTileEntity igte)) return;

        IMetaTileEntity mte = igte.getMetaTileEntity();
        if (!(mte instanceof MTEHatchCraftingInputME)) return;

        block.spec = spec;
        SmartCopyIntegration sc = new SmartCopyIntegration();
        sc.action = SmartCopyIntegration.SmartCopyAction.CRIB_TO_PROXY;
        sc.sourceX = srcX;
        sc.sourceY = srcY;
        sc.sourceZ = srcZ;
        block.smartCopy = sc;

        NBTTagCompound proxyData = new NBTTagCompound();
        proxyData.setString("type", "craftingInputProxy");
        NBTTagCompound masterNBT = new NBTTagCompound();
        masterNBT.setInteger("x", srcX);
        masterNBT.setInteger("y", srcY);
        masterNBT.setInteger("z", srcZ);
        proxyData.setTag("master", masterNBT);

        GTAnalysisResult gtResult = block.gt instanceof GTAnalysisResult ? (GTAnalysisResult) block.gt : new GTAnalysisResult();
        gtResult.mGTData = MMUtils.toJsonObject(proxyData);
        gtResult.mGTGhostCircuit = 0;
        block.gt = gtResult;

        block.ae = null;
        block.inventory = null;
    }

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    public static List<AEPartData> getBoundP2PInterfaces(AEPartData[] parts) {
        List<AEPartData> boundP2ps = new ArrayList<>();

        for (AEPartData partData : parts) {
            if (partData == null || partData.mP2PFreq == 0) continue;
            if (!partData.isPartSubclassOf(PartP2PInterface.class)) continue;

            boundP2ps.add(partData);
        }

        return boundP2ps;
    }

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    public static void applyP2PInterfaceCopyMode(AEPartData[] parts) {
        for (AEPartData partData : getBoundP2PInterfaces(parts)) {
            partData.mP2POutput = true;
            partData.mAEPatterns = null;
        }
    }

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    protected static ItemStack P2P_INTERFACE_STACK;
    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    protected static ItemStack P2P_DUAL_INTERFACE_STACK;

    @Optional(Mods.Names.APPLIED_ENERGISTICS2)
    public static void applySmartCopyP2P(World world, PendingBlock block, Location coordA, AEPartData[] parts) {
        if (P2P_INTERFACE_STACK == null) {
            P2P_INTERFACE_STACK = AEApi.instance().definitions().parts().p2PTunnelMEInterface().maybeStack(1).orNull();
        }

        if (P2P_INTERFACE_STACK == null) return;

        if (Mods.AE2FluidCraft.isModLoaded() && P2P_DUAL_INTERFACE_STACK == null) {
            P2P_DUAL_INTERFACE_STACK = MMState.getFluidP2PInterfaceStack();
        }

        int srcX = coordA.x + block.x;
        int srcY = coordA.y + block.y;
        int srcZ = coordA.z + block.z;

        for (int i = 0; i < parts.length; i++) {
            AEPartData partData = parts[i];

            if (partData == null) continue;
            if (partData.mAEPatterns == null) continue;
            if (partData.isP2P()) continue;

            boolean isDual = P2P_DUAL_INTERFACE_STACK != null && MMState.isFluidInterfaceStack(partData.mPart);
            ItemStack replacementStack = isDual ? P2P_DUAL_INTERFACE_STACK : P2P_INTERFACE_STACK;

            ForgeDirection side = AEAnalysisResult.ALL_DIRECTIONS[i];
            long freq = MMState.nextP2PFrequency();
            PortableItemStack sourcePart = partData.mPart;

            // Replace the interface part with a P2P tunnel in the analysis
            partData.mPart = new PortableItemStack(replacementStack);
            partData.mP2POutput = true;
            partData.mP2PFreq = freq;
            partData.mAEPatterns = null;

            if (block.smartCopy == null) {
                block.smartCopy = new SmartCopyIntegration();
            }
            block.smartCopy.action = SmartCopyIntegration.SmartCopyAction.INTERFACE_TO_P2P;

            if (block.smartCopy.p2pActions == null) {
                block.smartCopy.p2pActions = new ArrayList<>();
            }

            // Record info for the source-side P2P placement (done later by PendingBuild)
            SmartCopyIntegration.P2PInfo info = new SmartCopyIntegration.P2PInfo();
            info.freq = freq;
            info.srcX = srcX;
            info.srcY = srcY;
            info.srcZ = srcZ;
            info.srcSide = side;
            info.destSide = side;
            info.p2pItem = new PortableItemStack(replacementStack);
            info.sourcePart = sourcePart;
            block.smartCopy.p2pActions.add(info);
        }
    }
}
