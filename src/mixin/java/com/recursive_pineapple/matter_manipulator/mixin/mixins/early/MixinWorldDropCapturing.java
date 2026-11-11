package com.recursive_pineapple.matter_manipulator.mixin.mixins.early;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.recursive_pineapple.matter_manipulator.mixin.BlockCaptureDrops;

@Mixin(World.class)
public class MixinWorldDropCapturing implements BlockCaptureDrops {

    @Shadow(remap = false)
    public boolean restoringBlockSnapshots;

    @Unique
    private List<EntityItem> mm$capturedDrops;

    @Override
    public void captureDrops() {
        if (mm$capturedDrops == null) {
            mm$capturedDrops = new ArrayList<>();
        }
    }

    @Override
    public List<EntityItem> stopCapturingDropEntities() {
        List<EntityItem> drops = mm$capturedDrops;
        mm$capturedDrops = null;
        return drops == null ? new ArrayList<>() : drops;
    }

    // At the head, because Cauldron based servers (Thermos) move the rest of this method into another one
    @Inject(method = "spawnEntityInWorld", cancellable = true, at = @At("HEAD"))
    public void mm$captureEntityItems(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (mm$capturedDrops == null || !(entity instanceof EntityItem entityItem)) return;

        // Forge discards these
        if (restoringBlockSnapshots) return;

        mm$capturedDrops.add(entityItem);
        cir.setReturnValue(true);
    }
}
