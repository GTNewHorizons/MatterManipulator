package com.recursive_pineapple.matter_manipulator.client.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.ModMetadata;

import com.recursive_pineapple.matter_manipulator.client.nei.BlueprintNEIBookmarks.ExportException;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials.StackEntry;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlueprintNEIBookmarksTest {

    private static LaunchClassLoader gameLoader, previousLoader;
    private static Class<?> fixture;

    @BeforeAll
    static void registries() throws Exception {
        String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < urls.length; i++) urls[i] = new File(classpath[i]).toURI().toURL();
        gameLoader = new LaunchClassLoader(urls) {

            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.startsWith("codechicken.nei.")) throw new ClassNotFoundException("NEI deliberately absent in helper tests: " + name);
                return super.findClass(name);
            }
        };
        gameLoader.addClassLoaderExclusion("org.junit.");
        gameLoader.addClassLoaderExclusion("org.opentest4j.");
        previousLoader = Launch.classLoader;
        Launch.classLoader = gameLoader;
        fixture = Class.forName(BlueprintNEIBookmarksTest.class.getName() + "$Fixture", true, gameLoader);
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
    void aggregatesExactIdentitiesAndKeepsFirstOccurrenceOrder() throws Exception {
        run("aggregatesExactIdentitiesAndKeepsFirstOccurrenceOrder");
    }

    @Test
    void preservesSeparateMetadataAndNbtVariantsIncludingEmptyTags() throws Exception {
        run("preservesSeparateMetadataAndNbtVariantsIncludingEmptyTags");
    }

    @Test
    void normalizesDetachedCopiesWithoutMutatingInputs() throws Exception {
        run("normalizesDetachedCopiesWithoutMutatingInputs");
    }

    @Test
    void rejectsEmptyMalformedAndUnregisteredEntries() throws Exception {
        run("rejectsEmptyMalformedAndUnregisteredEntries");
    }

    @Test
    void checksIndividualAndAggregatedQuantityCaps() throws Exception {
        run("checksIndividualAndAggregatedQuantityCaps");
    }

    @Test
    void boundsEntireExportBeforeAggregation() throws Exception {
        run("boundsEntireExportBeforeAggregation");
    }

    @Test
    void validatesEntireExportBeforeCheckingNEI() throws Exception {
        run("validatesEntireExportBeforeCheckingNEI");
    }

    @Test
    void absentOrUnlinkableNEIDoesNotCrashAvailabilityOrAdd() throws Exception {
        run("absentOrUnlinkableNEIDoesNotCrashAvailabilityOrAdd");
    }

    public static final class Fixture {

        private static Map<String, ModContainer> mods;

        public static void bootstrap() throws Exception {
            Loader.injectData("7", "99", "0", "0", "1.7.10", "9.05", new File("."), Collections.emptyList());
            Field namedMods = Loader.class.getDeclaredField("namedMods");
            namedMods.setAccessible(true);
            mods = new HashMap<>();
            namedMods.set(Loader.instance(), mods);
            Field controller = Loader.class.getDeclaredField("modController");
            controller.setAccessible(true);
            controller.set(Loader.instance(), new LoadController(Loader.instance()));
            Bootstrap.func_151354_b();
        }

        private static StackEntry entry(long amount) {
            return new StackEntry(new ItemStack(Items.stick), amount);
        }

        private static StackEntry configured(int metadata, int value, long amount) {
            ItemStack stack = new ItemStack(Items.stick, 1, metadata);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("configuration", value);
            stack.setTagCompound(tag);
            return new StackEntry(stack, amount);
        }

        private static void error(String suffix, Runnable action) {
            ExportException error = assertThrows(ExportException.class, action::run);
            assertEquals("mm.blueprint.bookmarks." + suffix, error.translationKey);
        }

        public static void aggregatesExactIdentitiesAndKeepsFirstOccurrenceOrder() {
            StackEntry first = configured(0, 7, 3);
            StackEntry second = new StackEntry(new ItemStack(Items.feather), 8);
            List<StackEntry> result = BlueprintNEIBookmarks.prepare(Arrays.asList(first, second, configured(0, 7, 5)));
            assertEquals(2, result.size());
            assertEquals(Items.stick, result.get(0).stack.getItem());
            assertEquals(8, result.get(0).amount);
            assertEquals(Items.feather, result.get(1).stack.getItem());
            assertEquals(8, result.get(1).amount);
        }

        public static void preservesSeparateMetadataAndNbtVariantsIncludingEmptyTags() {
            StackEntry plain = entry(1);
            StackEntry emptyTag = entry(2);
            emptyTag.stack.setTagCompound(new NBTTagCompound());
            List<StackEntry> result = BlueprintNEIBookmarks.prepare(
                Arrays.asList(plain, emptyTag, configured(0, 1, 3), configured(0, 2, 4), configured(1, 1, 5))
            );
            assertEquals(5, result.size());
            for (int i = 0; i < result.size(); i++) assertEquals(i + 1, result.get(i).amount);
            assertEquals(1, result.get(4).stack.getItemDamage());
        }

        public static void normalizesDetachedCopiesWithoutMutatingInputs() {
            StackEntry source = configured(0, 9, 12);
            source.stack.stackSize = 37;
            StackEntry result = BlueprintNEIBookmarks.prepare(Collections.singletonList(source)).get(0);
            assertEquals(37, source.stack.stackSize);
            assertEquals(1, result.stack.stackSize);
            assertEquals(12, result.amount);
            assertNotSame(source.stack, result.stack);
            assertNotSame(source.stack.getTagCompound(), result.stack.getTagCompound());
            result.stack.getTagCompound().setInteger("configuration", 20);
            assertEquals(9, source.stack.getTagCompound().getInteger("configuration"));
            source.stack.getTagCompound().setInteger("configuration", 30);
            assertEquals(20, result.stack.getTagCompound().getInteger("configuration"));
        }

        public static void rejectsEmptyMalformedAndUnregisteredEntries() throws Exception {
            error("empty", () -> BlueprintNEIBookmarks.prepare(null));
            error("empty", () -> BlueprintNEIBookmarks.prepare(Collections.emptyList()));
            error("failed", () -> BlueprintNEIBookmarks.prepare(Collections.singletonList(null)));
            error("failed", () -> BlueprintNEIBookmarks.prepare(Collections.singletonList(new StackEntry(new ItemStack(new Item()), 1))));

            StackEntry broken = entry(1);
            Field amount = StackEntry.class.getDeclaredField("amount");
            amount.setAccessible(true);
            amount.setLong(broken, 0);
            error("failed", () -> BlueprintNEIBookmarks.prepare(Collections.singletonList(broken)));
            amount.setLong(broken, -1);
            error("failed", () -> BlueprintNEIBookmarks.prepare(Collections.singletonList(broken)));
        }

        public static void checksIndividualAndAggregatedQuantityCaps() {
            List<StackEntry> maximum = BlueprintNEIBookmarks.prepare(Collections.singletonList(entry(Integer.MAX_VALUE)));
            assertEquals(Integer.MAX_VALUE, maximum.get(0).amount);
            error("too_large", () -> BlueprintNEIBookmarks.prepare(Collections.singletonList(entry((long) Integer.MAX_VALUE + 1))));
            error("too_large", () -> BlueprintNEIBookmarks.prepare(Collections.singletonList(entry(Long.MAX_VALUE))));
            error("too_large", () -> BlueprintNEIBookmarks.prepare(Arrays.asList(entry(Integer.MAX_VALUE), entry(1))));
            List<StackEntry> exact = BlueprintNEIBookmarks.prepare(Arrays.asList(entry(Integer.MAX_VALUE - 1), entry(1)));
            assertEquals(Integer.MAX_VALUE, exact.get(0).amount);
        }

        public static void boundsEntireExportBeforeAggregation() {
            StackEntry source = entry(1);
            List<StackEntry> result = BlueprintNEIBookmarks.prepare(Collections.nCopies(65536, source));
            assertEquals(1, result.size());
            assertEquals(65536, result.get(0).amount);
            error("failed", () -> BlueprintNEIBookmarks.prepare(Collections.nCopies(65537, source)));
        }

        public static void validatesEntireExportBeforeCheckingNEI() {
            error("too_large", () -> BlueprintNEIBookmarks.add(Arrays.asList(entry(1), entry(Long.MAX_VALUE))));
            error("failed", () -> BlueprintNEIBookmarks.add(Arrays.asList(entry(1), null)));
            error("empty", () -> BlueprintNEIBookmarks.add(Collections.emptyList()));
        }

        public static void absentOrUnlinkableNEIDoesNotCrashAvailabilityOrAdd() {
            assertFalse(BlueprintNEIBookmarks.isAvailable());
            error("unavailable", () -> BlueprintNEIBookmarks.add(Collections.singletonList(entry(1))));
            ModMetadata metadata = new ModMetadata();
            metadata.modId = Names.NOT_ENOUGH_ITEMS;
            mods.put(Names.NOT_ENOUGH_ITEMS, new DummyModContainer(metadata));
            try {
                assertFalse(BlueprintNEIBookmarks.isAvailable());
                error("unavailable", () -> BlueprintNEIBookmarks.add(Collections.singletonList(entry(1))));
                assertFalse(BlueprintNEIBookmarks.isAvailable());
            } finally {
                mods.remove(Names.NOT_ENOUGH_ITEMS);
            }
        }
    }
}
