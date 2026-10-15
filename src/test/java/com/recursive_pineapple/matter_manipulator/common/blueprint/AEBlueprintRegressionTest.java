package com.recursive_pineapple.matter_manipulator.common.blueprint;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.RegionAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.InventoryAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.providers.IItemProvider;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

import org.joml.Vector3i;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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

    @Test
    void quantumBlueprintsOmitSingularitiesAndNamesWithoutMutatingSource(@TempDir Path directory) throws Exception {
        run("quantumBlueprintsOmitSingularitiesAndNamesWithoutMutatingSource", directory);
    }

    @Test
    void legacyQuantumBlueprintsDiscardSingularitiesOnDiskAndCachedLoads(@TempDir Path directory) throws Exception {
        run("legacyQuantumBlueprintsDiscardSingularitiesOnDiskAndCachedLoads", directory);
    }

    @Test
    void liveQuantumAnalysisDoesNotCaptureInventory() throws Exception {
        run("liveQuantumAnalysisDoesNotCaptureInventory", null);
    }

    @Test
    void existingQuantumBridgeIgnoresStaleInventoryPlan() throws Exception {
        run("existingQuantumBridgeIgnoresStaleInventoryPlan", null);
    }

    @Test
    void quantumApplyIgnoresStaleCustomNameAndMemoryCardSettings() throws Exception {
        run("quantumApplyIgnoresStaleCustomNameAndMemoryCardSettings", null);
    }

    @Test
    void quantumInventoryApplyPreservesContentsForAllFlags() throws Exception {
        run("quantumInventoryApplyPreservesContentsForAllFlags", null);
    }

    public static class BootstrapFixture {

        public static Block quantumRing, quantumChamber;
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
                quantumChamber = new Block(Material.iron) {};
                multiPart = new Item();
                GameRegistry.registerBlock(quantumRing, "tile.BlockQuantumRing");
                GameRegistry.registerBlock(quantumChamber, "tile.BlockQuantumLinkChamber");
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

        private static TileEntity newQuantumBridge() throws Exception {
            return (TileEntity) Class.forName("appeng.tile.qnb.TileQuantumBridge").getConstructor().newInstance();
        }

        private static PendingBlock quantumPendingBlock() {
            return new PendingBlock(7, 0, 0, 0, new BlockSpec().setObject(BootstrapFixture.quantumChamber, 0));
        }

        private static IBlockApplyContext quantumContext(TileEntity tile) {
            MMConfig config = new MMConfig();
            return (IBlockApplyContext) Proxy.newProxyInstance(
                Fixture.class.getClassLoader(),
                new Class<?>[] { IBlockApplyContext.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTileEntity": return tile;
                        case "getConfig": return config;
                        default: throw new AssertionError("Quantum bridge must not call context." + method.getName());
                    }
                }
            );
        }

        public static void liveQuantumAnalysisDoesNotCaptureInventory(Path unused) throws Exception {
            TileEntity source = newQuantumBridge();
            // Seed vanilla's cached block data so the pre-fix inventory path can run without a world.
            java.lang.reflect.Field blockType = TileEntity.class.getDeclaredField("blockType");
            blockType.setAccessible(true);
            blockType.set(source, BootstrapFixture.quantumChamber);
            java.lang.reflect.Field blockMetadata = TileEntity.class.getDeclaredField("blockMetadata");
            blockMetadata.setAccessible(true);
            blockMetadata.setInt(source, 0);
            IInventory inventory = (IInventory) source;
            assertEquals(1, inventory.getSizeInventory());
            assertNull(inventory.getStackInSlot(0));

            PendingBlock pending = quantumPendingBlock();
            assertSame(pending, pending.analyze(source, PendingBlock.ANALYZE_INV));
            assertNull(pending.inventory, "Live copy must not capture even an empty quantum inventory plan");
        }

        public static void existingQuantumBridgeIgnoresStaleInventoryPlan(Path unused) throws Exception {
            TileEntity target = newQuantumBridge();
            PendingBlock pending = quantumPendingBlock();
            pending.inventory = new InventoryAnalysis() {
                @Override
                public boolean apply(IBlockApplyContext context, IInventory inventory, boolean consume, boolean simulate) {
                    throw new AssertionError("Stale quantum inventory plan must not be applied, even in simulation");
                }
            };
            assertTrue(pending.getRequiredItemsForExistingBlock(quantumContext(target)));
        }

        public static void quantumInventoryApplyPreservesContentsForAllFlags(Path unused) throws Exception {
            InventoryAnalysis stale = new InventoryAnalysis();
            stale.mItems = new IItemProvider[] { new IItemProvider() {
                @Override
                public ItemStack getStack(IPseudoInventory inventory, boolean consume) {
                    throw new AssertionError("Quantum inventory must not resolve a stale item provider");
                }

                @Override
                public IItemProvider clone() {
                    return this;
                }
            } };
            ItemStack existing = new ItemStack(BootstrapFixture.multiPart, 1, 7);
            existing.setTagCompound(new NBTTagCompound());
            existing.getTagCompound().setLong("freq", 987654321L);
            existing.setStackDisplayName("Existing singularity");
            NBTTagCompound originalTag = existing.getTagCompound();
            NBTTagCompound originalNbt = (NBTTagCompound) originalTag.copy();

            for (ItemStack contents : new ItemStack[] { null, existing }) {
                TileEntity target = newQuantumBridge();
                IInventory inventory = (IInventory) target;
                // A new bridge has no cluster, so its inventory callback needs no world.
                inventory.setInventorySlotContents(0, contents);
                IBlockApplyContext context = quantumContext(target);
                for (boolean consume : new boolean[] { false, true }) {
                    for (boolean simulate : new boolean[] { false, true }) {
                        String flags = "consume=" + consume + ", simulate=" + simulate;
                        assertTrue(stale.apply(context, inventory, consume, simulate), flags);
                        assertSame(contents, inventory.getStackInSlot(0), flags);
                        assertSame(originalTag, existing.getTagCompound(), flags);
                        assertEquals(originalNbt, existing.getTagCompound(), flags);
                        assertEquals(1, existing.stackSize, flags);
                        assertEquals(7, existing.getItemDamage(), flags);
                    }
                }
            }
        }

        public static void quantumApplyIgnoresStaleCustomNameAndMemoryCardSettings(Path unused) throws Exception {
            TileEntity target = quantumApplySpy();
            IBlockApplyContext context = quantumContext(target);
            AEAnalysisResult staleName = new AEAnalysisResult();
            staleName.mAECustomName = "Copied singularity";
            AEAnalysisResult staleConfig = new AEAnalysisResult();
            NBTTagCompound settings = new NBTTagCompound();
            settings.setString("display.Name", "Copied singularity");
            staleConfig.mAEConfig = MMUtils.toJsonObject(settings);
            // Keep orientation null: these calls must not need world notifications or any resources.
            assertAll(
                () -> assertTrue(staleName.apply(context), "Stale custom name must be ignored"),
                () -> assertTrue(staleConfig.apply(context), "Stale memory-card settings must be ignored")
            );
        }

        private static TileEntity quantumApplySpy() throws Exception {
            // Generate only the spy at runtime, keeping optional AE types off the test compile classpath.
            String parent = "appeng/tile/qnb/TileQuantumBridge";
            String name = Fixture.class.getName().replace('.', '/') + "$QuantumApplySpy";
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, parent, null);
            MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(0, 0);
            constructor.visitEnd();
            rejectQuantumMutation(writer, "setCustomName", "(Ljava/lang/String;)V");
            rejectQuantumMutation(writer, "uploadSettings", "(Lappeng/util/SettingsFrom;Lnet/minecraft/nbt/NBTTagCompound;)V");
            writer.visitEnd();
            Class<?> spy = new ClassLoader(Fixture.class.getClassLoader()) {
                Class<?> define() {
                    byte[] bytes = writer.toByteArray();
                    return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
                }
            }.define();
            return (TileEntity) spy.getConstructor().newInstance();
        }

        private static void rejectQuantumMutation(ClassWriter writer, String name, String descriptor) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
            method.visitCode();
            method.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
            method.visitInsn(Opcodes.DUP);
            method.visitLdcInsn("Quantum bridge must not call " + name);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false);
            method.visitInsn(Opcodes.ATHROW);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

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
                assertNull(copy.mAEConfig);
                assertEquals(ForgeDirection.NORTH, copy.mAEForward);
                assertSame(BootstrapFixture.quantumRing, loaded.blocks.get(0).spec.getBlock());
            }
        }

        private static StructureBlueprint linkedQuantumSnapshot(Block block) {
            StructureBlueprint blueprint = new StructureBlueprint();
            blueprint.id = UUID.randomUUID().toString();
            blueprint.name = "Linked bridge";
            blueprint.deltas = new Vector3i();
            blueprint.blocks = new ArrayList<>();
            PendingBlock pending = new PendingBlock(7, 0, 0, 0, new BlockSpec().setObject(block, 0));
            PortableItemStack singularity = new PortableItemStack(BootstrapFixture.multiPart, 7);
            singularity.nbt = new NBTTagCompound();
            singularity.nbt.setLong("freq", 987654321L);
            pending.inventory = new InventoryAnalysis();
            pending.inventory.mItems = new IItemProvider[] { singularity };
            AEAnalysisResult ae = new AEAnalysisResult();
            ae.mAEForward = ForgeDirection.NORTH;
            ae.mAEUp = ForgeDirection.UP;
            ae.mAECustomName = "Original singularity";
            NBTTagCompound settings = new NBTTagCompound();
            settings.setString("display.Name", "Original singularity");
            ae.mAEConfig = MMUtils.toJsonObject(settings);
            pending.ae = ae;
            blueprint.blocks.add(pending);
            return blueprint;
        }

        private static void assertNoQuantumItemData(StructureBlueprint blueprint) {
            PendingBlock block = blueprint.blocks.get(0);
            assertNull(block.inventory, "No inventory plan may insert or remove a quantum singularity");
            AEAnalysisResult ae = (AEAnalysisResult) block.ae;
            assertNull(ae.mAECustomName, "Renaming a linked singularity can also rename its remote partner");
            assertNull(ae.mAEConfig);
            assertEquals(ForgeDirection.NORTH, ae.mAEForward);
            assertEquals(ForgeDirection.UP, ae.mAEUp);
            assertNull(blueprint.instantiate(42).blocks.get(0).inventory);
        }

        public static void quantumBlueprintsOmitSingularitiesAndNamesWithoutMutatingSource(Path directory) throws Exception {
            for (Block block : new Block[] { BootstrapFixture.quantumRing, BootstrapFixture.quantumChamber }) {
                StructureBlueprint source = linkedQuantumSnapshot(block);
                RegionAnalysis region = new RegionAnalysis();
                region.deltas = source.deltas;
                region.blocks = source.blocks;
                StructureBlueprint captured = StructureBlueprint.capture(region, "Safe quantum bridge");
                assertNoQuantumItemData(captured);
                assertNoQuantumItemData(BlueprintCodec.decode(BlueprintCodec.encode(source)));
                assertEquals(987654321L, ((PortableItemStack) source.blocks.get(0).inventory.mItems[0]).nbt.getLong("freq"));
                assertEquals("Original singularity", ((AEAnalysisResult) source.blocks.get(0).ae).mAECustomName);
                UUID owner = UUID.randomUUID();
                try {
                    BlueprintStore.save(directory, owner, source);
                    BlueprintStore.clearCache();
                    assertNoQuantumItemData(BlueprintStore.load(directory, owner, source.id));
                } finally {
                    BlueprintStore.clearCache();
                }
            }
        }

        public static void legacyQuantumBlueprintsDiscardSingularitiesOnDiskAndCachedLoads(Path directory) throws Exception {
            for (Block block : new Block[] { BootstrapFixture.quantumRing, BootstrapFixture.quantumChamber }) {
                StructureBlueprint source = linkedQuantumSnapshot(block);
                // Serialize as an ordinary block to construct a pre-fix payload, then restore the legacy quantum ID.
                source.blocks.get(0).spec = new BlockSpec().setObject(BootstrapFixture.multiPart, 3);
                JsonObject legacy = new JsonParser().parse(BlueprintCodec.encode(source)).getAsJsonObject();
                JsonObject spec = legacy.getAsJsonArray("blocks").get(0).getAsJsonObject().getAsJsonObject("spec");
                spec.addProperty("b", true);
                spec.addProperty("id", GameRegistry.findUniqueIdentifierFor(block).toString());
                assertTrue(legacy.getAsJsonArray("blocks").get(0).getAsJsonObject().has("inventory"));
                String json = legacy.toString();
                assertNoQuantumItemData(BlueprintCodec.decode(json));
                UUID owner = UUID.randomUUID();
                Path file = directory.resolve("matter_manipulator").resolve("blueprints").resolve(owner.toString()).resolve(source.id + ".json.gz");
                Files.createDirectories(file.getParent());
                try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(file))) {
                    output.write(json.getBytes(StandardCharsets.UTF_8));
                }
                try {
                    BlueprintStore.clearCache();
                    assertNoQuantumItemData(BlueprintStore.load(directory, owner, source.id));
                    assertNoQuantumItemData(BlueprintStore.load(directory, owner, source.id));
                } finally {
                    BlueprintStore.clearCache();
                }
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
