package com.recursive_pineapple.matter_manipulator.common.building;

import com.google.common.collect.ImmutableList;

public enum CopyableProperty {

    FACING,
    FORWARD,
    UP,
    LEFT,
    TOP,
    ROTATION,
    MODE,
    TEXT,
    ORIENTATION,
    DELAY,
    INVERTED,
    COLOR,
    ROTATION_STATE,
    ;

    public static final ImmutableList<CopyableProperty> VALUES = ImmutableList.copyOf(values());

    @Override
    public String toString() {
        return name().toLowerCase();
    }

    public String getUnlocalizedName() {
        return switch (this) {
            case FACING -> "mm.enum.copyable.property.facing";
            case FORWARD -> "mm.enum.copyable.property.forward";
            case UP -> "mm.enum.copyable.property.up";
            case LEFT -> "mm.enum.copyable.property.left";
            case TOP -> "mm.enum.copyable.property.top";
            case ROTATION -> "mm.enum.copyable.property.rotation";
            case MODE -> "mm.enum.copyable.property.mode";
            case TEXT -> "mm.enum.copyable.property.text";
            case ORIENTATION -> "mm.enum.copyable.property.orientation";
            case DELAY -> "mm.enum.copyable.property.delay";
            case INVERTED -> "mm.enum.copyable.property.inverted";
            case COLOR -> "mm.enum.copyable.property.color";
            case ROTATION_STATE -> "mm.enum.copyable.property.rotation_state";
        };
    }
}
