package com.recursive_pineapple.matter_manipulator.mixin.mixins.late;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import crazypants.enderio.conduit.AbstractConduit;
import com.recursive_pineapple.matter_manipulator.mixin.interfaces.ConduitExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = AbstractConduit.class, remap = false)
public abstract class MixinAbstractConduit implements ConduitExt {

    @Shadow
    protected abstract void writeTypeSettingsToNbt(ForgeDirection side, NBTTagCompound settings);

    @Shadow
    protected abstract void readTypeSettings(ForgeDirection side, NBTTagCompound settings);

    @Override
    public NBTTagCompound mm$getSettings(ForgeDirection side) {
        NBTTagCompound settings = new NBTTagCompound();
        writeTypeSettingsToNbt(side, settings);
        return settings;
    }

    @Override
    public void mm$setSettings(ForgeDirection side, NBTTagCompound settings) {
        readTypeSettings(side, settings);
    }
}
