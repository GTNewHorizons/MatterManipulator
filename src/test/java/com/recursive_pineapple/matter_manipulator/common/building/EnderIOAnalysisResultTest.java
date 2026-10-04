package com.recursive_pineapple.matter_manipulator.common.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.EnderIOAnalysisResult.ConduitData;
import com.recursive_pineapple.matter_manipulator.common.building.EnderIOAnalysisResult.SideData;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;
import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import crazypants.enderio.conduit.item.ItemExtractSpeedUpgrade;
import crazypants.enderio.conduit.IConduit;
import crazypants.enderio.conduit.me.IMEConduit;
import com.recursive_pineapple.matter_manipulator.mixin.interfaces.ConduitExt;

class EnderIOAnalysisResultTest {

    @BeforeAll
    static void bootstrap() throws Exception {
        // Only Items.feather is needed by BigItemStack; vanilla bootstrap requires LaunchWrapper.
        Method add = FMLControlledNamespacedRegistry.class
            .getDeclaredMethod("add", int.class, String.class, Object.class, BitSet.class);
        add.setAccessible(true);
        add.invoke(Item.itemRegistry, 288, "minecraft:feather", new Item(), new BitSet());
    }


    @Test
    void freshlyPastedMEConduitDoesNotRequireAnInitializedGridNode() {
        List<String> calls = new ArrayList<>();
        Item item = (Item) Item.itemRegistry.getObject("minecraft:feather");
        IConduit conduit = (IConduit) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] { IMEConduit.class, ConduitExt.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createItem": return new ItemStack(item);
                    case "getDrops": return Arrays.asList(new ItemStack(item));
                    case "getConnectionMode": return null;
                    case "mm$getSettings":
                        NBTTagCompound settings = new NBTTagCompound();
                        settings.setBoolean("previousSettings", true);
                        return settings;
                    case "onRemovedFromBundle":
                        throw new NullPointerException("ME grid node has not been created yet");
                    case "onAddedToBundle":
                        fail("Refreshing ME settings must not reset neighbouring connection modes");
                        return null;
                    case "mm$setSettings", "setConnectionMode", "connectionsChanged":
                        calls.add(method.getName());
                        return null;
                    default:
                        throw new AssertionError("Unexpected conduit call: " + method.getName());
                }
            }
        );
        ConduitData data = data();
        data.item = stack(item, 1);
        Context ctx = new Context(true);
        // The conduit has just been attached to its bundle, but has not received its first tile tick.
        assertTrue(data.apply(ctx, null, conduit, false));
        assertEquals(6, calls.stream().filter("mm$setSettings"::equals).count());
        assertEquals("connectionsChanged", calls.get(calls.size() - 1));
        assertTrue(ctx.returned.isEmpty());
    }

    @Test
    void installedItemsAreReusedOnlyOnceAndMustMatchTier() {
        Item item = new Item().setHasSubtypes(true);
        List<ItemStack> installed = new ArrayList<>(Arrays.asList(new ItemStack(item, 2, 1), new ItemStack(item, 3, 2)));
        assertEquals(1, EnderIOAnalysisResult.take(installed, new ItemStack(item, 3, 1)));
        assertEquals(1, EnderIOAnalysisResult.take(installed, new ItemStack(item, 1, 1)));
        assertEquals(0, EnderIOAnalysisResult.take(installed, new ItemStack(item, 3, 2)));
    }

    @Test
    void requiredUpgradesAreRequestedTogetherWithTheConduit() {
        ConduitData data = data();
        data.sides[0].upgrades = new PortableItemStack[] { stack(new Item(), 1) };
        data.sides[1].upgrades = new PortableItemStack[] { stack(new Item(), 1) };
        Context ctx = new Context(false);
        assertFalse(data.apply(ctx, null, null, false));
        assertEquals(1, ctx.requests.size());
        assertEquals(3, ctx.requests.get(0).size());
        assertEquals(1, ctx.warnings);
        assertTrue(ctx.returned.isEmpty());
    }

    @Test
    void materialPreviewDoesNotNeedOrModifyAWorld() {
        Context ctx = new Context(true);
        assertTrue(data().apply(ctx, null, null, true));
        assertEquals(1, ctx.requests.size());
        assertEquals(0, ctx.warnings);
    }

    @Test
    void missingSpeedUpgradesDoNotRejectTheConduit() {
        ConduitData data = data();
        data.sides[0].upgrades = new PortableItemStack[] {
            stack(new ItemExtractSpeedUpgrade() {}, 4)
        };
        Context ctx = new Context(true) {
            @Override
            public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
                if (flags == IPseudoInventory.CONSUME_PARTIAL) {
                    requests.add(items);
                    return BooleanObjectImmutablePair.of(true, new ArrayList<>());
                }
                return super.tryConsumeItems(items, flags);
            }
        };
        assertTrue(data.apply(ctx, null, null, true));
        assertEquals(2, ctx.requests.size());
        assertEquals(1, ctx.requests.get(0).size());
        assertEquals(4, ctx.requests.get(1).get(0).stackSize);
        assertEquals(1, ctx.warnings);
    }

    @Test
    void transformsMoveSettingsAndUpgradesTogetherAndCloneIsIndependent() {
        EnderIOAnalysisResult analysis = new EnderIOAnalysisResult();
        ConduitData data = data();
        analysis.conduits.add(data);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            data.sides[side.ordinal()].settings.setInteger("side", side.ordinal());
        }
        EnderIOAnalysisResult copy = analysis.clone();
        Transform transform = new Transform();
        transform.flipX = true;
        copy.transform(transform);
        assertEquals(ForgeDirection.EAST.ordinal(), copy.conduits.get(0).sides[ForgeDirection.WEST.ordinal()].settings.getInteger("side"));
        copy.conduits.get(0).sides[0].settings.setInteger("side", 99);
        assertEquals(0, data.sides[0].settings.getInteger("side"));
        for (int i = 0; i < 4; i++) {
            Transform rotation = new Transform();
            rotation.rotate(ForgeDirection.UP, 1);
            analysis.transform(rotation);
        }
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            assertEquals(side.ordinal(), analysis.conduits.get(0).sides[side.ordinal()].settings.getInteger("side"));
        }
    }

    private static ConduitData data() {
        ConduitData data = new ConduitData();
        data.item = stack(new Item(), 1);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            SideData settings = new SideData();
            settings.settings = new NBTTagCompound();
            data.sides[side.ordinal()] = settings;
        }
        return data;
    }

    private static PortableItemStack stack(Item stackItem, int count) {
        return new PortableItemStack() {
            @Override
            public Item getItem() { return stackItem; }
            @Override
            public ItemStack toStack() { return new ItemStack(stackItem, count); }
            @Override
            public PortableItemStack clone() { return stack(stackItem, count); }
        };
    }

    private static class Context implements IBlockApplyContext {
        final boolean available;
        final List<List<BigItemStack>> requests = new ArrayList<>();
        final List<BigItemStack> returned = new ArrayList<>();
        int warnings;

        Context(boolean available) { this.available = available; }
        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            assertEquals(IPseudoInventory.CONSUME_FUZZY, flags);
            requests.add(items);
            return BooleanObjectImmutablePair.of(available, available ? items : null);
        }
        @Override
        public void givePlayerItems(List<BigItemStack> items) { returned.addAll(items); }
        @Override
        public void givePlayerFluids(List<BigFluidStack> fluids) { fail("Unexpected fluid refund"); }
        @Override
        public World getWorld() { throw new AssertionError("Preview accessed world"); }
        @Override
        public TileEntity getTileEntity() { throw new AssertionError("Preview accessed tile"); }
        @Override
        public EntityPlayer getRealPlayer() { throw new AssertionError("Preview accessed player"); }
        @Override
        public int getX() { return 0; }
        @Override
        public int getY() { return 0; }
        @Override
        public int getZ() { return 0; }
        @Override
        public boolean tryApplyAction(double complexity) { return true; }
        @Override
        public void warn(IChatComponent message) { warnings++; }
        @Override
        public void error(IChatComponent message) { fail("Unexpected error"); }
    }
}
