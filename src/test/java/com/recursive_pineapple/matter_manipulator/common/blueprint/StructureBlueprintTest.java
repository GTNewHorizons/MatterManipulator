package com.recursive_pineapple.matter_manipulator.common.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.Loader;

import com.gtnewhorizon.gtnhlib.util.data.ItemMeta;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.RegionAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.InventoryAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.building.SmartCopyIntegration;
import com.recursive_pineapple.matter_manipulator.common.building.providers.BatteryItemProvider;
import com.recursive_pineapple.matter_manipulator.common.building.providers.IItemProvider;

import org.joml.Vector3i;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureBlueprintTest {

    private static LaunchClassLoader gameLoader;
    private static Class<?> fixture;
    private static LaunchClassLoader previousLoader;

    @BeforeAll
    static void vanillaRegistries() throws Exception {
        // Forge's registry bootstrap requires its classes to be defined by LaunchClassLoader, not JUnit's app loader.
        String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < urls.length; i++) urls[i] = new File(classpath[i]).toURI().toURL();
        gameLoader = new LaunchClassLoader(urls);
        gameLoader.addClassLoaderExclusion("org.junit.");
        gameLoader.addClassLoaderExclusion("org.opentest4j.");
        previousLoader = Launch.classLoader;
        Launch.classLoader = gameLoader;
        fixture = Class.forName(StructureBlueprintTest.class.getName() + "$Fixture", true, gameLoader);
        run("bootstrap");
    }

    @AfterAll
    static void closeLoader() throws Exception {
        Launch.classLoader = previousLoader;
        if (gameLoader != null) gameLoader.close();
    }

    private static void run(String name) throws Exception {
        try {
            fixture.getMethod(name).invoke(null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) throw error;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    @Test
    void deepCopiesBlocksSpecsInventoryAndNbtAndRebindsTargetWorld() throws Exception {
        run("deepCopiesBlocksSpecsInventoryAndNbtAndRebindsTargetWorld");
    }

    @Test
    void roundTripsInterfaceProvidersAndRegistryNames() throws Exception {
        run("roundTripsInterfaceProvidersAndRegistryNames");
    }

    @Test
    void rejectsMissingRegistryEntriesAndUnknownProviders() throws Exception {
        run("rejectsMissingRegistryEntriesAndUnknownProviders");
    }

    @Test
    void rejectsSmartCopyTransformedRegionsAndInvalidPositions() throws Exception {
        run("rejectsSmartCopyTransformedRegionsAndInvalidPositions");
    }

    @Test
    void roundTripsBatteryProviderWithoutSerializingRuntimeItemReferences() throws Exception {
        run("roundTripsBatteryProviderWithoutSerializingRuntimeItemReferences");
    }

    @Test
    void rejectsUnavailableModsWithoutLoadingTheirIntegrations() throws Exception {
        run("rejectsUnavailableModsWithoutLoadingTheirIntegrations");
    }

    @Test
    void compactFormatDeduplicatesBlocksAndKeepsRegionOrder() throws Exception {
        run("compactFormatDeduplicatesBlocksAndKeepsRegionOrder");
    }

    @Test
    void regionsBeyondTheOldLimitsStayCompact() throws Exception {
        run("regionsBeyondTheOldLimitsStayCompact");
    }

    @Test
    void readsVersionOneBlueprints() throws Exception {
        run("readsVersionOneBlueprints");
    }

    @Test
    void rejectsCorruptVoxelData() throws Exception {
        run("rejectsCorruptVoxelData");
    }

    @Test
    void captureRoundTripsEmptyNbtValues() throws Exception {
        run("captureRoundTripsEmptyNbtValues");
    }

    @Test
    void capturePreservesArbitraryItemConfiguration() throws Exception {
        run("capturePreservesArbitraryItemConfiguration");
    }

    @Test
    void storeMetadataRetainsActualCountAcrossCopiesAndReloads(@TempDir Path directory) throws Exception {
        fixture.getMethod("storeMetadataRetainsActualCountAcrossCopiesAndReloads", Path.class).invoke(null, directory);
    }

    public static class Fixture {

        public static void bootstrap() throws Exception {
            Loader.injectData("7", "99", "0", "0", "1.7.10", "9.05", new File("."), Collections.emptyList());
            java.lang.reflect.Field mods = Loader.class.getDeclaredField("namedMods");
            mods.setAccessible(true);
            mods.set(Loader.instance(), new HashMap<>());
            Bootstrap.func_151354_b();
        }

        private static StructureBlueprint snapshot() {
            StructureBlueprint blueprint = new StructureBlueprint();
            blueprint.id = UUID.randomUUID().toString();
            blueprint.name = "Snapshot";
            blueprint.deltas = new Vector3i(-2, 1, 0);
            blueprint.blocks = new ArrayList<>();
            PendingBlock block = new PendingBlock(7, -1, 0, 0, new BlockSpec());
            block.renderOrder = 12;
            block.buildOrder = 100;
            block.inventory = new InventoryAnalysis();
            PortableItemStack item = new PortableItemStack(Items.stick, 0);
            item.nbt = new NBTTagCompound();
            item.nbt.setInteger("nested", 42);
            block.inventory.mItems = new IItemProvider[] { item, null };
            blueprint.blocks.add(block);
            return blueprint;
        }

        public static void compactFormatDeduplicatesBlocksAndKeepsRegionOrder() {
            StructureBlueprint blueprint = snapshot();
            blueprint.deltas = new Vector3i(2, 1, -1);
            blueprint.blocks.get(0).x = 1;
            blueprint.blocks.get(0).y = 1;
            blueprint.blocks.get(0).z = -1;
            // Visit cells like BlockAnalyzer.analyzeRegion and leave two of them empty (e.g. skipped liquids).
            List<PendingBlock> expected = new ArrayList<>();
            for (int y = 0; y <= 1; y++) {
                for (int z = -1; z <= 0; z++) {
                    for (int x = 0; x <= 2; x++) {
                        if ((x == 2 && z == 0 && y == 0) || (x == 0 && z == -1 && y == 1)) continue;
                        if (x == 1 && y == 1 && z == -1) {
                            expected.add(blueprint.blocks.get(0));
                        } else {
                            expected.add(new PendingBlock(3, x, y, z, new BlockSpec()));
                        }
                    }
                }
            }
            blueprint.blocks = expected;
            String encoded = BlueprintCodec.encode(blueprint);
            com.google.gson.JsonObject tree = new com.google.gson.JsonParser().parse(encoded).getAsJsonObject();
            assertEquals(2, tree.getAsJsonArray("palette").size());
            assertTrue(!tree.getAsJsonArray("palette").get(0).getAsJsonObject().has("x"));
            StructureBlueprint decoded = BlueprintCodec.decode(encoded);
            assertEquals(expected.size(), decoded.blocks.size());
            assertEquals(expected.size(), decoded.blockCount);
            for (int i = 0; i < expected.size(); i++) {
                PendingBlock a = expected.get(i), b = decoded.blocks.get(i);
                assertEquals(a.x + "," + a.y + "," + a.z, b.x + "," + b.y + "," + b.z);
                assertEquals(0, b.worldId);
                assertEquals(a.inventory != null, b.inventory != null);
            }
            assertEquals(encoded, BlueprintCodec.encode(decoded));
        }

        public static void regionsBeyondTheOldLimitsStayCompact() {
            StructureBlueprint blueprint = snapshot();
            blueprint.deltas = new Vector3i(127, 15, -127);
            blueprint.blocks.clear();
            for (int y = 0; y <= 15; y++) {
                for (int z = -127; z <= 0; z++) {
                    for (int x = 0; x <= 127; x++) blueprint.blocks.add(new PendingBlock(0, x, y, z, new BlockSpec()));
                }
            }
            String encoded = BlueprintCodec.encode(blueprint);
            assertTrue(encoded.length() < 1024, "encoded length " + encoded.length());
            StructureBlueprint decoded = BlueprintCodec.decode(encoded);
            assertEquals(128 * 16 * 128, decoded.blocks.size());
            assertEquals(128 * 16 * 128, decoded.instantiate(0).blocks.size());
        }

        public static void readsVersionOneBlueprints() {
            String id = UUID.randomUUID().toString();
            String legacy = "{\"id\":\"" + id + "\",\"name\":\"Legacy\",\"deltas\":{\"x\":1,\"y\":0,\"z\":0},"
                + "\"blocks\":[{\"spec\":{\"b\":true,\"id\":\"minecraft:air\",\"m\":0},\"renderOrder\":0,\"buildOrder\":7,"
                + "\"x\":1,\"y\":0,\"z\":0,\"worldId\":5}],\"formatVersion\":1}";
            StructureBlueprint decoded = BlueprintCodec.decode(legacy);
            assertEquals(id, decoded.id);
            assertEquals(BlueprintCodec.FORMAT_VERSION, decoded.formatVersion);
            assertEquals(1, decoded.blockCount);
            assertEquals(1, decoded.blocks.get(0).x);
            assertEquals(7, decoded.blocks.get(0).buildOrder);
            assertEquals(0, decoded.blocks.get(0).worldId);
            assertTrue(BlueprintCodec.encode(decoded).contains("\"palette\""));
        }

        public static void rejectsCorruptVoxelData() {
            String encoded = BlueprintCodec.encode(snapshot());
            com.google.gson.JsonObject tree = new com.google.gson.JsonParser().parse(encoded).getAsJsonObject();
            // Region is 3x2x1 = 6 cells, with one palette entry.
            for (byte[] cells : new byte[][] {
                {}, // covers nothing
                { 1, 5 }, // too short
                { 1, 7 }, // too long
                { 2, 6 }, // unknown palette entry
                { 1, 0, 1, 6 }, // empty run
                { 1, (byte) 0x86 }, // truncated varint
                { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f, 6 }, // varint overflow
            }) {
                tree.addProperty("voxels", java.util.Base64.getEncoder().encodeToString(cells));
                assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(tree.toString()));
            }
            tree.addProperty("voxels", "!not base64!");
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(tree.toString()));
        }

        public static void captureRoundTripsEmptyNbtValues() {
            StructureBlueprint source = snapshot();
            PortableItemStack original = (PortableItemStack) source.blocks.get(0).inventory.mItems[0];
            original.nbt.setString("customName", "");
            original.nbt.setByteArray("bytes", new byte[0]);
            original.nbt.setIntArray("ints", new int[0]);
            RegionAnalysis region = new RegionAnalysis();
            region.deltas = source.deltas;
            region.blocks = source.blocks;
            StructureBlueprint captured = StructureBlueprint.capture(region, "Empty NBT regression");
            StructureBlueprint decoded = BlueprintCodec.decode(BlueprintCodec.encode(captured));
            PortableItemStack copy = (PortableItemStack) decoded.blocks.get(0).inventory.mItems[0];
            assertEquals(original.nbt, copy.nbt);
            assertNotSame(original.nbt, copy.nbt);
            assertEquals(original.nbt, ((PortableItemStack) captured.instantiate(42).blocks.get(0).inventory.mItems[0]).nbt);
        }

        public static void deepCopiesBlocksSpecsInventoryAndNbtAndRebindsTargetWorld() {
            StructureBlueprint blueprint = snapshot();
            RegionAnalysis source = new RegionAnalysis();
            source.deltas = blueprint.deltas;
            source.blocks = blueprint.blocks;
            StructureBlueprint captured = StructureBlueprint.capture(source, "Captured");
            RegionAnalysis first = captured.instantiate(-1);
            RegionAnalysis second = captured.instantiate(42);
            PendingBlock block = first.blocks.get(0);
            PendingBlock other = second.blocks.get(0);
            assertEquals(-1, block.worldId);
            assertEquals(42, other.worldId);
            assertEquals(7, source.blocks.get(0).worldId);
            assertEquals(1, captured.blockCount);
            assertEquals(-1, block.x);
            assertEquals(12, block.renderOrder);
            assertEquals(100, block.buildOrder);
            assertNotSame(source.blocks.get(0), captured.blocks.get(0));
            assertNotSame(block, other);
            // Block specs are immutable and shared by clones, like PendingBlock.clone does.
            assertEquals(block.spec, other.spec);
            assertNotSame(block.inventory.mItems, other.inventory.mItems);
            PortableItemStack item = (PortableItemStack) block.inventory.mItems[0];
            PortableItemStack otherItem = (PortableItemStack) other.inventory.mItems[0];
            item.nbt.setInteger("nested", 99);
            assertEquals(42, otherItem.nbt.getInteger("nested"));
            block.x = 0;
            first.blocks.clear();
            assertEquals(1, captured.blocks.size());
            assertEquals(-1, other.x);
        }

        public static void roundTripsInterfaceProvidersAndRegistryNames() {
            String encoded = BlueprintCodec.encode(snapshot());
            assertTrue(encoded.contains("minecraft:air"));
            assertTrue(encoded.contains("minecraft:stick"));
            assertTrue(encoded.contains("\"type\":\"stack\""));
            StructureBlueprint decoded = BlueprintCodec.decode(encoded);
            assertTrue(decoded.blocks.get(0).spec instanceof BlockSpec);
            assertTrue(decoded.blocks.get(0).inventory.mItems[0] instanceof PortableItemStack);
            assertEquals(42, ((PortableItemStack) decoded.blocks.get(0).inventory.mItems[0]).nbt.getInteger("nested"));
            assertEquals(0, decoded.blocks.get(0).worldId);
            assertEquals(1, decoded.blockCount);
            com.google.gson.JsonObject tree = new com.google.gson.JsonParser().parse(encoded).getAsJsonObject();
            tree.addProperty("blockCount", 999);
            assertEquals(1, BlueprintCodec.decode(tree.toString()).blockCount);
            tree.remove("blockCount");
            assertEquals(1, BlueprintCodec.decode(tree.toString()).blockCount);
        }

        public static void rejectsMissingRegistryEntriesAndUnknownProviders() {
            String encoded = BlueprintCodec.encode(snapshot());
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(encoded.replace("minecraft:air", "missing_mod:unknown_block")));
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(encoded.replace("minecraft:stick", "missing_mod:unknown_item")));
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(encoded.replace("\"type\":\"stack\"", "\"type\":\"java.lang.Runtime\"")));
        }

        public static void rejectsSmartCopyTransformedRegionsAndInvalidPositions() {
            StructureBlueprint blueprint = snapshot();
            blueprint.blocks.get(0).smartCopy = new SmartCopyIntegration();
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(blueprint));
            RegionAnalysis analysis = new RegionAnalysis();
            analysis.deltas = blueprint.deltas;
            analysis.blocks = blueprint.blocks;
            assertThrows(IllegalArgumentException.class, () -> StructureBlueprint.capture(analysis, "Too late"));
            blueprint.blocks.get(0).smartCopy = null;
            blueprint.blocks.get(0).x = 1;
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(blueprint));
            blueprint.blocks.get(0).x = -1;
            blueprint.blocks.add(blueprint.blocks.get(0));
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(blueprint));
        }

        public static void roundTripsBatteryProviderWithoutSerializingRuntimeItemReferences() {
            StructureBlueprint blueprint = snapshot();
            BatteryItemProvider battery = new BatteryItemProvider();
            battery.battery = new ItemMeta(Items.stick, 3);
            blueprint.blocks.get(0).inventory.mItems[0] = battery;
            String encoded = BlueprintCodec.encode(blueprint);
            assertTrue(encoded.contains("\"type\":\"battery\""));
            StructureBlueprint decoded = BlueprintCodec.decode(encoded);
            BatteryItemProvider copy = (BatteryItemProvider) decoded.blocks.get(0).inventory.mItems[0];
            assertEquals(Items.stick, copy.battery.getItem());
            assertEquals(3, copy.battery.getItemMeta());
        }

        public static void capturePreservesArbitraryItemConfiguration() {
            StructureBlueprint blueprint = snapshot();
            PortableItemStack original = (PortableItemStack) blueprint.blocks.get(0).inventory.mItems[0];
            original.nbt.setString("link", "frequency and link are user configuration");
            original.nbt.setLong("frequency", 123);
            original.nbt.setLong("securityKey", 456);
            original.nbt.setTag("connectedTargets", new NBTTagCompound());
            NBTTagCompound nested = new NBTTagCompound();
            nested.setString("owner", "player");
            original.nbt.setTag("oc:node", nested);
            RegionAnalysis analysis = new RegionAnalysis();
            analysis.deltas = blueprint.deltas;
            analysis.blocks = blueprint.blocks;
            StructureBlueprint captured = StructureBlueprint.capture(analysis, "Preserved configuration");
            PortableItemStack copy = (PortableItemStack) captured.blocks.get(0).inventory.mItems[0];
            assertEquals(original.nbt, copy.nbt);
            assertNotSame(original.nbt, copy.nbt);
        }

        public static void storeMetadataRetainsActualCountAcrossCopiesAndReloads(Path directory) throws Exception {
            StructureBlueprint blueprint = snapshot();
            blueprint.blockCount = 999;
            UUID owner = UUID.randomUUID();
            try {
                BlueprintStore.save(directory, owner, blueprint);
                StructureBlueprint metadata = BlueprintStore.list(directory, owner).get(0);
                assertTrue(metadata.blocks.isEmpty());
                assertEquals(1, metadata.blockCount);
                metadata.blockCount = 77;
                assertEquals(1, BlueprintStore.list(directory, owner).get(0).blockCount);
                assertEquals(1, BlueprintStore.load(directory, owner, blueprint.id).blockCount);
                BlueprintStore.clearCache();
                assertEquals(1, BlueprintStore.list(directory, owner).get(0).blockCount);
                assertEquals(1, BlueprintStore.list(directory, owner).get(0).blockCount);
                BlueprintStore.rename(directory, owner, blueprint.id, "Count survives rename");
                assertEquals(1, BlueprintStore.list(directory, owner).get(0).blockCount);
            } finally {
                BlueprintStore.clearCache();
            }
        }

        public static void rejectsUnavailableModsWithoutLoadingTheirIntegrations() {
            String encoded = BlueprintCodec.encode(snapshot());
            for (String tag : new String[] { "gt", "ae", "arch", "mp", "cb" }) {
                com.google.gson.JsonObject tree = new com.google.gson.JsonParser().parse(encoded).getAsJsonObject();
                com.google.gson.JsonObject integration = new com.google.gson.JsonObject();
                integration.addProperty("type", tag);
                integration.add("data", new com.google.gson.JsonObject());
                tree.getAsJsonArray("palette").get(0).getAsJsonObject().add(tag, integration);
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(tree.toString()));
                assertTrue(error.getMessage().contains("requires mod"));
            }
            StructureBlueprint invalid = snapshot();
            invalid.blocks.get(0).gt = new SmartCopyIntegration();
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(invalid));
        }
    }
}
