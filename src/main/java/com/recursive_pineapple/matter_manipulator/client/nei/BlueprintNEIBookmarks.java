package com.recursive_pineapple.matter_manipulator.client.nei;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Optional;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials.StackEntry;
import com.recursive_pineapple.matter_manipulator.common.utils.ItemId;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

/** Client-thread export into NEI's stored bookmarks; does not require or change the current screen. */
@SideOnly(Side.CLIENT)
public final class BlueprintNEIBookmarks {

    private static final String PREFIX = "mm.blueprint.bookmarks.";
    private static final int MAX_ENTRIES = 65536;

    private BlueprintNEIBookmarks() {}

    public static boolean isAvailable() {
        try {
            return Loader.isModLoaded(Names.NOT_ENOUGH_ITEMS) && availableNEI();
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Validates the entire export first. Returns the number of distinct identities added in one new group. */
    public static int add(List<StackEntry> entries) {
        try {
            List<StackEntry> prepared = prepare(entries);
            if (!Loader.isModLoaded(Names.NOT_ENOUGH_ITEMS)) throw new ExportException("unavailable");
            return addNEI(prepared);
        } catch (ExportException e) {
            throw e;
        } catch (LinkageError e) {
            throw new ExportException("unavailable", e);
        } catch (RuntimeException e) {
            MMMod.LOG.error("Could not add blueprint requirements to NEI bookmarks", e);
            throw new ExportException("failed", e);
        }
    }

    static List<StackEntry> prepare(List<StackEntry> entries) {
        if (entries == null || entries.isEmpty()) throw new ExportException("empty");
        if (entries.size() > MAX_ENTRIES) throw new ExportException("failed");

        Map<ItemId, Long> amounts = new LinkedHashMap<>();
        for (StackEntry entry : entries) {
            if (entry == null || entry.stack == null || entry.stack.getItem() == null || entry.amount <= 0) { throw new ExportException("failed"); }
            if (entry.amount > Integer.MAX_VALUE) throw new ExportException("too_large");
            Item item = entry.stack.getItem();
            String name = Item.itemRegistry.getNameForObject(item);
            if (name == null || Item.itemRegistry.getObject(name) != item) throw new ExportException("failed");

            ItemId identity = ItemId.create(entry.stack);
            long amount;
            try {
                amount = Math.addExact(amounts.getOrDefault(identity, 0L), entry.amount);
            } catch (ArithmeticException e) {
                throw new ExportException("too_large", e);
            }
            // NEI 2.8.160 writes long multipliers, but reloads them using JsonElement.getAsInt().
            if (amount > Integer.MAX_VALUE) throw new ExportException("too_large");
            amounts.put(identity, amount);
        }

        List<StackEntry> prepared = new ArrayList<>(amounts.size());
        for (var entry : amounts.entrySet()) {
            prepared.add(new StackEntry(entry.getKey().getItemStack(), entry.getValue()));
        }
        return prepared;
    }

    @Optional.Method(modid = Names.NOT_ENOUGH_ITEMS)
    private static boolean availableNEI() {
        return NEIImplementation.available();
    }

    @Optional.Method(modid = Names.NOT_ENOUGH_ITEMS)
    private static int addNEI(List<StackEntry> entries) {
        return NEIImplementation.add(entries);
    }

    public static final class ExportException extends RuntimeException {

        public final String translationKey;

        private ExportException(String key) {
            this(key, null);
        }

        private ExportException(String key, Throwable cause) {
            super(PREFIX + key, cause);
            translationKey = PREFIX + key;
        }
    }

    /** Loaded only through the guarded optional bridges; no NEI types occur in the outer class's signatures. */
    private static final class NEIImplementation {

        private static final Field TASK_THREAD;
        private static final Field PANEL_STORAGE;
        private static final Field STORAGE_FILE;

        static {
            try {
                TASK_THREAD = field(codechicken.nei.RestartableTask.class, "thread");
                PANEL_STORAGE = field(codechicken.nei.BookmarkPanel.class, "storage");
                STORAGE_FILE = field(codechicken.nei.bookmark.BookmarkStorage.class, "bookmarkFile");
                // Check every mutation entry point before isAvailable can advertise support for an older jar.
                codechicken.nei.BookmarkPanel.class.getMethod("load");
                codechicken.nei.BookmarkPanel.class.getMethod("update");
                codechicken.nei.BookmarkPanel.class.getMethod("save");
                codechicken.nei.BookmarkPanel.BookmarkViewMode.class.getField("TODO_LIST");
                codechicken.nei.bookmark.BookmarkGrid.class.getMethod("getNextGroupId");
                codechicken.nei.bookmark.BookmarkGrid.class.getMethod("addGroup", codechicken.nei.bookmark.BookmarkGroup.class);
                codechicken.nei.bookmark.BookmarkGrid.class.getMethod("addItem", codechicken.nei.bookmark.BookmarkItem.class, boolean.class);
                codechicken.nei.bookmark.BookmarkGroup.class.getConstructor(codechicken.nei.BookmarkPanel.BookmarkViewMode.class, boolean.class);
                codechicken.nei.bookmark.BookmarkItem.class.getMethod("builder", int.class, ItemStack.class);
                codechicken.nei.bookmark.BookmarkItem.Builder.class.getMethod("factor", long.class);
                codechicken.nei.bookmark.BookmarkItem.Builder.class.getMethod("multiplier", long.class);
                codechicken.nei.bookmark.BookmarkItem.Builder.class.getMethod("build");
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static boolean available() {
            synchronized (codechicken.nei.ItemListLoader.loadItems) {
                synchronized (codechicken.nei.ItemListLoader.loadBookmarks) {
                    return ready();
                }
            }
        }

        private static boolean ready() {
            Minecraft mc = Minecraft.getMinecraft();
            if (
                mc == null || mc.theWorld == null ||
                    mc.thePlayer == null ||
                    !codechicken.nei.NEIClientConfig.isLoaded() ||
                    codechicken.nei.NEIClientConfig.world == null ||
                    !codechicken.nei.ItemList.loadFinished
            ) return false;

            try {
                // loadFinished is set before loadBookmarks is scheduled. Both task monitors/threads are needed.
                if (
                    TASK_THREAD.get(codechicken.nei.ItemListLoader.loadItems) != null ||
                        TASK_THREAD.get(codechicken.nei.ItemListLoader.loadBookmarks) != null
                ) return false;
                var panel = codechicken.nei.ItemPanels.bookmarkPanel;
                if (panel == null || panel.namespaceLabel == null || panel.getGrid() == null) return false;
                var storage = (codechicken.nei.bookmark.BookmarkStorage) PANEL_STORAGE.get(panel);
                if (storage == null || storage.getActiveGrid() == null) return false;
                String namespace = codechicken.nei.NEIClientConfig.getBooleanSetting("inventory.bookmarks.worldSpecific") ?
                    codechicken.nei.NEIClientConfig.getWorldPath() :
                    "global";
                if (namespace == null) return false;
                File expected = new File(mc.mcDataDir, "saves/NEI/" + namespace + "/bookmarks.ini");
                return expected.equals(STORAGE_FILE.get(storage));
            } catch (IllegalAccessException e) {
                return false;
            }
        }

        private static int add(List<StackEntry> entries) {
            synchronized (codechicken.nei.ItemListLoader.loadItems) {
                synchronized (codechicken.nei.ItemListLoader.loadBookmarks) {
                    if (!ready()) throw new ExportException("unavailable");
                    var panel = codechicken.nei.ItemPanels.bookmarkPanel;
                    // ready() proves this path is already loaded, so load() cannot replace the active storage.
                    panel.load();
                    panel.update();
                    var grid = panel.getGrid();
                    int groupId = grid.getNextGroupId();
                    List<codechicken.nei.bookmark.BookmarkItem> items = new ArrayList<>(entries.size());
                    for (StackEntry entry : entries) {
                        items.add(
                            codechicken.nei.bookmark.BookmarkItem.builder(groupId, entry.stack.copy())
                                .factor(1L)
                                .multiplier(entry.amount)
                                .build()
                        );
                    }
                    grid.addGroup(new codechicken.nei.bookmark.BookmarkGroup(codechicken.nei.BookmarkPanel.BookmarkViewMode.TODO_LIST, false));
                    for (var item : items)
                        grid.addItem(item, false);
                    panel.save();
                    return items.size();
                }
            }
        }
    }
}
