package com.recursive_pineapple.matter_manipulator.common.building;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;

import gregtech.api.GregTechAPI;
import gregtech.api.util.CoverBehaviorBase;
import gregtech.api.util.ISerializableObject;
import gregtech.common.covers.CoverInfo;

/**
 * Contains all GT cover analysis data.
 */
public class CoverData {

    public PortableItemStack cover;
    public NBTBase coverData;
    public Integer tickRateAddition;

    public transient Integer coverID;
    public transient CoverBehaviorBase<?> behaviour;
    public transient ISerializableObject coverDataObject;

    public CoverData() {}

    public CoverData(PortableItemStack cover, NBTBase coverData, int tickRateAddition) {
        this.cover = cover;
        this.coverData = coverData;
        this.tickRateAddition = tickRateAddition == 0 ? null : tickRateAddition;
    }

    public ItemStack getCover() {
        return cover.toStack();
    }

    public int getCoverID() {
        if (coverID == null) {
            ItemStack stack = getCover();

            coverID = (Item.getIdFromItem(stack.getItem()) & 0xFFFF) | ((Items.feather.getDamage(stack) & 0xFFFF) << 16);
        }

        return coverID;
    }

    public CoverBehaviorBase<?> getCoverBehaviour() {
        if (behaviour == null) {
            behaviour = GregTechAPI.getCoverBehaviorNew(getCoverID());
        }

        return behaviour;
    }

    public ISerializableObject getCoverData() {
        if (coverDataObject == null) {
            coverDataObject = getCoverBehaviour().createDataObject(coverData);
        }

        return coverDataObject;
    }

    @Override
    public CoverData clone() {
        CoverData dup = new CoverData();

        dup.cover = cover.clone();
        dup.coverData = coverData.copy();
        dup.tickRateAddition = tickRateAddition;
        dup.coverID = coverID;

        return dup;
    }

    /**
     * Converts a CoverInfo into a CoverData.
     *
     * @return The CoverData, or null if there's no cover.
     */
    public static CoverData fromInfo(CoverInfo info) {
        if (info.getCoverID() == 0) return null;

        int itemId = info.getCoverID() & 0xFFFF;
        int metadata = (info.getCoverID() >> 16) & 0xFFFF;

        Item item = Item.getItemById(itemId);

        return new CoverData(
            new PortableItemStack(item, metadata),
            info.getCoverData()
                .saveDataToNBT(),
            info.getTickRateAddition()
        );
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((cover == null) ? 0 : cover.hashCode());
        result = prime * result + ((coverData == null) ? 0 : coverData.hashCode());
        result = prime * result + ((tickRateAddition == null) ? 0 : tickRateAddition.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        CoverData other = (CoverData) obj;
        if (cover == null) {
            if (other.cover != null) return false;
        } else if (!cover.equals(other.cover)) return false;
        if (coverData == null) {
            if (other.coverData != null) return false;
        } else if (!coverData.equals(other.coverData)) return false;
        if (tickRateAddition == null) {
            if (other.tickRateAddition != null) return false;
        } else if (!tickRateAddition.equals(other.tickRateAddition)) return false;
        return true;
    }

}
