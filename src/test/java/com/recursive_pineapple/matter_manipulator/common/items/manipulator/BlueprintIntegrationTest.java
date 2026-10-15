package com.recursive_pineapple.matter_manipulator.common.items.manipulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.function.Consumer;

import net.minecraft.entity.Entity;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;

import cpw.mods.fml.common.ModAPIManager;
import cpw.mods.fml.common.asm.transformers.ModAPITransformer;
import cpw.mods.fml.common.discovery.ASMDataTable;
import cpw.mods.fml.common.discovery.asm.ASMModParser;

import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator.ManipulatorTier;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig.VoxelAABB;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PlaceMode;

import org.joml.Vector3i;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlueprintIntegrationTest {

    private static LaunchClassLoader gameLoader;
    private static LaunchClassLoader previousLoader;
    private static Class<?> fixture;

    @BeforeAll
    static void bootstrap() throws Exception {
        String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < urls.length; i++) urls[i] = new File(classpath[i]).toURI().toURL();
        gameLoader = new LaunchClassLoader(urls);
        gameLoader.addClassLoaderExclusion("org.junit.");
        gameLoader.addClassLoaderExclusion("org.opentest4j.");
        previousLoader = Launch.classLoader;
        Launch.classLoader = gameLoader;
        // Reuse the existing registry bootstrap without changing its fixture or JUnit's application loader.
        Class.forName("com.recursive_pineapple.matter_manipulator.common.blueprint.StructureBlueprintTest$Fixture", true, gameLoader)
            .getMethod("bootstrap").invoke(null);
        gameLoader.registerTransformer("com.recursive_pineapple.matter_manipulator.asm.DeMemberator");
        gameLoader.registerTransformer("cpw.mods.fml.common.asm.transformers.ModAPITransformer");
        fixture = Class.forName(BlueprintIntegrationTest.class.getName() + "$Fixture", true, gameLoader);
        run("initializeOptionalInterfaces");
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
    void blueprintBoundsDoNotRequireSourceCoordinatesOrTheirDimension() throws Exception {
        run("blueprintBoundsDoNotRequireSourceCoordinatesOrTheirDimension");
    }

    @Test
    void blueprintBoundsRejectMissingMetadataAndWrongDestinationDimension() throws Exception {
        run("blueprintBoundsRejectMissingMetadataAndWrongDestinationDimension");
    }

    @Test
    void signedBlueprintDeltasDriveArraysAndFlippedPreviewBounds() throws Exception {
        run("signedBlueprintDeltasDriveArraysAndFlippedPreviewBounds");
    }

    @Test
    void arraySelectionUsesBlueprintSizeAndInverseFlips() throws Exception {
        run("arraySelectionUsesBlueprintSizeAndInverseFlips");
    }

    @Test
    void blueprintIdentityOwnerAndDeltasInvalidateEqualityAndHash() throws Exception {
        run("blueprintIdentityOwnerAndDeltasInvalidateEqualityAndHash");
    }

    @Test
    void clearBlueprintRemovesOnlyBlueprintMetadataAndRestoresSourcePreview() throws Exception {
        run("clearBlueprintRemovesOnlyBlueprintMetadataAndRestoresSourcePreview");
    }

    @Test
    void expansionAcceptsTheLimitAndRejectsOversizedProducts() throws Exception {
        run("expansionAcceptsTheLimitAndRejectsOversizedProducts");
    }

    @Test
    void expansionRejectsExtremeSignedSpansWithoutOverflow() throws Exception {
        run("expansionRejectsExtremeSignedSpansWithoutOverflow");
    }

    @Test
    void onlyCopyCapableTiersCanUseBlueprintsAndUnsupportedTiersNeverReadTheWorld() throws Exception {
        run("onlyCopyCapableTiersCanUseBlueprintsAndUnsupportedTiersNeverReadTheWorld");
    }

    public static class Fixture {

        public static void initializeOptionalInterfaces() throws Exception {
            // Use Forge's own annotation parser/transformer, not test-authored bytecode patches or optional-mod stubs.
            ASMDataTable table = new ASMDataTable();
            try (InputStream stream = Launch.classLoader.getResourceAsStream(
                "com/recursive_pineapple/matter_manipulator/common/items/manipulator/ItemMatterManipulator.class")) {
                assertNotNull(stream);
                new ASMModParser(stream).sendToTable(table, null);
            }
            ModAPIManager.INSTANCE.registerDataTableAndParseAPI(table);
            boolean initialized = false;
            for (var transformer : Launch.classLoader.getTransformers()) {
                if (transformer instanceof ModAPITransformer optional) {
                    optional.initTable(table);
                    initialized = true;
                }
            }
            assertTrue(initialized, "Forge's optional-interface transformer must be installed");
        }

        private static MMConfig blueprint() {
            MMConfig config = new MMConfig();
            config.placeMode = PlaceMode.COPYING;
            config.blueprintId = "saved-blueprint";
            config.blueprintOwner = "00000000-0000-0000-0000-000000000001";
            config.blueprintDeltas = new Vector3i(-2, 1, 0);
            config.coordC = new Location(42, 10, 64, 30);
            return config;
        }

        private static void assertBounds(VoxelAABB bounds, Vector3i min, Vector3i max) {
            assertNotNull(bounds);
            assertEquals(new Vector3i(10, 64, 30), bounds.origin);
            assertEquals(min, bounds.min());
            assertEquals(max, bounds.max());
        }

        public static void blueprintBoundsDoNotRequireSourceCoordinatesOrTheirDimension() {
            World world = new PreviewWorld(42);
            MMConfig config = blueprint();
            assertBounds(config.getPasteVisualDeltas(world, true), new Vector3i(8, 64, 30), new Vector3i(10, 65, 30));
            assertNull(config.getCopyVisualDeltas(world));

            config.coordA = new Location(-1, 100, 4, 200);
            config.coordB = new Location(-1, 120, 10, 230);
            assertBounds(config.getPasteVisualDeltas(world, true), new Vector3i(8, 64, 30), new Vector3i(10, 65, 30));
            assertNull(config.getCopyVisualDeltas(null), "A stored snapshot must not preview the old live source");
            config.coordB = new Location(7, -500, 30, 900);
            assertBounds(config.getPasteVisualDeltas(world, false), new Vector3i(8, 64, 30), new Vector3i(10, 65, 30));
            assertEquals(new Vector3i(-2, 1, 0), config.blueprintDeltas);
        }

        public static void blueprintBoundsRejectMissingMetadataAndWrongDestinationDimension() {
            World world = new PreviewWorld(42);
            MMConfig config = blueprint();
            assertNull(config.getPasteVisualDeltas(new PreviewWorld(7), true));
            config.blueprintDeltas = null;
            assertNull(config.getPasteVisualDeltas(world, true));
            assertEquals(new Vector3i(), config.getArrayMult(world, null, null, config.coordC, new Vector3i(20)));
            config.blueprintDeltas = new Vector3i();
            assertBounds(config.getPasteVisualDeltas(world, true), new Vector3i(10, 64, 30), new Vector3i(10, 64, 30));
            assertEquals(new Vector3i(), config.getArrayMult(world, null, null, null, new Vector3i(20)));
            assertEquals(new Vector3i(), config.getArrayMult(new PreviewWorld(7), null, null, config.coordC, new Vector3i(20)));
            config.coordC = null;
            assertNull(config.getPasteVisualDeltas(world, true));
        }

        public static void signedBlueprintDeltasDriveArraysAndFlippedPreviewBounds() {
            MMConfig config = blueprint();
            config.arraySpan = new Vector3i(2, -1, 3);
            config.transform = new Transform();
            config.transform.flipX = true;
            config.transform.flipZ = true;
            assertBounds(config.getPasteVisualDeltas(null, false), new Vector3i(2, 62, 30), new Vector3i(10, 65, 33));
            assertBounds(config.getPasteVisualDeltas(null, true), new Vector3i(10, 62, 27), new Vector3i(18, 65, 30));
            assertEquals(new Vector3i(-2, 1, 0), config.blueprintDeltas);
            assertEquals(new Vector3i(2, -1, 3), config.arraySpan);
            assertEquals(new Location(42, 10, 64, 30), config.coordC);
        }

        public static void arraySelectionUsesBlueprintSizeAndInverseFlips() {
            World world = new PreviewWorld(42);
            MMConfig config = blueprint();
            Vector3i lookingAt = new Vector3i(3, 67, 28);
            assertEquals(new Vector3i(2, 1, -2), config.getArrayMult(world, null, null, config.coordC, lookingAt));
            assertEquals(new Vector3i(3, 67, 28), lookingAt);
            config.coordA = new Location(-1, 1, 1, 1);
            config.coordB = new Location(7, 999, 999, 999);
            assertEquals(new Vector3i(-1), config.getArrayMult(world, config.coordA, config.coordB, config.coordC, new Vector3i(11, 63, 29)));
            config.transform = new Transform();
            config.transform.flipX = true;
            config.transform.flipZ = true;
            assertEquals(new Vector3i(2, 1, -2), config.getArrayMult(world, config.coordA, config.coordB, config.coordC, new Vector3i(17, 67, 32)));
            assertEquals(new Vector3i(-2, 1, 0), config.blueprintDeltas);
        }

        private static void assertInvalidates(Consumer<MMConfig> change) {
            MMConfig original = blueprint();
            MMConfig changed = blueprint();
            assertEquals(original, changed);
            assertEquals(original.hashCode(), changed.hashCode());
            int previousHash = changed.hashCode();
            change.accept(changed);
            assertNotEquals(original, changed);
            assertNotEquals(changed, original);
            assertNotEquals(previousHash, changed.hashCode(), "Preview cache key must include blueprint metadata");
        }

        public static void blueprintIdentityOwnerAndDeltasInvalidateEqualityAndHash() {
            assertInvalidates(config -> config.blueprintId = "another-blueprint");
            assertInvalidates(config -> config.blueprintOwner = "another-owner");
            assertInvalidates(config -> config.blueprintDeltas.x--);
            assertInvalidates(config -> config.blueprintDeltas.y++);
            assertInvalidates(config -> config.blueprintDeltas.z++);
            assertInvalidates(config -> config.blueprintDeltas = null);
            assertInvalidates(MMConfig::clearBlueprint);
        }

        public static void clearBlueprintRemovesOnlyBlueprintMetadataAndRestoresSourcePreview() {
            MMConfig config = blueprint();
            config.coordA = new Location(42, 100, 5, 200);
            config.coordB = new Location(42, 104, 7, 203);
            config.arraySpan = new Vector3i();
            config.transform = new Transform();
            MMConfig expected = blueprint();
            expected.coordA = config.coordA.clone();
            expected.coordB = config.coordB.clone();
            expected.arraySpan = new Vector3i();
            expected.transform = new Transform();
            expected.blueprintId = null;
            expected.blueprintOwner = null;
            expected.blueprintDeltas = null;

            assertTrue(config.isBlueprint());
            config.clearBlueprint();
            assertFalse(config.isBlueprint());
            assertNull(config.blueprintId);
            assertNull(config.blueprintOwner);
            assertNull(config.blueprintDeltas);
            assertEquals(expected, config);
            assertEquals(expected.hashCode(), config.hashCode());
            assertBounds(config.getPasteVisualDeltas(new PreviewWorld(42), false), new Vector3i(10, 64, 30), new Vector3i(14, 66, 33));
            VoxelAABB source = config.getCopyVisualDeltas(new PreviewWorld(42));
            assertNotNull(source);
            assertEquals(config.coordA.toVec(), source.a);
            assertEquals(config.coordB.toVec(), source.b);
            config.clearBlueprint();
            assertEquals(expected, config, "Clearing an already cleared reference is idempotent");
        }

        public static void expansionAcceptsTheLimitAndRejectsOversizedProducts() {
            assertTrue(MMState.canExpandBlueprint(262144, null));
            assertFalse(MMState.canExpandBlueprint(262145, null));
            assertTrue(MMState.canExpandBlueprint(262144, new Vector3i()));
            assertTrue(MMState.canExpandBlueprint(1, new Vector3i(63, 63, 63)));
            assertFalse(MMState.canExpandBlueprint(1, new Vector3i(63, 63, 64)));
            assertTrue(MMState.canExpandBlueprint(1024, new Vector3i(3, 7, 7)));
            assertFalse(MMState.canExpandBlueprint(1025, new Vector3i(3, 7, 7)));
            assertTrue(MMState.canExpandBlueprint(0, null));
            assertFalse(MMState.canExpandBlueprint(0, new Vector3i(Integer.MAX_VALUE, 0, 0)));
        }

        public static void expansionRejectsExtremeSignedSpansWithoutOverflow() {
            for (int axis = 0; axis < 3; axis++) {
                for (int extreme : new int[] { Integer.MIN_VALUE, Integer.MAX_VALUE }) {
                    Vector3i span = new Vector3i();
                    span.setComponent(axis, extreme);
                    assertFalse(MMState.canExpandBlueprint(1, span), span.toString());
                    assertFalse(MMState.canExpandBlueprint(262144, span), span.toString());
                }
            }
            assertFalse(MMState.canExpandBlueprint(1, new Vector3i(Integer.MIN_VALUE)));
            assertFalse(MMState.canExpandBlueprint(Integer.MAX_VALUE, new Vector3i(Integer.MAX_VALUE)));
            assertFalse(MMState.canExpandBlueprint(1, new Vector3i(-65536, 65535, 65535)));
        }

        public static void onlyCopyCapableTiersCanUseBlueprintsAndUnsupportedTiersNeverReadTheWorld() {
            for (ManipulatorTier tier : ManipulatorTier.values()) {
                // Item construction registers event handlers and requires a complete Forge mod lifecycle.
                // Supply the real tier's capabilities while exercising MMState's unmodified guard methods.
                MMState state = new MMState() {
                    @Override
                    public boolean hasCap(int cap) {
                        return (tier.capabilities & cap) == cap;
                    }
                };
                state.config = blueprint();
                boolean canCopy = tier == ManipulatorTier.Tier2 || tier == ManipulatorTier.Tier3;
                assertEquals(canCopy, (tier.capabilities & ItemMatterManipulator.ALLOW_COPYING) != 0, tier.name());
                assertEquals(canCopy, state.hasCap(ItemMatterManipulator.ALLOW_COPYING), tier.name());
                if (!canCopy) {
                    // coordC is populated: reaching blueprint analysis would dereference the null world.
                    assertTrue(state.getPendingBlocks(tier, null).isEmpty(), tier.name());
                    assertNull(state.getBlueprint(null), tier.name());
                    assertFalse(state.canUseBlueprint(null), tier.name());
                    state.config.clearBlueprint();
                    state.config.coordA = new Location(42, 0, 0, 0);
                    state.config.coordB = new Location(42, 1, 1, 1);
                    assertTrue(state.getPendingBlocks(tier, null).isEmpty(), tier.name());
                }
            }
        }
    }

    /** Dimension-only preview context; no chunks, entities, disk saves, or running server are needed. */
    public static class PreviewWorld extends World {

        public PreviewWorld(int dimension) {
            super(new SaveHandlerMP(), "blueprint-preview", new WorldProviderSurface(),
                new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT), new Profiler());
            provider.dimensionId = dimension;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            throw new AssertionError("Blueprint preview must not create chunks");
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            throw new AssertionError("Blueprint preview must not query entities");
        }
    }
}
