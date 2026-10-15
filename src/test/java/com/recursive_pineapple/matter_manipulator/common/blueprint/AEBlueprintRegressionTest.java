package com.recursive_pineapple.matter_manipulator.common.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.RegionAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import org.joml.Vector3i;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real AE analysis and Forge-registered AE-style IDs, without full AE mod or world startup. */
class AEBlueprintRegressionTest {

    private static LaunchClassLoader gameLoader;
    private static LaunchClassLoader previousLoader;
    private static Class<?> fixture;

    @BeforeAll
    static void gameRegistries() throws Exception {
        // AE2 is compileOnly: ordinary test runs skip these cases without adding dependencies.
        assumeTrue(AEBlueprintRegressionTest.class.getClassLoader().getResource("appeng/api/util/AEColor.class") != null,
            "Run with the existing main compileClasspath added to the test runtime to exercise AE2");
        String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < urls.length; i++) urls[i] = new File(classpath[i]).toURI().toURL();
        gameLoader = new LaunchClassLoader(urls);
        gameLoader.addClassLoaderExclusion("org.junit.");
        gameLoader.addClassLoaderExclusion("org.opentest4j.");
        previousLoader = Launch.classLoader;
        Launch.classLoader = gameLoader;
        // Loader must exist before the optional-member transformer resolves any mod checks.
        Class.forName(AEBlueprintRegressionTest.class.getName() + "$BootstrapFixture", true, gameLoader)
            .getMethod("bootstrap").invoke(null);
        gameLoader.registerTransformer("com.recursive_pineapple.matter_manipulator.asm.DeMemberator");
        fixture = Class.forName(AEBlueprintRegressionTest.class.getName() + "$Fixture", true, gameLoader);
    }

    @AfterAll
    static void closeLoader() throws Exception {
        if (gameLoader != null) {
            Launch.classLoader = previousLoader;
            gameLoader.close();
        }
    }

    private static void run(String name, Path directory) throws Exception {
        try {
            fixture.getMethod(name, Path.class).invoke(null, directory);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) throw error;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    @Test
    void quantumRingCapturesStoresAndReloadsWithNullAndEmptyConfig(@TempDir Path directory) throws Exception {
        run("quantumRingCapturesStoresAndReloadsWithNullAndEmptyConfig", directory);
    }

    @Test
    void cablePartsCaptureStoreAndReloadWithRegisteredMultipartItem(@TempDir Path directory) throws Exception {
        run("cablePartsCaptureStoreAndReloadWithRegisteredMultipartItem", directory);
    }

    public static class BootstrapFixture {

        public static Block quantumRing;
        public static Item multiPart;

        public static void bootstrap() throws Exception {
            Loader.injectData("7", "99", "0", "0", "1.7.10", "9.05", new File("."), Collections.emptyList());
            ModMetadata metadata = new ModMetadata();
            metadata.modId = "appliedenergistics2";
            metadata.name = "Applied Energistics 2";
            metadata.version = "rv3-beta-999-GTNH";
            ModContainer ae = new DummyModContainer(metadata);
            Map<String, ModContainer> namedMods = new HashMap<>();
            namedMods.put(metadata.modId, ae);
            java.lang.reflect.Field mods = Loader.class.getDeclaredField("namedMods");
            mods.setAccessible(true);
            mods.set(Loader.instance(), namedMods);
            LoadController loadController = new LoadController(Loader.instance());
            java.lang.reflect.Field controller = Loader.class.getDeclaredField("modController");
            controller.setAccessible(true);
            controller.set(Loader.instance(), loadController);
            Bootstrap.func_151354_b();

            // Forge ignores the deprecated explicit modId argument; registry ownership comes from the active mod.
            java.lang.reflect.Field active = LoadController.class.getDeclaredField("activeContainer");
            active.setAccessible(true);
            active.set(loadController, ae);
            try {
                quantumRing = new Block(Material.iron) {};
                multiPart = new Item();
                GameRegistry.registerBlock(quantumRing, "tile.BlockQuantumRing");
                GameRegistry.registerItem(multiPart, "item.ItemMultiPart");
            } finally {
                active.set(loadController, null);
            }
            assertIdentifier(GameRegistry.findUniqueIdentifierFor(quantumRing), "tile.BlockQuantumRing");
            assertIdentifier(GameRegistry.findUniqueIdentifierFor(multiPart), "item.ItemMultiPart");
            assertSame(quantumRing, GameRegistry.findBlock("appliedenergistics2", "tile.BlockQuantumRing"));
            assertSame(multiPart, GameRegistry.findItem("appliedenergistics2", "item.ItemMultiPart"));
            assertTrue(Loader.isModLoaded("appliedenergistics2"));
            System.out.println("Gson loaded from: " + com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation());
            System.out.println("AE loaded from: " + Class.forName("appeng.api.util.AEColor").getProtectionDomain().getCodeSource().getLocation());
        }

        private static void assertIdentifier(UniqueIdentifier id, String name) {
            assertEquals("appliedenergistics2", id.modId);
            assertEquals(name, id.name);
            assertEquals("appliedenergistics2:" + name, id.toString());
            System.out.println("Registered UniqueIdentifier: " + id + " (modId=" + id.modId + ", name=" + id.name + ")");
        }
    }

    public static class Fixture {

        private static StructureBlueprint captureStoreAndReload(Path directory, BlockSpec spec, AEAnalysisResult ae) throws Exception {
            RegionAnalysis region = new RegionAnalysis();
            region.deltas = new Vector3i();
            region.blocks = new ArrayList<>();
            PendingBlock source = new PendingBlock(7, 0, 0, 0, spec);
            source.ae = ae;
            region.blocks.add(source);
            StructureBlueprint captured = StructureBlueprint.capture(region, "Registered AE regression");
            assertNotSame(source, captured.blocks.get(0));
            String encoded = BlueprintCodec.encode(captured);
            System.out.println("Captured AE JSON: " + encoded);
            JsonObject block = new JsonParser().parse(encoded).getAsJsonObject().getAsJsonArray("blocks").get(0).getAsJsonObject();
            assertEquals(spec.getObjectId().toString(), block.getAsJsonObject("spec").get("id").getAsString());
            assertEquals("ae", block.getAsJsonObject("ae").get("type").getAsString());
            UUID owner = UUID.randomUUID();
            try {
                BlueprintStore.save(directory, owner, captured);
                assertEquals(1, BlueprintStore.list(directory, owner).size());
                BlueprintStore.clearCache();
                StructureBlueprint loaded = BlueprintStore.load(directory, owner, captured.id);
                assertEquals(1, loaded.blockCount);
                assertEquals(0, loaded.blocks.get(0).worldId);
                assertEquals(spec.getObjectId().toString(), loaded.blocks.get(0).spec.getObjectId().toString());
                assertTrue(loaded.blocks.get(0).ae instanceof AEAnalysisResult);
                assertEquals(encoded, BlueprintCodec.encode(loaded));
                return loaded;
            } finally {
                BlueprintStore.clearCache();
            }
        }

        public static void quantumRingCapturesStoresAndReloadsWithNullAndEmptyConfig(Path directory) throws Exception {
            TileEntity tile = (TileEntity) Class.forName("appeng.tile.qnb.TileQuantumBridge").getConstructor().newInstance();
            Class<?> baseTile = Class.forName("appeng.tile.AEBaseTile");
            // setOrientation notifies a live world; seed the actual tile's orientation without world startup.
            java.lang.reflect.Field up = baseTile.getDeclaredField("up");
            up.setAccessible(true);
            up.set(tile, ForgeDirection.UP);
            java.lang.reflect.Field forward = baseTile.getDeclaredField("forward");
            forward.setAccessible(true);
            forward.set(tile, ForgeDirection.NORTH);
            AEAnalysisResult ae = AEAnalysisResult.analyze(tile);
            assertTrue(ae != null);
            assertNull(ae.mAEConfig);
            for (JsonElement config : new JsonElement[] { null, MMUtils.toJsonObject(new NBTTagCompound()) }) {
                ae.mAEConfig = config;
                StructureBlueprint loaded = captureStoreAndReload(directory, new BlockSpec().setObject(BootstrapFixture.quantumRing, 0), ae);
                AEAnalysisResult copy = (AEAnalysisResult) loaded.blocks.get(0).ae;
                assertEquals(config, copy.mAEConfig);
                assertEquals(ForgeDirection.NORTH, copy.mAEForward);
                assertSame(BootstrapFixture.quantumRing, loaded.blocks.get(0).spec.getBlock());
            }
        }

        public static void cablePartsCaptureStoreAndReloadWithRegisteredMultipartItem(Path directory) throws Exception {
            AEAnalysisResult ae = new AEAnalysisResult();
            ae.mAEConfig = MMUtils.toJsonObject(new NBTTagCompound());
            // Reflection keeps the ordinary test compile classpath free of optional AE types.
            Object transparent = Class.forName("appeng.api.util.AEColor").getField("Transparent").get(null);
            java.lang.reflect.Field colour = AEAnalysisResult.class.getField("mAEColour");
            colour.set(ae, transparent);
            ae.mAEParts = new AEPartData[ForgeDirection.values().length];
            AEPartData cable = new AEPartData();
            cable.mPart = new PortableItemStack(BootstrapFixture.multiPart, 3);
            cable.mPartSettings = new NBTTagCompound();
            cable.mPartSettings.setString("customName", "");
            cable.mPartSettings.setByteArray("emptyBytes", new byte[0]);
            cable.mPartSettings.setIntArray("emptyInts", new int[0]);
            ae.mAEParts[ForgeDirection.UNKNOWN.ordinal()] = cable;
            StructureBlueprint loaded = captureStoreAndReload(directory, new BlockSpec().setObject(BootstrapFixture.multiPart, 3), ae);
            AEAnalysisResult copy = (AEAnalysisResult) loaded.blocks.get(0).ae;
            assertEquals(transparent, colour.get(copy));
            assertEquals(7, copy.mAEParts.length);
            assertNull(copy.mAEParts[ForgeDirection.NORTH.ordinal()]);
            AEPartData part = copy.mAEParts[ForgeDirection.UNKNOWN.ordinal()];
            assertEquals(cable.mPart, part.mPart);
            assertSame(BootstrapFixture.multiPart, part.mPart.getItem());
            assertEquals(cable.mPartSettings, part.mPartSettings);
            assertNotSame(cable, part);
        }
    }
}
