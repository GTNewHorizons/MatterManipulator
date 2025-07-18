package com.recursive_pineapple.matter_manipulator.common.building;

import java.util.Arrays;
import java.util.Optional;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.AEApi;
import appeng.api.implementations.tiles.ISegmentedInventory;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartItemStack;
import appeng.api.util.IConfigurableObject;
import appeng.helpers.ICustomNameObject;
import appeng.helpers.IOreFilterable;
import appeng.helpers.IPriorityHost;
import appeng.me.GridAccessException;
import appeng.me.cache.P2PCache;
import appeng.parts.automation.UpgradeInventory;
import appeng.parts.p2p.PartP2PTunnel;
import appeng.parts.p2p.PartP2PTunnelNormal;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.providers.IItemProvider;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

/**
 * Stores data for AE facade parts. Also stores the data for AE cables.
 */
public class AEPartData {

    public PortableItemStack mPart;
    public String mSettingsName = null;
    public NBTTagCompound mSettings = null;
    public String mCustomName = null;

    public PortableItemStack[] mAEUpgrades = null;
    public InventoryAnalysis mConfig = null;
    public InventoryAnalysis mAEPatterns = null;
    public String mOreDict = null;

    public boolean mP2POutput = false;
    public long mP2PFreq = 0;

    public int priority = 0;

    private transient Optional<Class<? extends IPart>> mPartClass;

    public AEPartData() {

    }

    /**
     * Analyzes the part. An AEPartData always does something if a part is present, so we'll never need to return null.
     */
    public AEPartData(IPart part) {
        mPart = new PortableItemStack(part.getItemStack(PartItemStack.Wrench));

        if (part instanceof ICustomNameObject customName) {
            mCustomName = customName.hasCustomName() ? customName.getCustomName() : null;
        }

        if (part instanceof IOreFilterable filterable) {
            mOreDict = filterable.getFilter();

            if ("".equals(mOreDict)) mOreDict = null;
        }

        if (part instanceof IConfigurableObject configurable && configurable.getConfigManager() != null) {
            NBTTagCompound settings = new NBTTagCompound();
            configurable.getConfigManager()
                .writeToNBT(settings);
            mSettings = settings.hasNoTags() ? null : settings;
        }

        if (part instanceof PartP2PTunnel tunnel) {
            mP2POutput = tunnel.isOutput();
            mP2PFreq = tunnel.getFrequency();
        }

        if (part instanceof ISegmentedInventory segmentedInventory) {
            IInventory upgrades = segmentedInventory.getInventoryByName("upgrades");
            if (upgrades != null) {
                mAEUpgrades = MMUtils.streamInventory(upgrades)
                    .filter(x -> x != null)
                    .map(PortableItemStack::new)
                    .toArray(PortableItemStack[]::new);
            }

            IInventory config = segmentedInventory.getInventoryByName("config");
            if (config != null) {
                mConfig = InventoryAnalysis.fromInventory(config, false);
            }

            IInventory patterns = segmentedInventory.getInventoryByName("patterns");
            if (patterns != null) {
                mAEPatterns = InventoryAnalysis.fromInventory(patterns, false);
            }
        }

        if (part instanceof IPriorityHost priorityHost) {
            priority = priorityHost.getPriority();
        }
    }

    public Class<? extends IPart> getPartClass() {
        if (mPartClass == null) {
            if (mPart.toStack().getItem() instanceof IPartItem partItem) {
                IPart part = partItem.createPartFromItemStack(mPart.toStack());

                mPartClass = Optional.ofNullable(part)
                    .map(IPart::getClass);
            }
        }

        return mPartClass.orElse(null);
    }

    public boolean isPartSubclassOf(Class<? extends IPart> superclass) {
        return superclass.isAssignableFrom(getPartClass());
    }

    public boolean isP2P() {
        return isPartSubclassOf(PartP2PTunnel.class);
    }

    public boolean isAttunable() {
        return isPartSubclassOf(PartP2PTunnelNormal.class);
    }

    /**
     * Updates an existing part on a cable bus.
     * Will attune p2ps to the correct variant if possible.
     *
     * @return True if the part was updated successfully, or false if the TileAnalysisResult should bail.
     */
    public boolean updatePart(IBlockApplyContext context, IPartHost partHost, ForgeDirection side) {
        IPart part = partHost.getPart(side);

        boolean success = true;

        if (part instanceof PartP2PTunnelNormal && isAttunable()) {
            partHost.removePart(side, true);

            partHost.addPart(mPart.toStack(), side, context.getRealPlayer());
            part = partHost.getPart(side);
        }

        if (part instanceof PartP2PTunnel<?> tunnel) {
            tunnel.output = mP2POutput;

            if (tunnel.getFrequency() != mP2PFreq) {
                try {
                    final P2PCache p2p = tunnel.getProxy().getP2P();

                    // calls setFrequency
                    p2p.updateFreq(tunnel, mP2PFreq);
                } catch (final GridAccessException e) {
                    // not on a grid yet, so we just set the frequency directly
                    tunnel.setFrequency(mP2PFreq);
                }
            }

            tunnel.onTunnelConfigChange();
        }

        if (part instanceof ICustomNameObject customName) {
            if (mCustomName != null) customName.setCustomName(mCustomName);
        }

        if (part instanceof IConfigurableObject configurable && configurable.getConfigManager() != null) {
            NBTTagCompound settings = mSettings == null ? new NBTTagCompound() : mSettings;
            configurable.getConfigManager().readFromNBT(settings);
        }

        if (part instanceof ISegmentedInventory segmentedInventory) {
            if (segmentedInventory.getInventoryByName("upgrades") instanceof UpgradeInventory upgradeInv) {
                if (!MMUtils.installUpgrades(context, upgradeInv, mAEUpgrades, true, false)) {
                    success = false;
                }
            }

            IInventory config = segmentedInventory.getInventoryByName("config");
            if (config != null) {
                if (!mConfig.apply(context, config, false, false)) success = false;
            }

            IInventory patterns = segmentedInventory.getInventoryByName("patterns");
            if (mAEPatterns != null && patterns != null) {
                if (!mAEPatterns.apply(context, patterns, true, false)) success = false;
            }
        }

        if (part instanceof IOreFilterable filterable) {
            filterable.setFilter(mOreDict == null ? "" : mOreDict);
        }

        if (part instanceof IPriorityHost priorityHost) {
            priorityHost.setPriority(priority);
        }

        return success;
    }

    /**
     * Gets any required items for a part that exists in the world.
     * Effectively a no-op, should not be called with a real IBlockApplyContext.
     *
     * @return True if the op succeeded
     */
    public boolean getRequiredItemsForExistingPart(
        IBlockApplyContext context,
        IPartHost partHost,
        ForgeDirection side
    ) {
        IPart part = partHost.getPart(side);

        boolean success = true;

        if (part instanceof ISegmentedInventory segmentedInventory) {
            if (segmentedInventory.getInventoryByName("upgrades") instanceof UpgradeInventory upgradeInv) {
                if (!MMUtils.installUpgrades(context, upgradeInv, mAEUpgrades, true, false)) success = false;
            }

            IInventory patterns = segmentedInventory.getInventoryByName("patterns");
            if (mAEPatterns != null && patterns != null) {
                if (!mAEPatterns.apply(context, patterns, true, true)) success = false;
            }
        }

        return success;
    }

    /**
     * Gets all required items for a part that doesn't exist.
     *
     * @param context
     * @return True if the op succeeded
     */
    public boolean getRequiredItemsForNewPart(IBlockApplyContext context) {
        if (mAEUpgrades != null) {
            for (PortableItemStack upgrade : mAEUpgrades) {
                context.tryConsumeItems(upgrade.toStack());
            }
        }

        if (mAEPatterns != null) {
            for (IItemProvider item : mAEPatterns.mItems) {
                if (item != null) item.getStack(context, true);
            }
        }

        return true;
    }

    /**
     * Gets the stack that should be consumed/given when this part is created/removed.
     */
    public ItemStack getEffectivePartStack() {
        if (isAttunable() && isP2P()) {
            return AEApi.instance()
                .definitions()
                .parts()
                .p2PTunnelME()
                .maybeStack(1)
                .get();
        } else {
            return mPart.toStack();
        }
    }

    public AEPartData clone() {
        AEPartData dup = new AEPartData();

        dup.mPart = mPart == null ? null : mPart.clone();
        dup.mSettingsName = mSettingsName;
        dup.mSettings = mSettings == null ? null : (NBTTagCompound) mSettings.copy();
        dup.mCustomName = mCustomName;
        dup.mAEUpgrades = mAEUpgrades == null ? null : MMUtils.mapToArray(mAEUpgrades, PortableItemStack[]::new, x -> x == null ? null : x.clone());
        dup.mConfig = mConfig == null ? null : mConfig.clone();
        dup.mAEPatterns = mAEPatterns == null ? null : mAEPatterns.clone();
        dup.mOreDict = mOreDict;
        dup.mP2POutput = mP2POutput;
        dup.mP2PFreq = mP2PFreq;
        dup.priority = priority;

        return dup;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mPart == null) ? 0 : mPart.hashCode());
        result = prime * result + ((mSettingsName == null) ? 0 : mSettingsName.hashCode());
        result = prime * result + ((mSettings == null) ? 0 : mSettings.hashCode());
        result = prime * result + ((mCustomName == null) ? 0 : mCustomName.hashCode());
        result = prime * result + Arrays.hashCode(mAEUpgrades);
        result = prime * result + ((mConfig == null) ? 0 : mConfig.hashCode());
        result = prime * result + ((mAEPatterns == null) ? 0 : mAEPatterns.hashCode());
        result = prime * result + ((mOreDict == null) ? 0 : mOreDict.hashCode());
        result = prime * result + Boolean.hashCode(mP2POutput);
        result = prime * result + Long.hashCode(mP2PFreq);
        result = prime * result + ((mPartClass == null) ? 0 : mPartClass.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        AEPartData other = (AEPartData) obj;
        if (mPart == null) {
            if (other.mPart != null) return false;
        } else if (!mPart.equals(other.mPart)) return false;
        if (mSettingsName == null) {
            if (other.mSettingsName != null) return false;
        } else if (!mSettingsName.equals(other.mSettingsName)) return false;
        if (mSettings == null) {
            if (other.mSettings != null) return false;
        } else if (!mSettings.equals(other.mSettings)) return false;
        if (mCustomName == null) {
            if (other.mCustomName != null) return false;
        } else if (!mCustomName.equals(other.mCustomName)) return false;
        if (!Arrays.equals(mAEUpgrades, other.mAEUpgrades)) return false;
        if (mConfig == null) {
            if (other.mConfig != null) return false;
        } else if (!mConfig.equals(other.mConfig)) return false;
        if (mAEPatterns == null) {
            if (other.mAEPatterns != null) return false;
        } else if (!mAEPatterns.equals(other.mAEPatterns)) return false;
        if (mOreDict == null) {
            if (other.mOreDict != null) return false;
        } else if (!mOreDict.equals(other.mOreDict)) return false;
        if (mP2POutput != other.mP2POutput) return false;
        if (mP2PFreq != other.mP2PFreq) return false;
        if (mPartClass == null) {
            if (other.mPartClass != null) return false;
        } else if (!mPartClass.equals(other.mPartClass)) return false;
        return true;
    }
}
