package com.recursive_pineapple.matter_manipulator.mixin.mixins.late;

import java.util.Map;

import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.mixin.interfaces.ConduitExt.InsulatedExt;
import crazypants.enderio.conduit.ConnectionMode;
import crazypants.enderio.conduit.redstone.InsulatedRedstoneConduit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = InsulatedRedstoneConduit.class, remap = false)
public abstract class MixinInsulatedRedstoneConduit implements InsulatedExt {

    @Shadow
    @Final
    private Map<ForgeDirection, ConnectionMode> forcedConnections;

    @Override
    public ConnectionMode mm$getForcedConnection(ForgeDirection side) {
        return forcedConnections.get(side);
    }

    @Override
    public void mm$setForcedConnection(ForgeDirection side, ConnectionMode mode) {
        if (mode == null) {
            forcedConnections.remove(side);
        } else {
            forcedConnections.put(side, mode);
        }
    }
}
