package com.recursive_pineapple.matter_manipulator.common.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;

import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;

import com.mojang.authlib.GameProfile;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials.Entry;
import com.recursive_pineapple.matter_manipulator.common.building.AEAnalysisResult;
import com.recursive_pineapple.matter_manipulator.common.building.AEPartData;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.InventoryAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.building.providers.IItemProvider;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;

import org.joml.Vector3i;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class BlueprintMaterialsTest {

    private static LaunchClassLoader gameLoader, previousLoader;
    private static Class<?> fixture;

    @BeforeAll
    static void registries() throws Exception {
        String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < urls.length; i++) urls[i] = new File(classpath[i]).toURI().toURL();
        gameLoader = new LaunchClassLoader(urls);
        gameLoader.addClassLoaderExclusion("org.junit.");
        gameLoader.addClassLoaderExclusion("org.opentest4j.");
        previousLoader = Launch.classLoader;
        Launch.classLoader = gameLoader;
        String bootstrap = hasAE() ? BlueprintMaterialsTest.class.getName() + "$BootstrapFixture" : StructureBlueprintTest.class.getName() + "$Fixture";
        Class.forName(bootstrap, true, gameLoader).getMethod("bootstrap").invoke(null);
        gameLoader.registerTransformer("com.recursive_pineapple.matter_manipulator.asm.DeMemberator");
        gameLoader.registerTransformer("cpw.mods.fml.common.asm.transformers.ModAPITransformer");
        Class.forName("com.recursive_pineapple.matter_manipulator.common.items.manipulator.BlueprintIntegrationTest$Fixture", true, gameLoader)
            .getMethod("initializeOptionalInterfaces").invoke(null);
        fixture = Class.forName(BlueprintMaterialsTest.class.getName() + "$Fixture", true, gameLoader);
    }

    @AfterAll
    static void closeLoader() throws Exception {
        Launch.classLoader = previousLoader;
        if (gameLoader != null) gameLoader.close();
    }

    private static boolean hasAE() {
        return BlueprintMaterialsTest.class.getClassLoader().getResource("appeng/api/util/AEColor.class") != null;
    }

    private static void runAE(String name) throws Exception {
        assumeTrue(hasAE(), "Run with the existing main compileClasspath added to the test runtime to exercise AE2");
        run(name);
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
    void analyzesOneSnapshotWithoutTouchingStateResourcesOrWorld() throws Exception {
        run("analyzesOneSnapshotWithoutTouchingStateResourcesOrWorld");
    }

    @Test
    void aggregatesLongTotalsAndFiltersNonpositiveRequirements() throws Exception {
        run("aggregatesLongTotalsAndFiltersNonpositiveRequirements");
    }

    @Test
    void preservesConfiguredVariantsAndSortsDeterministically() throws Exception {
        run("preservesConfiguredVariantsAndSortsDeterministically");
    }

    @Test
    void boundsAndSanitizesWireStringsWithoutBrokenSurrogates() throws Exception {
        run("boundsAndSanitizesWireStringsWithoutBrokenSurrogates");
    }

    @Test
    void rejectsUnsupportedManipulatorsBeforeAccessingPlayerOrBlueprint() throws Exception {
        run("rejectsUnsupportedManipulatorsBeforeAccessingPlayerOrBlueprint");
    }

    @Test
    void fromScratchNeverQueriesTilesButNormalPlanningStillDoes() throws Exception {
        run("fromScratchNeverQueriesTilesButNormalPlanningStillDoes");
    }

    @Test
    void quantumBlueprintsNeverRequireInstalledSingularities() throws Exception {
        runAE("quantumBlueprintsNeverRequireInstalledSingularities");
    }

    @Test
    void patternFlagControlsNewBlockAndPartCountsWithoutDroppingUpgrades() throws Exception {
        runAE("patternFlagControlsNewBlockAndPartCountsWithoutDroppingUpgrades");
    }

    @Test
    void patternFlagControlsExistingBlockAndPartCountsInSimulation() throws Exception {
        runAE("patternFlagControlsExistingBlockAndPartCountsInSimulation");
    }

    public static class BootstrapFixture {

        public static void bootstrap() throws Exception {
            AEBlueprintRegressionTest.BootstrapFixture.bootstrap();
            // Material analysis also checks AE's free cable-bus block, absent from the persistence-only fixture.
            java.lang.reflect.Field controller = Loader.class.getDeclaredField("modController");
            controller.setAccessible(true);
            LoadController loadController = (LoadController) controller.get(Loader.instance());
            java.lang.reflect.Field active = LoadController.class.getDeclaredField("activeContainer");
            active.setAccessible(true);
            active.set(loadController, Loader.instance().getIndexedModList().get("appliedenergistics2"));
            try {
                GameRegistry.registerBlock(new Block(Material.iron) {}, "tile.BlockCableBus");
            } finally {
                active.set(loadController, null);
            }
        }
    }

    public static class Fixture {

        private static MMState state(boolean canCopy) {
            MMState state = new MMState() {
                @Override
                public boolean hasCap(int cap) {
                    assertEquals(ItemMatterManipulator.ALLOW_COPYING, cap);
                    return canCopy;
                }

                @Override
                public boolean connectToMESystem() {
                    throw new AssertionError("Must not connect to ME or start crafting");
                }

                @Override
                public boolean connectToUplink() {
                    throw new AssertionError("Must not connect to uplink");
                }
            };
            state.config = new MMConfig();
            return state;
        }

        private static StructureBlueprint blueprint(int count) {
            StructureBlueprint blueprint = new StructureBlueprint();
            blueprint.id = UUID.randomUUID().toString();
            blueprint.name = "Materials";
            blueprint.deltas = new Vector3i(count - 1, 0, 0);
            blueprint.blocks = new ArrayList<>();
            for (int x = 0; x < count; x++) {
                blueprint.blocks.add(new PendingBlock(7, x, 0, 0, new BlockSpec().setObject(Blocks.stone, 0)));
            }
            return blueprint;
        }

        private static PortableItemStack stack(Item item, int amount) {
            PortableItemStack stack = new PortableItemStack(item, 0);
            stack.amount = amount;
            return stack;
        }

        private static InventoryAnalysis inventory(IItemProvider... items) {
            InventoryAnalysis inventory = new InventoryAnalysis();
            inventory.mItems = items;
            return inventory;
        }

        private static long amount(List<Entry> entries, String registryName) {
            return entries.stream().filter(entry -> entry.registryName.equals(registryName)).mapToLong(entry -> entry.amount).sum();
        }

        private static List<String> rows(List<Entry> entries) {
            return entries.stream()
                .map(entry -> entry.displayName + ":" + entry.registryName + ":" + entry.metadata + ":" + entry.amount + ":" + entry.configured)
                .collect(Collectors.toList());
        }

        public static void analyzesOneSnapshotWithoutTouchingStateResourcesOrWorld() {
            ReadOnlyPlayer player = new ReadOnlyPlayer(new ReadOnlyWorld());
            MMState state = state(true);
            MMConfig config = state.config;
            config.transform = new Transform();
            config.arraySpan = new Vector3i(100);
            config.coordA = new Location(99, 100, 200, 300);
            config.coordB = new Location(99, 400, 500, 600);
            config.coordC = new Location(99, 700, 800, 900);
            config.blueprintId = UUID.randomUUID().toString();
            config.blueprintOwner = UUID.randomUUID().toString();
            config.placeMode = MMState.PlaceMode.MOVING;
            state.charge = 123456;
            state.encKey = 42L;
            state.uplinkAddress = 71L;
            state.installedUpgrades = new BitSet();
            state.installedUpgrades.set(2);
            player.inventory.currentItem = 2;
            ItemStack held = new ItemStack(Items.stick, 17);
            held.setStackDisplayName("Held item must not change");
            player.inventory.mainInventory[2] = held;
            NBTTagCompound heldBefore = held.writeToNBT(new NBTTagCompound());
            MMState configSnapshot = new MMState();
            configSnapshot.config = config;
            NBTTagCompound configBefore = configSnapshot.save();

            StructureBlueprint blueprint = blueprint(3);
            for (PendingBlock block : blueprint.blocks) block.inventory = inventory(stack(Items.stick, 64));
            String before = BlueprintCodec.encode(blueprint);
            List<Entry> first = BlueprintMaterials.analyze(player, state, blueprint);
            assertEquals(192L, amount(first, "minecraft:stick"));
            assertEquals(3L, amount(first, "minecraft:stone"));
            assertEquals(rows(first), rows(BlueprintMaterials.analyze(player, state, blueprint)));
            assertEquals(before, BlueprintCodec.encode(blueprint));
            assertNull(((PortableItemStack) blueprint.blocks.get(0).inventory.mItems[0]).itemStack);
            assertSame(config, state.config);
            assertEquals(configBefore, configSnapshot.save());
            assertEquals(new Vector3i(100), config.arraySpan);
            assertEquals(700, config.coordC.x);
            assertEquals(MMState.PlaceMode.MOVING, config.placeMode);
            assertEquals(123456, state.charge);
            assertEquals(42L, state.encKey.longValue());
            assertEquals(71L, state.uplinkAddress.longValue());
            assertEquals(BitSet.valueOf(new long[] { 4 }), state.installedUpgrades);
            assertSame(held, player.inventory.mainInventory[2]);
            assertEquals(heldBefore, held.writeToNBT(new NBTTagCompound()));
            assertThrows(UnsupportedOperationException.class, first::clear);
        }

        public static void aggregatesLongTotalsAndFiltersNonpositiveRequirements() {
            StructureBlueprint blueprint = blueprint(2);
            for (PendingBlock block : blueprint.blocks) {
                block.inventory = inventory(stack(Items.stick, Integer.MAX_VALUE), stack(Items.apple, 0), stack(Items.diamond, -1));
            }
            List<Entry> entries = BlueprintMaterials.analyze(new ReadOnlyPlayer(new ReadOnlyWorld()), state(true), blueprint);
            assertEquals(2L * Integer.MAX_VALUE, amount(entries, "minecraft:stick"));
            assertTrue(entries.stream().allMatch(entry -> entry.amount > 0));
            assertFalse(entries.stream().anyMatch(entry -> entry.registryName.equals("minecraft:apple") || entry.registryName.equals("minecraft:diamond")));
        }

        public static void preservesConfiguredVariantsAndSortsDeterministically() {
            StructureBlueprint blueprint = blueprint(1);
            PortableItemStack first = stack(Items.stick, 5);
            first.nbt = new NBTTagCompound();
            first.nbt.setByteArray("bytes", new byte[] { 1 });
            first.nbt.setIntArray("ints", new int[] { 2, 3 });
            PortableItemStack same = stack(Items.stick, 7);
            same.nbt = new NBTTagCompound();
            same.nbt.setIntArray("ints", new int[] { 2, 3 });
            same.nbt.setByteArray("bytes", new byte[] { 1 });
            PortableItemStack second = stack(Items.stick, 6);
            second.nbt = (NBTTagCompound) first.nbt.copy();
            second.nbt.setByteArray("bytes", new byte[] { 2 });
            ItemStack namedStack = new ItemStack(Items.stick);
            namedStack.setStackDisplayName("\u00a7bActual configured name");
            PortableItemStack named = PortableItemStack.withNBT(namedStack);
            PortableItemStack metadata = new PortableItemStack(Items.dye, 4);
            blueprint.blocks.get(0).inventory = inventory(second, same, first, named, stack(Items.stick, 2), metadata);
            ReadOnlyPlayer player = new ReadOnlyPlayer(new ReadOnlyWorld());
            List<Entry> entries = BlueprintMaterials.analyze(player, state(true), blueprint);
            List<Entry> configuredSticks = entries.stream()
                .filter(entry -> entry.registryName.equals("minecraft:stick") && entry.configured && !entry.displayName.equals("Actual configured name"))
                .collect(Collectors.toList());
            assertEquals(Arrays.asList(12L, 6L), configuredSticks.stream().map(entry -> entry.amount).collect(Collectors.toList()));
            assertTrue(entries.stream().anyMatch(entry -> entry.configured && entry.displayName.equals("Actual configured name")));
            assertTrue(entries.stream().anyMatch(entry -> !entry.configured && entry.registryName.equals("minecraft:stick") && entry.amount == 2));
            assertTrue(entries.stream().anyMatch(entry -> entry.registryName.equals("minecraft:dye") && entry.metadata == 4));
            List<String> names = entries.stream().map(entry -> entry.displayName).collect(Collectors.toList());
            List<String> sortedNames = new ArrayList<>(names);
            Collections.sort(sortedNames);
            assertEquals(sortedNames, names);
            Collections.reverse(Arrays.asList(blueprint.blocks.get(0).inventory.mItems));
            assertEquals(rows(entries), rows(BlueprintMaterials.analyze(player, state(true), blueprint)));
        }

        public static void boundsAndSanitizesWireStringsWithoutBrokenSurrogates() {
            String emoji = new String(Character.toChars(0x1f642));
            StringBuilder longName = new StringBuilder("\u00a7a\n\t\u202e\ud800");
            for (int i = 0; i < 200; i++) longName.append(emoji);
            longName.append('\udc00');
            Entry entry = new Entry("minecraft:stick", 3, longName.toString(), Long.MAX_VALUE, true);
            assertEquals(128, entry.displayName.codePointCount(0, entry.displayName.length()));
            assertEquals(512, entry.displayName.getBytes(StandardCharsets.UTF_8).length);
            assertEquals(emoji, entry.displayName.substring(0, 2));
            assertEquals("AB", new Entry("minecraft:stick", 0, "A\ud800\u00a7c\u0000\u200dB\udc00", 1, false).displayName);
            String registry = String.join("", Collections.nCopies(128, "\u00e9"));
            assertEquals(registry, new Entry(registry, 0, "name", 1, false).registryName);
            assertThrows(IllegalArgumentException.class, () -> new Entry(registry + "a", 0, "name", 1, false));
            assertEquals(Long.MAX_VALUE, entry.amount);
        }

        public static void rejectsUnsupportedManipulatorsBeforeAccessingPlayerOrBlueprint() {
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterials.analyze(null, state(false), null));
        }

        public static void fromScratchNeverQueriesTilesButNormalPlanningStillDoes() {
            ReadOnlyWorld world = new ReadOnlyWorld();
            ReadOnlyPlayer player = new ReadOnlyPlayer(world);
            PendingBlock block = new PendingBlock(42, 0, 0, 0, BlockSpec.air()) {
                @Override
                public boolean getRequiredItemsForNewBlock(IBlockApplyContext context) {
                    assertSame(world.allowDestinationReads ? world.tile : null, context.getTileEntity());
                    return true;
                }
            };
            BlockAnalyzer.getRequiredItemsForBuild(player, state(true), Collections.singletonList(block), true);
            world.allowDestinationReads = true;
            BlockAnalyzer.getRequiredItemsForBuild(player, state(true), Collections.singletonList(block), false);
            assertEquals(1, world.tileReads);
        }

        public static void quantumBlueprintsNeverRequireInstalledSingularities() {
            StructureBlueprint blueprint = blueprint(1);
            blueprint.blocks.get(0).spec = new BlockSpec().setObject(AEBlueprintRegressionTest.BootstrapFixture.quantumChamber, 0);
            blueprint.blocks.get(0).inventory = inventory(stack(Items.nether_star, 1));
            String before = BlueprintCodec.encode(blueprint);
            List<Entry> entries = BlueprintMaterials.analyze(new ReadOnlyPlayer(new ReadOnlyWorld()), state(true), blueprint);
            assertEquals(1, entries.size());
            assertEquals("appliedenergistics2:tile.BlockQuantumLinkChamber", entries.get(0).registryName);
            assertFalse(entries.get(0).configured);
            assertEquals(before, BlueprintCodec.encode(blueprint));
            assertEquals(1, blueprint.blocks.get(0).inventory.mItems.length);
        }

        public static void patternFlagControlsNewBlockAndPartCountsWithoutDroppingUpgrades() {
            StructureBlueprint blueprint = blueprint(1);
            AEAnalysisResult ae = new AEAnalysisResult();
            ae.mAEPatterns = inventory(stack(Items.paper, 3));
            ae.mAEUpgrades = new PortableItemStack[] { stack(Items.diamond, 2) };
            blueprint.blocks.get(0).ae = ae;
            MMState state = state(true);
            ReadOnlyPlayer player = new ReadOnlyPlayer(new ReadOnlyWorld());
            assertEquals(0, amount(BlueprintMaterials.analyze(player, state, blueprint), "minecraft:paper"));
            assertEquals(2, amount(BlueprintMaterials.analyze(player, state, blueprint), "minecraft:diamond"));
            state.config.shouldCopyAEPatterns = true;
            assertEquals(3, amount(BlueprintMaterials.analyze(player, state, blueprint), "minecraft:paper"));
            assertEquals(2, amount(BlueprintMaterials.analyze(player, state, blueprint), "minecraft:diamond"));

            AEPartData part = new AEPartData();
            part.mAEPatterns = inventory(stack(Items.paper, 4));
            part.mAEUpgrades = new PortableItemStack[] { stack(Items.diamond, 2) };
            MMConfig config = new MMConfig();
            int[] consumed = new int[1];
            IBlockApplyContext context = countingContext(config, null, consumed);
            part.getRequiredItemsForNewPart(context);
            assertEquals(2, consumed[0]);
            config.shouldCopyAEPatterns = true;
            part.getRequiredItemsForNewPart(context);
            assertEquals(8, consumed[0]);
        }

        private static IBlockApplyContext countingContext(MMConfig config, TileEntity tile, int[] consumed) {
            return (IBlockApplyContext) Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[] { IBlockApplyContext.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getConfig": return config;
                    case "getTileEntity": return tile;
                    case "tryConsumeItems":
                        for (ItemStack stack : (ItemStack[]) args[0]) consumed[0] += stack.stackSize;
                        return true;
                    default: throw new AssertionError("Unexpected context call: " + method.getName());
                }
            });
        }

        private static SegmentedTile segmentedTile() throws Exception {
            // AE is a compile-only main dependency; generate the interface bridge just as the existing AE fixture does.
            String parent = SegmentedTile.class.getName().replace('.', '/');
            String name = parent + "Impl";
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, parent, new String[] { "appeng/api/implementations/tiles/ISegmentedInventory" });
            MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(1, 1);
            constructor.visitEnd();
            writer.visitEnd();
            Class<?> type = new ClassLoader(Fixture.class.getClassLoader()) {
                Class<?> define() {
                    byte[] bytes = writer.toByteArray();
                    return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
                }
            }.define();
            return (SegmentedTile) type.getConstructor().newInstance();
        }

        public static void patternFlagControlsExistingBlockAndPartCountsInSimulation() throws Exception {
            MMConfig config = new MMConfig();
            int[] calls = new int[1];
            InventoryAnalysis patterns = new InventoryAnalysis() {
                @Override
                public boolean apply(IBlockApplyContext context, IInventory inventory, boolean consume, boolean simulate) {
                    assertTrue(consume);
                    assertTrue(simulate);
                    calls[0]++;
                    return true;
                }
            };
            SegmentedTile tile = segmentedTile();
            AEAnalysisResult ae = new AEAnalysisResult();
            ae.mAEPatterns = patterns;
            IBlockApplyContext context = countingContext(config, tile, new int[1]);
            ae.getRequiredItemsForExistingBlock(context);
            assertEquals(0, calls[0]);
            config.shouldCopyAEPatterns = true;
            ae.getRequiredItemsForExistingBlock(context);
            assertEquals(1, calls[0]);

            Class<?> partType = Class.forName("appeng.api.parts.IPart");
            Class<?> hostType = Class.forName("appeng.api.parts.IPartHost");
            Object part = Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[] { partType, Class.forName("appeng.api.implementations.tiles.ISegmentedInventory") }, (proxy, method, args) -> {
                if (method.getName().equals("getInventoryByName")) return tile.getInventoryByName((String) args[0]);
                throw new AssertionError("Unexpected part call: " + method.getName());
            });
            Object host = Proxy.newProxyInstance(Fixture.class.getClassLoader(), new Class<?>[] { hostType }, (proxy, method, args) -> {
                if (method.getName().equals("getPart")) return part;
                throw new AssertionError("Unexpected host call: " + method.getName());
            });
            AEPartData expected = new AEPartData();
            expected.mAEPatterns = patterns;
            java.lang.reflect.Method required = AEPartData.class.getMethod("getRequiredItemsForExistingPart", IBlockApplyContext.class, hostType, ForgeDirection.class);
            config.shouldCopyAEPatterns = false;
            required.invoke(expected, context, host, ForgeDirection.UP);
            assertEquals(1, calls[0]);
            config.shouldCopyAEPatterns = true;
            required.invoke(expected, context, host, ForgeDirection.UP);
            assertEquals(2, calls[0]);
        }
    }

    public static class SegmentedTile extends TileEntity {

        public IInventory getInventoryByName(String name) {
            if (!name.equals("patterns")) return null;
            return (IInventory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IInventory.class }, (proxy, method, args) -> {
                throw new AssertionError("Pattern simulation spy must not mutate inventory");
            });
        }
    }

    public static class ReadOnlyPlayer extends EntityPlayer {

        public ReadOnlyPlayer(World world) {
            super(world, new GameProfile(UUID.randomUUID(), "MaterialsTest"));
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            throw new AssertionError("Material calculation must not send player messages");
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return false;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            throw new AssertionError("Material calculation must not inspect player position");
        }
    }

    public static class ReadOnlyWorld extends World {

        public boolean allowDestinationReads;
        public int tileReads;
        public final TileEntity tile = new TileEntity();

        public ReadOnlyWorld() {
            super(new SaveHandlerMP(), "materials-test", new WorldProviderSurface(),
                new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT), new Profiler());
            provider.dimensionId = 42;
        }

        @Override
        public ChunkCoordinates getSpawnPoint() {
            return new ChunkCoordinates(0, 64, 0);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (!allowDestinationReads) throw new AssertionError("Must not query destination tiles");
            tileReads++;
            return tile;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            throw new AssertionError("Must not query destination blocks");
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            throw new AssertionError("Must not query destination metadata");
        }

        @Override
        public boolean isAirBlock(int x, int y, int z) {
            if (!allowDestinationReads) throw new AssertionError("Must not query destination air");
            return true;
        }

        @Override
        public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
            throw new AssertionError("Must not place blocks");
        }

        @Override
        public void setTileEntity(int x, int y, int z, TileEntity tile) {
            throw new AssertionError("Must not place tiles");
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {
            throw new AssertionError("Must not update blocks");
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            throw new AssertionError("Must not create chunks");
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            throw new AssertionError("Must not query entities");
        }
    }
}
