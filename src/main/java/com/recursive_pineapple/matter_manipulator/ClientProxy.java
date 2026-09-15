package com.recursive_pineapple.matter_manipulator;

import com.recursive_pineapple.matter_manipulator.common.utils.BogoCompat;
import cpw.mods.fml.common.Loader;
import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

import com.recursive_pineapple.matter_manipulator.common.entities.EntityItemLarge;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMKeyInputs;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMRenderer;
import net.minecraftforge.common.MinecraftForge;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        EntityItemLarge.registerClient();
        MMRenderer.init();
        MMKeyInputs.init();

        if (Loader.isModLoaded("bogosorter")) MinecraftForge.EVENT_BUS.register(new BogoCompat());
    }

    @Override
    public EntityPlayer getThePlayer() {
        return FMLClientHandler.instance().getClientPlayerEntity();
    }
}
