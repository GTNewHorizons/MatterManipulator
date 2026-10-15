package com.recursive_pineapple.matter_manipulator.common.blueprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials.StackEntry;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;

/** Exact, registry-name-only transport for a complete material snapshot. Does not access NEI or physical files. */
public final class BlueprintMaterialExport {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ENTRIES = 65536;
    private static final int MAX_DEPTH = 64;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private BlueprintMaterialExport() {}

    public static byte[] encode(List<StackEntry> entries) {
        if (entries == null || entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Invalid material entry count");
        BoundedOutput bytes = new BoundedOutput();
        try (
            JsonWriter writer = new JsonWriter(
                new OutputStreamWriter(
                    bytes,
                    StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                )
            )
        ) {
            writer.setSerializeNulls(true);
            writer.beginArray();
            for (StackEntry entry : entries) {
                if (entry == null || entry.stack == null || entry.stack.getItem() == null || entry.amount <= 0) {
                    throw new IllegalArgumentException("Invalid material entry");
                }
                if (Item.itemRegistry.getNameForObject(entry.stack.getItem()) == null) throw new IllegalArgumentException("Unregistered material item");
                UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(entry.stack.getItem());
                if (id == null) throw new IllegalArgumentException("Unregistered material item");
                String name = id.toString();
                if (item(name) != entry.stack.getItem()) throw new IllegalArgumentException("Unregistered material item");
                // Bound recursive NBT conversion before constructing its tree, including the array/row wrappers.
                checkTagDepth(entry.stack.getTagCompound(), 3);
                writer.beginObject();
                writer.name("item").value(name);
                writer.name("meta").value(Items.feather.getDamage(entry.stack));
                writer.name("amount").value(entry.amount);
                writer.name("nbt");
                GSON.toJson(encodeTag(entry.stack.getTagCompound()), writer);
                writer.endObject();
            }
            writer.endArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid material export", e);
        } catch (RuntimeException e) {
            throw e instanceof IllegalArgumentException ? (IllegalArgumentException) e : new IllegalArgumentException("Invalid material export", e);
        }
        return bytes.toByteArray();
    }

    public static List<StackEntry> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid material export size");
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
            checkSyntax(json);
            JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonArray() || root.getAsJsonArray().size() > MAX_ENTRIES) throw new IllegalArgumentException("Invalid material entry count");
            List<StackEntry> entries = new ArrayList<>(root.getAsJsonArray().size());
            for (JsonElement value : root.getAsJsonArray()) {
                if (!value.isJsonObject()) throw new IllegalArgumentException("Expected material object");
                JsonObject row = value.getAsJsonObject();
                if (row.entrySet().size() != 4) throw new IllegalArgumentException("Invalid material fields");
                JsonElement name = required(row, "item");
                if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected item registry name");
                Item item = item(name.getAsString());
                int meta = number(required(row, "meta")).intValueExact();
                long amount = number(required(row, "amount")).longValueExact();
                if (amount <= 0) throw new IllegalArgumentException("Invalid material amount");
                JsonElement tag = required(row, "nbt");
                NBTTagCompound nbt = tag.isJsonNull() ? null : (NBTTagCompound) decodeTag(tag);
                checkTagDepth(nbt, 3);
                // The legacy scalar decoder can normalize malformed payloads. Never silently accept lost configuration.
                if (!tag.equals(encodeTag(nbt))) throw new IllegalArgumentException("Inexact material NBT");
                ItemStack stack = new ItemStack(item, 1, meta);
                stack.setTagCompound(nbt);
                entries.add(new StackEntry(stack, amount));
            }
            return Collections.unmodifiableList(entries);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid material export UTF-8", e);
        } catch (RuntimeException e) {
            throw e instanceof IllegalArgumentException ? (IllegalArgumentException) e : new IllegalArgumentException("Invalid material export", e);
        }
    }

    private static JsonElement encodeTag(NBTBase tag) {
        if (tag == null) return JsonNull.INSTANCE;
        if (tag instanceof NBTTagCompound compound) {
            JsonObject object = new JsonObject();
            for (String key : compound.func_150296_c())
                object.add(key, encodeTag(compound.getTag(key)));
            return object;
        }
        if (tag instanceof NBTTagList list) {
            // The shared adapter's [] loses the retained element type after removeTag empties a list.
            // Reserve L<type> for typed empty lists; NBT strings already carry the distinct s prefix.
            if (list.tagCount() == 0 && list.func_150303_d() != 0) {
                int type = list.func_150303_d();
                if (type < 1 || type > 11) throw new IllegalArgumentException("Invalid empty material list type");
                return new JsonPrimitive("L" + type);
            }
            JsonArray array = new JsonArray();
            for (Object child : list.tagList)
                array.add(encodeTag((NBTBase) child));
            return array;
        }
        return MMUtils.toJsonObjectExact(tag);
    }

    private static NBTBase decodeTag(JsonElement tag) {
        if (tag.isJsonObject()) {
            NBTTagCompound compound = new NBTTagCompound();
            for (var entry : tag.getAsJsonObject().entrySet())
                compound.setTag(entry.getKey(), decodeTag(entry.getValue()));
            return compound;
        }
        if (tag.isJsonArray()) {
            NBTTagList list = new NBTTagList();
            for (JsonElement child : tag.getAsJsonArray()) {
                NBTBase decoded = decodeTag(child);
                if (list.tagCount() > 0 && list.func_150303_d() != decoded.getId()) { throw new IllegalArgumentException("Mixed material list types"); }
                list.appendTag(decoded);
            }
            return list;
        }
        if (tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString() && tag.getAsString().startsWith("L")) {
            String type = tag.getAsString().substring(1);
            NBTBase element = switch (type) {
                case "1" -> new NBTTagByte((byte) 0);
                case "2" -> new NBTTagShort((short) 0);
                case "3" -> new NBTTagInt(0);
                case "4" -> new NBTTagLong(0);
                case "5" -> new NBTTagFloat(0);
                case "6" -> new NBTTagDouble(0);
                case "7" -> new NBTTagByteArray(new byte[0]);
                case "8" -> new NBTTagString("");
                case "9" -> new NBTTagList();
                case "10" -> new NBTTagCompound();
                case "11" -> new NBTTagIntArray(new int[0]);
                default -> throw new IllegalArgumentException("Invalid empty material list type");
            };
            NBTTagList list = new NBTTagList();
            list.appendTag(element);
            list.removeTag(0);
            return list;
        }
        return MMUtils.toNbtExact(tag);
    }

    private static Item item(String name) {
        int colon = name.indexOf(':');
        if (name.length() > 256 || colon <= 0 || colon == name.length() - 1 || name.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("Invalid material registry name");
        }
        Item item = GameRegistry.findItem(name.substring(0, colon), name.substring(colon + 1));
        if (item == null) throw new IllegalArgumentException("Unknown material item: " + name);
        UniqueIdentifier registered = GameRegistry.findUniqueIdentifierFor(item);
        if (registered == null || !name.equals(registered.toString())) throw new IllegalArgumentException("Unknown material item: " + name);
        return item;
    }

    private static JsonElement required(JsonObject row, String key) {
        JsonElement value = row.get(key);
        if (value == null) throw new IllegalArgumentException("Missing material " + key);
        return value;
    }

    private static BigDecimal number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected material integer");
        return new BigDecimal(value.getAsString());
    }

    private static void checkTagDepth(NBTBase tag, int depth) {
        if (tag == null) return;
        if (tag instanceof NBTTagCompound compound) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("Material NBT is nested too deeply");
            for (String key : compound.func_150296_c())
                checkTagDepth(compound.getTag(key), depth + 1);
        } else if (tag instanceof NBTTagList list) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("Material NBT is nested too deeply");
            for (Object child : list.tagList)
                checkTagDepth((NBTBase) child, depth + 1);
        }
    }

    // Streaming preflight runs before Gson's recursive parser and NBT conversion see untrusted input.
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
                    default -> throw new IllegalArgumentException("Invalid material JSON document");
                }
                if (depth > MAX_DEPTH) throw new IllegalArgumentException("Material JSON is nested too deeply");
            } while (reader.peek() != JsonToken.END_DOCUMENT);
            if (depth != 0) throw new IllegalArgumentException("Incomplete material JSON document");
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid material JSON", e);
        }
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {

        @Override
        public synchronized void write(int value) {
            checkSize(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            checkSize(length);
            super.write(data, offset, length);
        }

        private void checkSize(int length) {
            if (length > MAX_BYTES - count) throw new IllegalArgumentException("Material export exceeds " + MAX_BYTES + " decoded UTF-8 bytes");
        }
    }
}
