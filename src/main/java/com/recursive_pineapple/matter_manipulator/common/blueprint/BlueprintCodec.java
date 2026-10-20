package com.recursive_pineapple.matter_manipulator.common.blueprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.gtnewhorizon.gtnhlib.util.data.ImmutableItemMeta;
import com.gtnewhorizon.gtnhlib.util.data.ItemMeta;
import com.recursive_pineapple.matter_manipulator.common.building.BlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.CoverData;
import com.recursive_pineapple.matter_manipulator.common.building.ITileAnalysisIntegration;
import com.recursive_pineapple.matter_manipulator.common.building.ImmutableBlockSpec;
import com.recursive_pineapple.matter_manipulator.common.building.InventoryAnalysis;
import com.recursive_pineapple.matter_manipulator.common.building.PendingBlock;
import com.recursive_pineapple.matter_manipulator.common.building.PortableItemStack;
import com.recursive_pineapple.matter_manipulator.common.building.providers.IItemProvider;
import com.recursive_pineapple.matter_manipulator.common.persist.NBTJsonAdapter;
import com.recursive_pineapple.matter_manipulator.common.persist.StaticEnumJsonAdapter;

import org.joml.Vector3i;

/** Versioned JSON with closed interface types and registry-named references. Opaque integration NBT is preserved. */
public final class BlueprintCodec {

    public static final int FORMAT_VERSION = 2;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    // Copying itself is only limited by the manipulator's range. This only bounds how far a snapshot can expand.
    public static final int MAX_BLOCKS = 1 << 24;
    private static final String BUILDING = "com.recursive_pineapple.matter_manipulator.common.building.";

    // Class names are fixed here, never accepted from JSON. Resolve only the selected implementation, after its mod check.
    private static final Gson GSON = new GsonBuilder()
        .setExclusionStrategies(new ExclusionStrategy() {

            @Override
            public boolean shouldSkipField(FieldAttributes field) {
                // Gson prepares adapters even for null fields. Do not reflect an unavailable optional integration.
                return field.getDeclaringClass() == BlockSpec.class && field.getName().equals("arch") && !Loader.isModLoaded("ArchitectureCraft");
            }

            @Override
            public boolean shouldSkipClass(Class<?> type) {
                return false;
            }
        })
        .registerTypeAdapter(UniqueIdentifier.class, new RegistryNameAdapter())
        .registerTypeAdapter(NBTTagCompound.class, new NBTJsonAdapter())
        .registerTypeAdapter(ForgeDirection.class, new StaticEnumJsonAdapter<>(ForgeDirection.class))
        .registerTypeAdapter(ImmutableBlockSpec.class, new BlockSpecAdapter())
        .registerTypeAdapter(InventoryAnalysis.class, new InventoryAdapter())
        .registerTypeAdapter(CoverData.class, new CoverAdapter())
        .registerTypeAdapter(PortableItemStack.class, new PortableStackAdapter())
        .registerTypeAdapter(ItemStack.class, new ItemStackAdapter())
        .registerTypeAdapter(ImmutableItemMeta.class, new ItemMetaAdapter())
        .registerTypeAdapter(FluidStack.class, new FluidStackAdapter())
        .registerTypeAdapter(ITileAnalysisIntegration.class, new WhitelistAdapter<ITileAnalysisIntegration>(new String[][] {
            {
                "gt", BUILDING + "GTAnalysisResult", "gregtech"
            },
            {
                "ae", BUILDING + "AEAnalysisResult", "appliedenergistics2"
            },
            {
                "arch", BUILDING + "ArchitectureCraftAnalysisResult", "ArchitectureCraft"
            },
            {
                "mp", BUILDING + "MultipartAnalysisResult", "ForgeMultipart"
            },
            {
                "cb", BUILDING + "CarpentersBlocksAnalysisResult", "CarpentersBlocks"
            }
        }))
        .registerTypeAdapter(IItemProvider.class, new WhitelistAdapter<IItemProvider>(new String[][] {
            {
                "stack", BUILDING + "PortableItemStack", ""
            },
            {
                "battery", BUILDING + "providers.BatteryItemProvider", ""
            },
            {
                "cell", BUILDING + "providers.AECellItemProvider", "appliedenergistics2"
            },
            {
                "pattern", BUILDING + "providers.PatternItemProvider", "appliedenergistics2"
            },
            {
                "component", BUILDING + "providers.ComputerComponentItemProvider", "OpenComputers"
            }
        }))
        .create();

    private BlueprintCodec() {}

    public static String encode(StructureBlueprint blueprint) {
        try {
            JsonObject tree = toTree(blueprint);
            String json = GSON.toJson(tree);
            checkSize(json);
            checkSyntax(json);
            return json;
        } catch (RuntimeException e) {
            throw invalid(e);
        }
    }

    public static StructureBlueprint decode(String json) {
        try {
            checkSize(json);
            checkSyntax(json);
            JsonObject tree = object(new JsonParser().parse(json));
            int version = integer(required(tree, "formatVersion"));
            StructureBlueprint blueprint;
            if (version == 1) {
                blueprint = decodeLegacy(tree);
            } else if (version == FORMAT_VERSION) {
                blueprint = decodeCompact(tree);
            } else {
                throw new IllegalArgumentException("Unsupported blueprint format version");
            }
            blueprint.formatVersion = FORMAT_VERSION;
            validate(blueprint);
            for (PendingBlock block : blueprint.blocks)
                block.worldId = 0;
            return blueprint;
        } catch (RuntimeException e) {
            throw invalid(e);
        }
    }

    /** Version 1 stored every block as a full object with its coordinates. */
    private static StructureBlueprint decodeLegacy(JsonObject tree) {
        validateHeader(tree);
        JsonElement blocks = required(tree, "blocks");
        if (!blocks.isJsonArray() || blocks.getAsJsonArray().size() > MAX_BLOCKS) throw new IllegalArgumentException("Invalid blocks array");
        for (JsonElement element : blocks.getAsJsonArray()) {
            JsonObject block = object(element);
            integer(required(block, "x"));
            integer(required(block, "y"));
            integer(required(block, "z"));
            validateBlock(block);
        }
        // Apply this on read too: pre-fix files and cached JSON must not restore old quantum links.
        stripQuantumBridgeData(tree);
        // Full snapshots are authoritative; older version-1 files may not contain this derived field.
        tree.addProperty("blockCount", blocks.getAsJsonArray().size());
        return GSON.fromJson(tree, StructureBlueprint.class);
    }

    /** Version 2 stores each distinct block once in a palette, and the region as run-length encoded palette indices. */
    private static StructureBlueprint decodeCompact(JsonObject tree) {
        validateHeader(tree);
        StructureBlueprint blueprint = new StructureBlueprint();
        blueprint.id = string(required(tree, "id"));
        blueprint.name = string(required(tree, "name"));
        blueprint.deltas = GSON.fromJson(object(required(tree, "deltas")), Vector3i.class);
        Region region = new Region(blueprint.deltas);

        JsonElement palette = required(tree, "palette");
        if (!palette.isJsonArray() || palette.getAsJsonArray().size() > region.volume) throw new IllegalArgumentException("Invalid block palette");
        for (JsonElement element : palette.getAsJsonArray())
            validateBlock(object(element));
        stripQuantumBridgeData(tree);

        List<PendingBlock> prototypes = new ArrayList<>(palette.getAsJsonArray().size());
        for (JsonElement element : palette.getAsJsonArray()) {
            PendingBlock prototype = GSON.fromJson(element, PendingBlock.class);
            if (prototype == null || prototype.spec == null) throw new IllegalArgumentException("Missing block specification");
            prototypes.add(prototype);
        }

        blueprint.blocks = region.expand(string(required(tree, "voxels")), prototypes);
        blueprint.blockCount = blueprint.blocks.size();
        return blueprint;
    }

    static StructureBlueprint portableCopy(StructureBlueprint blueprint) {
        try {
            JsonObject tree = toTree(blueprint);
            stripSourceLinks(tree);
            return decode(GSON.toJson(tree));
        } catch (RuntimeException e) {
            throw invalid(e);
        }
    }

    private static JsonObject toTree(StructureBlueprint blueprint) {
        validate(blueprint);
        Region region = new Region(blueprint.deltas);
        int[] cells = new int[region.volume];
        JsonArray palette = new JsonArray();
        Map<String, Integer> byJson = new HashMap<>();
        // Most blocks are plain (air, casings, ...): find their entry without serializing each one.
        Map<List<Object>, Integer> byPlainSpec = new HashMap<>();
        for (PendingBlock block : blueprint.blocks) {
            List<Object> plainKey = isPlain(block) ? Arrays.asList(block.spec, block.renderOrder, block.buildOrder) : null;
            Integer index = plainKey == null ? null : byPlainSpec.get(plainKey);
            if (index == null) {
                JsonObject entry = GSON.toJsonTree(block, PendingBlock.class).getAsJsonObject();
                // Positions are implied by the cell order; source dimensions have no meaning in a relative snapshot.
                for (String key : new String[] {
                    "x", "y", "z", "worldId"
                })
                    entry.remove(key);
                validateBlock(entry);
                String key = entry.toString();
                index = byJson.get(key);
                if (index == null) {
                    index = palette.size();
                    palette.add(entry);
                    byJson.put(key, index);
                }
                if (plainKey != null) byPlainSpec.put(plainKey, index);
            }
            cells[region.index(block.x, block.y, block.z)] = index + 1;
        }

        JsonObject tree = new JsonObject();
        tree.addProperty("formatVersion", FORMAT_VERSION);
        tree.addProperty("id", blueprint.id);
        tree.addProperty("name", blueprint.name);
        tree.add("deltas", GSON.toJsonTree(blueprint.deltas));
        tree.addProperty("blockCount", blueprint.blocks.size());
        tree.add("palette", palette);
        tree.addProperty("voxels", encodeCells(cells));
        stripQuantumBridgeData(tree);
        return tree;
    }

    private static boolean isPlain(PendingBlock block) {
        return block.gt == null && block.ae == null &&
            block.arch == null &&
            block.mp == null &&
            block.cb == null &&
            block.inventory == null &&
            block.smartCopy == null;
    }

    static void validate(StructureBlueprint blueprint) {
        if (blueprint == null) throw new IllegalArgumentException("Missing blueprint");
        validateId(blueprint.id);
        validateName(blueprint.name);
        if (blueprint.formatVersion != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported blueprint format version");
        if (blueprint.deltas == null || blueprint.blocks == null) throw new IllegalArgumentException("Missing region data");
        Region region = new Region(blueprint.deltas);
        if (blueprint.blocks.size() > MAX_BLOCKS) throw new IllegalArgumentException("Too many blueprint blocks");
        BitSet positions = new BitSet(region.volume);
        for (PendingBlock block : blueprint.blocks) {
            if (block == null || block.spec == null) throw new IllegalArgumentException("Missing block or block specification");
            if (block.smartCopy != null) throw new IllegalArgumentException("Capture raw analysis before SmartCopy processing");
            int index = region.index(block.x, block.y, block.z);
            if (positions.get(index)) throw new IllegalArgumentException("Duplicate block position");
            positions.set(index);
        }
    }

    /**
     * The signed region deltas as a box of cells, ordered like BlockAnalyzer.analyzeRegion visits them: x fastest,
     * then z, then y. The cell stream is (palette index + 1, run length) pairs of unsigned varints; 0 means no block.
     */
    private static final class Region {

        private final int minX, minY, minZ, sizeX, sizeY, sizeZ, volume;

        private Region(Vector3i deltas) {
            volume = (int) volume(deltas.x, deltas.y, deltas.z);
            minX = Math.min(0, deltas.x);
            minY = Math.min(0, deltas.y);
            minZ = Math.min(0, deltas.z);
            sizeX = Math.abs(deltas.x) + 1;
            sizeY = Math.abs(deltas.y) + 1;
            sizeZ = Math.abs(deltas.z) + 1;
        }

        private int index(int x, int y, int z) {
            int cx = x - minX, cy = y - minY, cz = z - minZ;
            if (cx < 0 || cx >= sizeX || cy < 0 || cy >= sizeY || cz < 0 || cz >= sizeZ) {
                throw new IllegalArgumentException("Block lies outside the signed region deltas");
            }
            return (cy * sizeZ + cz) * sizeX + cx;
        }

        private List<PendingBlock> expand(String voxels, List<PendingBlock> prototypes) {
            byte[] data;
            try {
                data = Base64.getDecoder().decode(voxels);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid blueprint voxel data", e);
            }
            List<PendingBlock> blocks = new ArrayList<>();
            int[] position = new int[1];
            int cell = 0;
            while (position[0] < data.length) {
                int value = readVarInt(data, position);
                int run = readVarInt(data, position);
                if (value > prototypes.size() || run < 1 || run > volume - cell) throw new IllegalArgumentException("Invalid blueprint voxel run");
                if (value > 0) {
                    PendingBlock prototype = prototypes.get(value - 1);
                    for (int i = cell; i < cell + run; i++) {
                        PendingBlock block = prototype.clone();
                        block.x = minX + i % sizeX;
                        block.z = minZ + i / sizeX % sizeZ;
                        block.y = minY + i / sizeX / sizeZ;
                        blocks.add(block);
                    }
                }
                cell += run;
            }
            if (cell != volume) throw new IllegalArgumentException("Blueprint voxel data does not cover the region");
            return blocks;
        }
    }

    private static String encodeCells(int[] cells) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int start = 0; start < cells.length;) {
            int end = start + 1;
            while (end < cells.length && cells[end] == cells[start])
                end++;
            writeVarInt(out, cells[start]);
            writeVarInt(out, end - start);
            start = end;
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(byte[] data, int[] position) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (position[0] >= data.length) throw new IllegalArgumentException("Truncated blueprint voxel data");
            byte b = data[position[0]++];
            // The fifth byte holds only the top 4 bits of an int.
            if (shift == 28 && (b & 0xF0) != 0) throw new IllegalArgumentException("Invalid blueprint voxel data");
            value |= (b & 0x7F) << shift;
            if (b >= 0) return value;
        }
        throw new IllegalArgumentException("Invalid blueprint voxel data");
    }

    static long volume(int x, int y, int z) {
        long volume = 1;
        for (int delta : new int[] {
            x, y, z
        }) {
            long length = Math.abs((long) delta) + 1;
            if (length > MAX_BLOCKS / volume) throw new IllegalArgumentException("Blueprint volume exceeds " + MAX_BLOCKS);
            volume *= length;
        }
        return volume;
    }

    static String validateId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) { throw new IllegalArgumentException("Blueprint ID must be a canonical UUID"); }
        return id;
    }

    static void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 64) throw new IllegalArgumentException("Blueprint name must contain 1-64 characters");
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("Control characters in blueprint name");
        }
    }

    private static void validateHeader(JsonObject tree) {
        validateId(string(required(tree, "id")));
        validateName(string(required(tree, "name")));
        JsonObject deltas = object(required(tree, "deltas"));
        volume(integer(required(deltas, "x")), integer(required(deltas, "y")), integer(required(deltas, "z")));
    }

    private static void validateBlock(JsonObject block) {
        object(required(block, "spec"));
        for (String field : new String[] {
            "gt", "ae", "arch", "mp", "cb"
        }) {
            JsonElement integration = block.get(field);
            if (integration != null && !integration.isJsonNull() && !field.equals(string(required(object(integration), "type")))) {
                throw new IllegalArgumentException("Wrong integration type for " + field);
            }
        }
        if (block.has("smartCopy")) throw new IllegalArgumentException("SmartCopy is not portable");
    }

    /** The per-block objects of a snapshot tree: every block in version 1, the palette in the compact format. */
    private static JsonArray blockObjects(JsonObject tree) {
        boolean legacy = tree.has("formatVersion") && integer(tree.get("formatVersion")) == 1;
        JsonElement blocks = required(tree, legacy ? "blocks" : "palette");
        if (!blocks.isJsonArray()) throw new IllegalArgumentException("Invalid blocks array");
        return blocks.getAsJsonArray();
    }

    static void stripQuantumBridgeData(JsonObject tree) {
        for (JsonElement element : blockObjects(tree)) {
            JsonObject block = object(element);
            String id = string(required(object(required(block, "spec")), "id"));
            if (!"appliedenergistics2:tile.BlockQuantumRing".equals(id) && !"appliedenergistics2:tile.BlockQuantumLinkChamber".equals(id)) continue;

            // Every bridge tile has an inventory, even ring blocks. Its singularity carries the source link identity.
            // Omit the plan entirely rather than clearing slots: existing destination contents must remain untouched.
            block.remove("inventory");
            JsonObject ae = integrationData(block, "ae");
            if (ae != null) {
                // Bridge names/settings describe the installed singularity; renaming it can rename its remote partner.
                ae.remove("mAECustomName");
                ae.remove("mAEConfig");
            }
        }
    }

    /** Operates only on known integration fields, never recursively on opaque item or pattern data. */
    static void stripSourceLinks(JsonElement tree) {
        List<JsonObject> parts = new ArrayList<>();
        Map<Long, Integer> roles = new LinkedHashMap<>();
        for (JsonElement element : blockObjects(object(tree))) {
            JsonObject block = object(element);
            JsonObject gt = integrationData(block, "gt");
            // IDataCopyable has no relocation schema; dropping it also loses otherwise portable GT machine settings.
            if (gt != null) gt.remove("mGTData");
            JsonObject ae = integrationData(block, "ae");
            if (ae == null) continue;
            stripAESourceSettings(ae.get("mAEConfig"));
            JsonElement entries = ae.get("mAEParts");
            if (entries == null || entries.isJsonNull()) continue;
            for (JsonElement entry : entries.getAsJsonArray()) {
                if (entry.isJsonNull()) continue;
                JsonObject part = object(entry);
                stripAESourceSettings(part.get("mPartSettings"));
                parts.add(part);
                long frequency = p2pFrequency(part);
                if (frequency == 0) continue;
                int role = part.has("mP2POutput") && part.get("mP2POutput").getAsBoolean() ? 2 : 1;
                roles.put(frequency, roles.getOrDefault(frequency, 0) | role);
            }
        }
        Map<Long, Long> tokens = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> group : roles.entrySet()) {
            if (group.getValue() == 3) tokens.put(group.getKey(), (long) tokens.size() + 1);
        }
        for (JsonObject part : parts) {
            // Local tokens express topology only. The caller must allocate fresh destination frequencies per build.
            part.addProperty("mP2PFreq", tokens.getOrDefault(p2pFrequency(part), 0L));
        }
    }

    private static JsonObject integrationData(JsonObject block, String type) {
        JsonElement value = block.get(type);
        if (value == null || value.isJsonNull()) return null;
        JsonObject integration = object(value);
        if (!type.equals(string(required(integration, "type")))) throw new IllegalArgumentException("Wrong integration type for " + type);
        return object(required(integration, "data"));
    }

    private static long p2pFrequency(JsonObject part) {
        JsonElement frequency = part.get("mP2PFreq");
        return frequency == null || frequency.isJsonNull() ? 0 : frequency.getAsLong();
    }

    private static void stripAESourceSettings(JsonElement settings) {
        if (settings == null || settings.isJsonNull()) return;
        JsonObject data = object(settings);
        // These are AE world-NBT identities/absolute targets, normally absent from downloadSettings(MEMORY_CARD).
        // Relative snapshot coordinates cannot identify internal wireless targets, so no absolute targets are retained.
        data.remove("securityKey");
        data.remove("serial");
        data.remove("connectedTargets");
    }

    private static void checkSize(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Blueprint exceeds " + MAX_BYTES + " decoded UTF-8 bytes");
        }
    }

    // Bound nesting BEFORE Gson's recursive tree/reflection readers see untrusted input.
    private static void checkSyntax(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            int depth = 0;
            do {
                JsonToken token = reader.peek();
                switch (token) {
                    case BEGIN_ARRAY -> { reader.beginArray(); depth++; }
                    case BEGIN_OBJECT -> { reader.beginObject(); depth++; }
                    case END_ARRAY -> { reader.endArray(); depth--; }
                    case END_OBJECT -> { reader.endObject(); depth--; }
                    case NAME -> reader.nextName();
                    case STRING, NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    default -> throw new IllegalArgumentException("Invalid JSON document");
                }
                if (depth > 64) throw new IllegalArgumentException("Blueprint JSON is nested too deeply");
            } while (reader.peek() != JsonToken.END_DOCUMENT);
            if (depth != 0) throw new IllegalArgumentException("Incomplete JSON document");
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid blueprint JSON", e);
        }
    }

    private static IllegalArgumentException invalid(RuntimeException e) {
        return e instanceof IllegalArgumentException ? (IllegalArgumentException) e : new IllegalArgumentException("Invalid blueprint data", e);
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected JSON object");
        return value.getAsJsonObject();
    }

    private static JsonElement required(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static String string(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        return value.getAsString();
    }

    private static int integer(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Integer out of range", e);
        }
    }

    private abstract static class Adapter<T> implements JsonSerializer<T>, JsonDeserializer<T> {}

    private static final class RegistryNameAdapter extends Adapter<UniqueIdentifier> {

        @Override
        public JsonElement serialize(UniqueIdentifier src, Type type, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public UniqueIdentifier deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            String name = string(json);
            int colon = name.indexOf(':');
            if (colon <= 0 || colon == name.length() - 1 || name.indexOf(':', colon + 1) >= 0) throw new IllegalArgumentException("Invalid registry name");
            return new UniqueIdentifier(name);
        }
    }

    private static final class BlockSpecAdapter extends Adapter<ImmutableBlockSpec> {

        @Override
        public JsonElement serialize(ImmutableBlockSpec src, Type type, JsonSerializationContext context) {
            if (src.getClass() != BlockSpec.class) throw new IllegalArgumentException("Unsupported block specification");
            JsonObject data = context.serialize(src, BlockSpec.class).getAsJsonObject();
            check(data);
            return data;
        }

        private void check(JsonObject data) {
            UniqueIdentifier id = new RegistryNameAdapter().deserialize(required(data, "id"), UniqueIdentifier.class, null);
            JsonElement block = required(data, "b");
            if (!block.isJsonPrimitive() || !block.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Missing block/item discriminator");
            integer(required(data, "m"));
            boolean missing = block.getAsBoolean() ? GameRegistry.findBlock(id.modId, id.name) == null : GameRegistry.findItem(id.modId, id.name) == null;
            if (missing) throw new IllegalArgumentException("Missing registered block/item: " + id);
        }

        @Override
        public ImmutableBlockSpec deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            JsonObject data = object(json);
            check(data);
            if (data.has("a") && !data.get("a").isJsonNull() && !Loader.isModLoaded("ArchitectureCraft")) {
                throw new IllegalArgumentException("Blueprint requires ArchitectureCraft");
            }
            return context.deserialize(data, BlockSpec.class);
        }
    }

    private static final class CoverAdapter extends Adapter<CoverData> {

        @Override
        public JsonElement serialize(CoverData src, Type type, JsonSerializationContext context) {
            if (src.cover == null) throw new IllegalArgumentException("Missing cover item");
            JsonObject data = new JsonObject();
            data.add("cover", context.serialize(src.cover, PortableItemStack.class));
            data.add("coverData", context.serialize(src.coverData == null ? new NBTTagCompound() : src.coverData, NBTTagCompound.class));
            return data;
        }

        @Override
        public CoverData deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            JsonObject data = object(json);
            PortableItemStack cover = context.deserialize(required(data, "cover"), PortableItemStack.class);
            NBTTagCompound settings = context.deserialize(required(data, "coverData"), NBTTagCompound.class);
            // GT's cover ID is stackToInt(item), a runtime registry ID. Rebuild it; never persist it.
            int runtimeId = Item.getIdFromItem(cover.getItem()) | (cover.getMeta() << 16);
            return new CoverData(cover, runtimeId, settings);
        }
    }

    private static final class InventoryAdapter extends Adapter<InventoryAnalysis> {

        @Override
        public JsonElement serialize(InventoryAnalysis src, Type type, JsonSerializationContext context) {
            if (src.mItems == null) throw new IllegalArgumentException("Missing inventory slots");
            JsonObject data = new JsonObject();
            data.addProperty("mFuzzy", src.mFuzzy);
            JsonArray items = new JsonArray();
            for (IItemProvider item : src.mItems) {
                // An explicit declared type avoids Gson replacing the interface wrapper with a concrete stack adapter.
                items.add(item == null ? JsonNull.INSTANCE : context.serialize(item, IItemProvider.class));
            }
            data.add("mItems", items);
            return data;
        }

        @Override
        public InventoryAnalysis deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            JsonObject data = object(json);
            JsonElement items = required(data, "mItems");
            if (!items.isJsonArray()) throw new IllegalArgumentException("Expected inventory slots array");
            InventoryAnalysis inventory = new InventoryAnalysis();
            JsonElement fuzzy = required(data, "mFuzzy");
            if (!fuzzy.isJsonPrimitive() || !fuzzy.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected inventory fuzzy flag");
            inventory.mFuzzy = fuzzy.getAsBoolean();
            inventory.mItems = new IItemProvider[items.getAsJsonArray().size()];
            for (int i = 0; i < inventory.mItems.length; i++) {
                JsonElement item = items.getAsJsonArray().get(i);
                inventory.mItems[i] = item.isJsonNull() ? null : context.deserialize(item, IItemProvider.class);
            }
            return inventory;
        }
    }

    private static final class PortableStackAdapter extends Adapter<PortableItemStack> {

        @Override
        public JsonElement serialize(PortableItemStack src, Type type, JsonSerializationContext context) {
            checkItem(src.item);
            JsonObject data = new JsonObject();
            data.add("id", context.serialize(src.item, UniqueIdentifier.class));
            if (src.metadata != null) data.addProperty("m", src.metadata);
            if (src.amount != null) data.addProperty("a", src.amount);
            if (src.nbt != null) data.add("nbt", context.serialize(src.nbt, NBTTagCompound.class));
            return data;
        }

        @Override
        public PortableItemStack deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            JsonObject data = object(json);
            PortableItemStack stack = new PortableItemStack();
            stack.item = context.deserialize(required(data, "id"), UniqueIdentifier.class);
            checkItem(stack.item);
            stack.metadata = data.has("m") ? integer(required(data, "m")) : null;
            stack.amount = data.has("a") ? integer(required(data, "a")) : null;
            stack.nbt = context.deserialize(data.get("nbt"), NBTTagCompound.class);
            return stack;
        }
    }

    private static void checkItem(UniqueIdentifier id) {
        if (id == null || GameRegistry.findItem(id.modId, id.name) == null) throw new IllegalArgumentException("Missing registered item: " + id);
    }

    private static final class ItemStackAdapter extends Adapter<ItemStack> {

        @Override
        public JsonElement serialize(ItemStack src, Type type, JsonSerializationContext context) {
            return context.serialize(PortableItemStack.withNBT(src), PortableItemStack.class);
        }

        @Override
        public ItemStack deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            return context.<PortableItemStack>deserialize(json, PortableItemStack.class).toStack();
        }
    }

    private static final class ItemMetaAdapter extends Adapter<ImmutableItemMeta> {

        @Override
        public JsonElement serialize(ImmutableItemMeta src, Type type, JsonSerializationContext context) {
            return context.serialize(new PortableItemStack(src.getItem(), src.getItemMeta()), PortableItemStack.class);
        }

        @Override
        public ImmutableItemMeta deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            PortableItemStack stack = context.deserialize(json, PortableItemStack.class);
            return new ItemMeta(stack.getItem(), stack.getMeta());
        }
    }

    private static final class FluidStackAdapter extends Adapter<FluidStack> {

        @Override
        public JsonElement serialize(FluidStack src, Type type, JsonSerializationContext context) {
            String name = FluidRegistry.getFluidName(src);
            if (name == null) throw new IllegalArgumentException("Missing registered fluid");
            JsonObject data = new JsonObject();
            data.addProperty("fluid", name);
            data.addProperty("amount", src.amount);
            if (src.tag != null) data.add("nbt", context.serialize(src.tag, NBTTagCompound.class));
            return data;
        }

        @Override
        public FluidStack deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            JsonObject data = object(json);
            String name = string(required(data, "fluid"));
            if (FluidRegistry.getFluid(name) == null) throw new IllegalArgumentException("Missing registered fluid: " + name);
            return new FluidStack(FluidRegistry.getFluid(name), integer(required(data, "amount")), context.deserialize(data.get("nbt"), NBTTagCompound.class));
        }
    }

    private static final class WhitelistAdapter<T> extends Adapter<T> {

        private final String[][] entries;

        private WhitelistAdapter(String[][] entries) {
            this.entries = entries;
        }

        @Override
        public JsonElement serialize(T src, Type type, JsonSerializationContext context) {
            for (String[] entry : entries) {
                if (entry[1].equals(src.getClass().getName())) {
                    JsonObject wrapper = new JsonObject();
                    wrapper.addProperty("type", entry[0]);
                    wrapper.add("data", context.serialize(src, src.getClass()));
                    return wrapper;
                }
            }
            throw new IllegalArgumentException("Unsupported blueprint implementation: " + src.getClass().getName());
        }

        @Override
        public T deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            JsonObject wrapper = object(json);
            String tag = string(required(wrapper, "type"));
            for (String[] entry : entries) {
                if (!entry[0].equals(tag)) continue;
                if (!entry[2].isEmpty() && !Loader.isModLoaded(entry[2])) throw new IllegalArgumentException("Blueprint requires mod " + entry[2]);
                try {
                    return context.deserialize(object(required(wrapper, "data")), Class.forName(entry[1], false, BlueprintCodec.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    throw new JsonParseException("Blueprint integration is unavailable: " + tag, e);
                }
            }
            throw new IllegalArgumentException("Unknown blueprint implementation: " + tag);
        }
    }
}
