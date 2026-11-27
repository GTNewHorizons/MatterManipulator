package com.recursive_pineapple.matter_manipulator.mixin.interfaces;

import net.minecraft.nbt.NBTTagCompound;

import net.minecraftforge.common.util.ForgeDirection;

import crazypants.enderio.conduit.ConnectionMode;

public interface ConduitExt {

    NBTTagCompound mm$getSettings(ForgeDirection side);

    void mm$setSettings(ForgeDirection side, NBTTagCompound settings);

    interface InsulatedExt {

        ConnectionMode mm$getForcedConnection(ForgeDirection side);

        void mm$setForcedConnection(ForgeDirection side, ConnectionMode mode);
    }

    interface SwitchExt {

        boolean mm$isOn();

        void mm$setOn(boolean on);
    }
}
