package com.recursive_pineapple.matter_manipulator.mixin.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.recursive_pineapple.matter_manipulator.event.ClientChatEvent;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraftforge.common.MinecraftForge;

@Mixin(EntityClientPlayerMP.class)
public abstract class MixinGuiChat {

    @Inject(
        method = "sendChatMessage",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mm$onSendChat(String p_71165_1_, CallbackInfo ci) {
        ClientChatEvent.Pre event = new ClientChatEvent.Pre(p_71165_1_);

        if (MinecraftForge.EVENT_BUS.post(event)) {
            //            Minecraft.getMinecraft().displayGuiScreen(null);
            ci.cancel();
        }
    }
}
