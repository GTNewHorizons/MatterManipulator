package com.recursive_pineapple.matter_manipulator.mixin.mixins.late;

import crazypants.enderio.conduit.redstone.RedstoneSwitch;
import com.recursive_pineapple.matter_manipulator.mixin.interfaces.ConduitExt.SwitchExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = RedstoneSwitch.class, remap = false)
public abstract class MixinRedstoneSwitch implements SwitchExt {

    @Shadow
    private boolean isOn;

    @Shadow
    private void toggleSwitch() {}

    @Override
    public boolean mm$isOn() {
        return isOn;
    }

    @Override
    public void mm$setOn(boolean on) {
        if (isOn != on) toggleSwitch();
    }
}
