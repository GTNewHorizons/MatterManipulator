package com.recursive_pineapple.matter_manipulator.client;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import gregtech.api.interfaces.IIconContainer;

import com.recursive_pineapple.matter_manipulator.common.utils.Mods;

@SideOnly(Side.CLIENT)
public class Textures {

    public static class MTEMMUplink {

        public static final IIconContainer ACTIVE_GLOW = gregtech.api.enums.Textures.BlockIcons.customOptional(
            Mods.MatterManipulator.getResourcePath("machines", "uplink", "OVERLAY_FRONT_ACTIVE_GLOW")
        );
        public static final IIconContainer IDLE_GLOW = gregtech.api.enums.Textures.BlockIcons.customOptional(
            Mods.MatterManipulator.getResourcePath("machines", "uplink", "OVERLAY_FRONT_IDLE_GLOW")
        );
        public static final IIconContainer OFF = gregtech.api.enums.Textures.BlockIcons.custom(
            Mods.MatterManipulator.getResourcePath("machines", "uplink", "OVERLAY_FRONT_OFF")
        );
    }
}
