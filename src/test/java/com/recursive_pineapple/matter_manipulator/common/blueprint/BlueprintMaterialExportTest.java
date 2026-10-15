package com.recursive_pineapple.matter_manipulator.common.blueprint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials.StackEntry;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.InventoryAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.building.providers.IItemProvider;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMConfig;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;

import org.joml.Vector3i;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlueprintMaterialExportTest {

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
        Class.forName(StructureBlueprintTest.class.getName() + "$Fixture", true, gameLoader).getMethod("bootstrap").invoke(null);
        gameLoader.registerTransformer("com.recursive_pineapple.matter_manipulator.asm.DeMemberator");
        gameLoader.registerTransformer("cpw.mods.fml.common.asm.transformers.ModAPITransformer");
        Class.forName("com.recursive_pineapple.matter_manipulator.common.items.manipulator.BlueprintIntegrationTest$Fixture", true, gameLoader)
            .getMethod("initializeOptionalInterfaces").invoke(null);
        fixture = Class.forName(BlueprintMaterialExportTest.class.getName() + "$Fixture", true, gameLoader);
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
    void roundTripsExactMetadataTagsEmptyValuesAndLongCountsWithoutRuntimeGraphs() throws Exception {
        run("roundTripsExactMetadataTagsEmptyValuesAndLongCountsWithoutRuntimeGraphs");
    }

    @Test
    void preservesNullVersusEmptyCompoundAndDefensivelyCopiesStacks() throws Exception {
        run("preservesNullVersusEmptyCompoundAndDefensivelyCopiesStacks");
    }

    @Test
    void preservesTypedEmptyListsAtEveryNestingPosition() throws Exception {
        run("preservesTypedEmptyListsAtEveryNestingPosition");
    }

    @Test
    void rejectsMalformedTypedEmptyLists() throws Exception {
        run("rejectsMalformedTypedEmptyLists");
    }

    @Test
    void rejectsInvalidCountsMetadataRegistriesAndNbt() throws Exception {
        run("rejectsInvalidCountsMetadataRegistriesAndNbt");
    }

    @Test
    void boundsUtf8BytesEntriesAndDepthBeforeRecursiveParsing() throws Exception {
        run("boundsUtf8BytesEntriesAndDepthBeforeRecursiveParsing");
    }

    @Test
    void exportsEveryConfiguredVariantWithoutMutatingBlueprintPlayerOrResources() throws Exception {
        run("exportsEveryConfiguredVariantWithoutMutatingBlueprintPlayerOrResources");
    }

    public static class Fixture {

        private static byte[] utf8(String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }

        private static String row(String item, String meta, String amount, String nbt) {
            return "[{\"item\":" + item + ",\"meta\":" + meta + ",\"amount\":" + amount + ",\"nbt\":" + nbt + "}]";
        }

        private static NBTTagCompound nested(int depth) {
            NBTTagCompound root = new NBTTagCompound(), child = root;
            for (int i = 1; i < depth; i++) {
                NBTTagCompound next = new NBTTagCompound();
                child.setTag("child", next);
                child = next;
            }
            return root;
        }

        public static void roundTripsExactMetadataTagsEmptyValuesAndLongCountsWithoutRuntimeGraphs() {
            ItemStack stack = new ItemStack(Items.dye, 47, 4);
            stack.setStackDisplayName("\u00a7bConfigured \u5efa\u7bc9 \uD83C\uDFE0");
            NBTTagCompound tag = stack.getTagCompound();
            tag.setString("emptyName", "");
            tag.setByteArray("emptyBytes", new byte[0]);
            tag.setIntArray("emptyInts", new int[0]);
            tag.setByteArray("bytes", new byte[] { -128, 0, 127 });
            tag.setIntArray("ints", new int[] { Integer.MIN_VALUE, 0, Integer.MAX_VALUE });
            tag.setByte("byte", (byte) -7);
            tag.setShort("short", (short) -1234);
            tag.setInteger("int", Integer.MIN_VALUE);
            tag.setLong("long", Long.MIN_VALUE);
            tag.setFloat("float", 1.25f);
            tag.setDouble("double", -3.125);
            tag.setTag("emptyCompound", new NBTTagCompound());
            tag.setTag("emptyList", new NBTTagList());
            NBTTagList list = new NBTTagList();
            list.appendTag(new NBTTagString(""));
            list.appendTag(new NBTTagString("exact"));
            tag.setTag("list", list);
            tag.setTag("nested", nested(3));
            StackEntry source = new StackEntry(stack, Long.MAX_VALUE);
            byte[] bytes = BlueprintMaterialExport.encode(Collections.singletonList(source));
            String json = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(json.contains("\"item\":\"minecraft:dye\""));
            assertTrue(json.contains("\"amount\":9223372036854775807"));
            assertFalse(json.contains("unlocalizedName"));
            assertFalse(json.contains("net.minecraft"));
            assertFalse(json.contains("itemID"));
            assertFalse(json.contains("\"id\":"));
            List<StackEntry> decoded = BlueprintMaterialExport.decode(bytes);
            assertEquals(1, decoded.size());
            StackEntry result = decoded.get(0);
            assertSame(Items.dye, result.stack.getItem());
            assertEquals(4, Items.feather.getDamage(result.stack));
            assertEquals(1, result.stack.stackSize);
            assertEquals(Long.MAX_VALUE, result.amount);
            assertEquals(tag, result.stack.getTagCompound());
            assertNotSame(tag, result.stack.getTagCompound());
            assertArrayEquals(new byte[0], result.stack.getTagCompound().getByteArray("emptyBytes"));
            assertArrayEquals(new int[0], result.stack.getTagCompound().getIntArray("emptyInts"));
            assertEquals(47, stack.stackSize);
            assertThrows(UnsupportedOperationException.class, decoded::clear);
        }

        public static void preservesNullVersusEmptyCompoundAndDefensivelyCopiesStacks() {
            ItemStack stack = new ItemStack(Items.stick, 64);
            StackEntry absent = new StackEntry(stack, 3);
            stack.setTagCompound(new NBTTagCompound());
            StackEntry empty = new StackEntry(stack, 4);
            stack.getTagCompound().setString("after", "not in snapshot");
            assertNull(absent.stack.getTagCompound());
            assertEquals(new NBTTagCompound(), empty.stack.getTagCompound());
            assertEquals(64, stack.stackSize);
            List<StackEntry> decoded = BlueprintMaterialExport.decode(BlueprintMaterialExport.encode(Arrays.asList(absent, empty)));
            assertNull(decoded.get(0).stack.getTagCompound());
            assertEquals(new NBTTagCompound(), decoded.get(1).stack.getTagCompound());
            decoded.get(1).stack.getTagCompound().setString("client", "detached");
            assertEquals(new NBTTagCompound(), empty.stack.getTagCompound());
            assertTrue(BlueprintMaterialExport.decode(BlueprintMaterialExport.encode(Collections.emptyList())).isEmpty());
        }

        public static void preservesTypedEmptyListsAtEveryNestingPosition() {
            NBTBase[] elements = {
                new NBTTagByte((byte) 1), new NBTTagShort((short) 1), new NBTTagInt(1), new NBTTagLong(1),
                new NBTTagFloat(1), new NBTTagDouble(1), new NBTTagByteArray(new byte[0]), new NBTTagString("value"),
                new NBTTagList(), new NBTTagCompound(), new NBTTagIntArray(new int[0])
            };
            NBTTagCompound tag = new NBTTagCompound();
            NBTTagCompound nested = new NBTTagCompound();
            NBTTagList lists = new NBTTagList();
            tag.setTag("nested", nested);
            tag.setTag("lists", lists);
            tag.setString("marker", "L8");
            for (NBTBase element : elements) {
                NBTTagList empty = new NBTTagList();
                empty.appendTag(element);
                empty.removeTag(0);
                assertEquals(element.getId(), empty.func_150303_d());
                tag.setTag("empty" + element.getId(), empty);
                nested.setTag("empty" + element.getId(), empty.copy());
                lists.appendTag(empty.copy());
            }
            NBTTagList nonempty = new NBTTagList();
            nonempty.appendTag(new NBTTagString("L8"));
            lists.appendTag(nonempty);
            ItemStack stack = new ItemStack(Items.stick);
            stack.setTagCompound(tag);
            NBTTagCompound before = (NBTTagCompound) tag.copy();
            byte[] bytes = BlueprintMaterialExport.encode(Collections.singletonList(new StackEntry(stack, 1)));
            NBTTagCompound decoded = BlueprintMaterialExport.decode(bytes).get(0).stack.getTagCompound();
            assertEquals(before, decoded);
            assertEquals(before, tag);
            assertEquals("L8", decoded.getString("marker"));
            for (NBTBase element : elements) {
                NBTTagList empty = (NBTTagList) decoded.getTag("empty" + element.getId());
                assertEquals(0, empty.tagCount());
                assertEquals(element.getId(), empty.func_150303_d());
                assertNotSame(tag.getTag("empty" + element.getId()), empty);
            }
        }

        public static void rejectsMalformedTypedEmptyLists() {
            for (String marker : new String[] { "L", "L0", "L12", "L-1", "L01", "L1.0", "L 1", "L2147483648" }) {
                String nbt = "{\"empty\":\"" + marker + "\"}";
                assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(row("\"minecraft:stick\"", "0", "1", nbt))));
            }
            for (String nbt : new String[] { "\"L8\"", "{\"mixed\":[\"L8\",\"svalue\"]}", "{\"empty\":null}" }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(row("\"minecraft:stick\"", "0", "1", nbt))));
            }
        }

        public static void rejectsInvalidCountsMetadataRegistriesAndNbt() {
            for (String count : new String[] { "0", "-1", "9223372036854775808", "1.5", "\"1\"", "null", "true" }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(row("\"minecraft:stick\"", "0", count, "null"))));
            }
            for (String meta : new String[] { "2147483648", "-2147483649", "1.5", "\"0\"", "null" }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(row("\"minecraft:stick\"", meta, "1", "null"))));
            }
            for (String id : new String[] { "\"missing:item\"", "\"minecraft:absent\"", "\"280\"", "280", "\"stick\"", "\"minecraft:stick:extra\"" }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(row(id, "0", "1", "null"))));
            }
            for (String nbt : new String[] { "[]", "true", "{\"x\":null}", "{\"x\":\"\"}", "{\"x\":[\"i1\",\"swrong-type\"]}", "{\"x\":\"1AA==\"}" }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(row("\"minecraft:stick\"", "0", "1", nbt))));
            }
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8("[{\"item\":\"minecraft:stick\",\"meta\":0,\"amount\":1}]")));
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.encode(Collections.singletonList(new StackEntry(new ItemStack(new Item()), 1))));
            assertThrows(IllegalArgumentException.class, () -> new StackEntry(new ItemStack(Items.stick), 0));
            assertThrows(IllegalArgumentException.class, () -> new StackEntry(null, 1));
        }

        public static void boundsUtf8BytesEntriesAndDepthBeforeRecursiveParsing() {
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(new byte[BlueprintMaterialExport.MAX_BYTES + 1]));
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(new byte[] { '[', '"', (byte) 0xc3, '(', '"', ']' }));
            for (String invalid : new String[] { "[] []", "[", "[/* comment */]", "[NaN]", "{'invalid':true}" }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(invalid)));
            }
            StackEntry entry = new StackEntry(new ItemStack(Items.stick), 1);
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.encode(Collections.nCopies(BlueprintMaterialExport.MAX_ENTRIES + 1, entry)));
            String tooMany = "[" + String.join(",", Collections.nCopies(BlueprintMaterialExport.MAX_ENTRIES + 1, "null")) + "]";
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(tooMany)));
            ItemStack large = new ItemStack(Items.stick);
            NBTTagCompound tag = new NBTTagCompound();
            // Less than MAX_BYTES UTF-16 code units, more than MAX_BYTES actual UTF-8 bytes.
            tag.setString("large", String.join("", Collections.nCopies(BlueprintMaterialExport.MAX_BYTES / 2, "\u5efa")));
            large.setTagCompound(tag);
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.encode(Collections.singletonList(new StackEntry(large, 1))));
            ItemStack deep = new ItemStack(Items.stick);
            deep.setTagCompound(nested(62));
            assertEquals(deep.getTagCompound(), BlueprintMaterialExport.decode(BlueprintMaterialExport.encode(Collections.singletonList(new StackEntry(deep, 1))))
                .get(0).stack.getTagCompound());
            deep.setTagCompound(nested(63));
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.encode(Collections.singletonList(new StackEntry(deep, 1))));
            NBTTagList typedEmpty = new NBTTagList();
            typedEmpty.appendTag(new NBTTagString("removed"));
            typedEmpty.removeTag(0);
            for (int depth : new int[] { 61, 62 }) {
                NBTTagCompound root = nested(depth), child = root;
                for (int i = 1; i < depth; i++) child = child.getCompoundTag("child");
                child.setTag("empty", typedEmpty.copy());
                deep.setTagCompound(root);
                StackEntry typedEntry = new StackEntry(deep, 1);
                String typedJson = String.join("", Collections.nCopies(depth - 1, "{\"child\":")) + "{\"empty\":\"L8\"}"
                    + String.join("", Collections.nCopies(depth - 1, "}"));
                byte[] typedBytes = utf8(row("\"minecraft:stick\"", "0", "1", typedJson));
                if (depth == 61) {
                    assertEquals(root, BlueprintMaterialExport.decode(BlueprintMaterialExport.encode(Collections.singletonList(typedEntry)))
                        .get(0).stack.getTagCompound());
                    assertEquals(root, BlueprintMaterialExport.decode(typedBytes).get(0).stack.getTagCompound());
                } else {
                    assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.encode(Collections.singletonList(typedEntry)));
                    assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(typedBytes));
                }
            }
            String nested = String.join("", Collections.nCopies(1000, "[")) + "null" + String.join("", Collections.nCopies(1000, "]"));
            assertThrows(IllegalArgumentException.class, () -> BlueprintMaterialExport.decode(utf8(nested)));
        }

        public static void exportsEveryConfiguredVariantWithoutMutatingBlueprintPlayerOrResources() {
            MMState state = new MMState() {
                @Override
                public boolean hasCap(int cap) {
                    assertEquals(ItemMatterManipulator.ALLOW_COPYING, cap);
                    return true;
                }

                @Override
                public boolean connectToMESystem() {
                    throw new AssertionError("Export must not connect to AE or craft");
                }

                @Override
                public boolean connectToUplink() {
                    throw new AssertionError("Export must not connect to uplink");
                }
            };
            state.config = new MMConfig();
            state.config.arraySpan = new Vector3i(100);
            state.charge = 123456;
            MMConfig config = state.config;
            NBTTagCompound stateBefore = state.save();
            BlueprintMaterialsTest.ReadOnlyPlayer player = new BlueprintMaterialsTest.ReadOnlyPlayer(new BlueprintMaterialsTest.ReadOnlyWorld());
            ItemStack held = new ItemStack(Items.stick, 19);
            player.inventory.mainInventory[0] = held;
            NBTTagCompound heldBefore = held.writeToNBT(new NBTTagCompound());
            StructureBlueprint blueprint = new StructureBlueprint();
            blueprint.id = UUID.randomUUID().toString();
            blueprint.name = "All export pages";
            blueprint.deltas = new Vector3i(1, 0, 0);
            blueprint.blocks = new ArrayList<>();
            for (int x = 0; x < 2; x++) {
                PendingBlock block = new PendingBlock(7, x, 0, 0, new BlockSpec().setObject(Blocks.stone, 0));
                block.inventory = new InventoryAnalysis();
                block.inventory.mItems = new IItemProvider[40];
                for (int variant = 0; variant < 40; variant++) {
                    ItemStack configured = new ItemStack(Items.dye, 1, variant % 16);
                    configured.setStackDisplayName("Variant " + variant);
                    configured.getTagCompound().setInteger("variant", variant);
                    PortableItemStack portable = PortableItemStack.withNBT(configured);
                    portable.amount = Integer.MAX_VALUE;
                    block.inventory.mItems[variant] = portable;
                }
                blueprint.blocks.add(block);
            }
            String before = BlueprintCodec.encode(blueprint);
            List<StackEntry> exported = BlueprintMaterials.export(player, state, blueprint);
            assertEquals(41, exported.size());
            assertEquals(BlueprintMaterials.analyze(player, state, blueprint).size(), exported.size());
            List<StackEntry> decoded = BlueprintMaterialExport.decode(BlueprintMaterialExport.encode(exported));
            boolean[] variants = new boolean[40];
            for (StackEntry entry : decoded) {
                assertEquals(1, entry.stack.stackSize);
                if (entry.stack.getItem() == Items.dye) {
                    int variant = entry.stack.getTagCompound().getInteger("variant");
                    assertFalse(variants[variant]);
                    variants[variant] = true;
                    assertEquals(variant % 16, Items.feather.getDamage(entry.stack));
                    assertEquals(2L * Integer.MAX_VALUE, entry.amount);
                } else {
                    assertSame(Item.getItemFromBlock(Blocks.stone), entry.stack.getItem());
                    assertEquals(2, entry.amount);
                }
            }
            for (boolean present : variants) assertTrue(present);
            exported.stream().filter(entry -> entry.stack.getItem() == Items.dye).findFirst().get().stack.getTagCompound().setInteger("variant", -1);
            assertEquals(before, BlueprintCodec.encode(blueprint));
            for (PendingBlock block : blueprint.blocks) {
                for (IItemProvider provider : block.inventory.mItems) assertNull(((PortableItemStack) provider).itemStack);
            }
            assertSame(config, state.config);
            assertEquals(stateBefore, state.save());
            assertSame(held, player.inventory.mainInventory[0]);
            assertEquals(heldBefore, held.writeToNBT(new NBTTagCompound()));
            assertThrows(UnsupportedOperationException.class, exported::clear);
        }
    }
}
