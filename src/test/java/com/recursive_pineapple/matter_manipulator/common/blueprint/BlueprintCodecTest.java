package com.recursive_pineapple.matter_manipulator.common.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.RegionAnalysis;

import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

class BlueprintCodecTest {

    static StructureBlueprint emptyBlueprint() {
        StructureBlueprint blueprint = new StructureBlueprint();
        blueprint.id = UUID.randomUUID().toString();
        blueprint.name = "Test blueprint";
        blueprint.deltas = new Vector3i(-2, 1, 0);
        blueprint.blocks = new ArrayList<>();
        return blueprint;
    }

    @Test
    void roundTripPreservesSignedDeltasAndVersion() {
        StructureBlueprint source = emptyBlueprint();
        StructureBlueprint copy = BlueprintCodec.decode(BlueprintCodec.encode(source));
        assertEquals(source.id, copy.id);
        assertEquals(source.name, copy.name);
        assertEquals(source.deltas, copy.deltas);
        assertEquals(1, copy.formatVersion);
        assertNotSame(source.deltas, copy.deltas);
        assertNotSame(source.blocks, copy.blocks);
    }

    @Test
    void captureCreatesFreshIdsAndIndependentRegionData() {
        StructureBlueprint source = emptyBlueprint();
        RegionAnalysis analysis = new RegionAnalysis();
        analysis.deltas = source.deltas;
        analysis.blocks = source.blocks;
        StructureBlueprint first = StructureBlueprint.capture(analysis, "First");
        StructureBlueprint second = StructureBlueprint.capture(analysis, "Second");
        assertTrue(!first.id.equals(second.id));
        assertNotSame(analysis.deltas, first.deltas);
        assertNotSame(analysis.blocks, first.blocks);
        RegionAnalysis instantiated = first.instantiate(-5);
        assertNotSame(first.deltas, instantiated.deltas);
        assertNotSame(first.blocks, instantiated.blocks);
        instantiated.deltas.x = 10;
        assertEquals(-2, first.deltas.x);
    }

    @Test
    void rejectsMissingNullAndUnknownVersionFields() {
        String json = BlueprintCodec.encode(emptyBlueprint());
        for (String key : new String[] { "id", "name", "deltas", "blocks", "formatVersion" }) {
            JsonObject tree = new JsonParser().parse(json).getAsJsonObject();
            tree.remove(key);
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(tree.toString()));
            tree.add(key, com.google.gson.JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(tree.toString()));
        }
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(json.replace("\"formatVersion\":1", "\"formatVersion\":2")));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(json.replace("\"formatVersion\":1", "\"formatVersion\":1.5")));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(null));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode("null"));
    }

    @Test
    void validatesNamesAndCanonicalUuidIds() {
        for (String name : new String[] { "", " ", new String(new char[65]).replace('\0', 'x'), "line\nbreak" }) {
            StructureBlueprint blueprint = emptyBlueprint();
            blueprint.name = name;
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(blueprint));
        }
        for (String id : new String[] { "../escape", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA" }) {
            StructureBlueprint blueprint = emptyBlueprint();
            blueprint.id = id;
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(blueprint));
        }
    }

    @Test
    void volumeIsInclusiveSignedAndOverflowSafe() {
        assertEquals(32768, BlueprintCodec.volume(-31, 31, -31));
        assertEquals(1, BlueprintCodec.volume(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.volume(32, 31, 31));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.volume(Integer.MIN_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.volume(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void rejectsTooManyBlocksAndNullBlocksBeforeSerialization() {
        StructureBlueprint blueprint = emptyBlueprint();
        blueprint.blocks.add(null);
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(blueprint));
        blueprint.blocks = new ArrayList<>(java.util.Collections.nCopies(BlueprintCodec.MAX_BLOCKS + 1, null));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.encode(blueprint));
    }

    @Test
    void limitsUtf8BytesAndNesting() {
        String oversized = new String(new char[BlueprintCodec.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(oversized));
        String multibyte = new String(new char[BlueprintCodec.MAX_BYTES / 2]).replace('\0', '\u20ac');
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(multibyte));
        String nested = new String(new char[65]).replace("\0", "[") + "0" + new String(new char[65]).replace("\0", "]");
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(nested));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode("{broken"));
    }

    @Test
    void fullSnapshotCountIsDerivedRatherThanTrusted() {
        StructureBlueprint source = emptyBlueprint();
        source.blockCount = 123;
        String encoded = BlueprintCodec.encode(source);
        assertEquals(0, new JsonParser().parse(encoded).getAsJsonObject().get("blockCount").getAsInt());
        assertEquals(0, BlueprintCodec.decode(encoded.replace("\"blockCount\":0", "\"blockCount\":999")).blockCount);
        JsonObject legacy = new JsonParser().parse(encoded).getAsJsonObject();
        legacy.remove("blockCount");
        assertEquals(0, BlueprintCodec.decode(legacy.toString()).blockCount);
    }

    @Test
    void internalP2PGroupsGetStableLocalTokensAndExternalGroupsAreCleared() {
        JsonObject first = aeBlock("[{\"mP2PFreq\":9007199254740993,\"mP2POutput\":true},null,"
            + "{\"mP2PFreq\":88,\"mP2POutput\":false},{\"mP2PFreq\":5,\"mP2POutput\":false},{\"mP2PFreq\":0}]");
        JsonObject second = aeBlock("[{\"mP2PFreq\":9007199254740993,\"mP2POutput\":false},"
            + "{\"mP2PFreq\":88,\"mP2POutput\":true},{\"mP2PFreq\":88,\"mP2POutput\":true},"
            + "{\"mP2PFreq\":777,\"mP2POutput\":true},{}]");
        JsonObject tree = snapshotTree(first, second);
        BlueprintCodec.stripSourceLinks(tree);
        JsonArray a = aeData(first).getAsJsonArray("mAEParts");
        JsonArray b = aeData(second).getAsJsonArray("mAEParts");
        assertEquals(1L, a.get(0).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(1L, b.get(0).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(2L, a.get(2).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(2L, b.get(1).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(2L, b.get(2).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(0L, a.get(3).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(0L, b.get(3).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(0L, a.get(4).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertEquals(0L, b.get(4).getAsJsonObject().get("mP2PFreq").getAsLong());
        assertTrue(a.get(0).getAsJsonObject().get("mP2POutput").getAsBoolean());
        assertTrue(!b.get(0).getAsJsonObject().get("mP2POutput").getAsBoolean());
        String sanitized = tree.toString();
        BlueprintCodec.stripSourceLinks(tree);
        assertEquals(sanitized, tree.toString());
    }

    @Test
    void sanitationIsRestrictedToTypedSourceFieldsAndPreservesOpaqueConfiguration() {
        JsonObject block = aeBlock("[{\"mP2PFreq\":45,\"mP2POutput\":true,\"mPartSettings\":{"
            + "\"securityKey\":\"l1\",\"serial\":\"l2\",\"connectedTargets\":{},\"frequency\":\"l123\","
            + "\"link\":\"user text containing link and frequency\",\"config\":{\"securityKey\":\"user data\"}}}]");
        JsonObject config = new JsonParser().parse("{\"securityKey\":1,\"serial\":2,\"connectedTargets\":{},"
            + "\"link\":\"unchanged\",\"frequency\":123,\"config\":{\"connectedTargets\":\"user data\"}}").getAsJsonObject();
        aeData(block).add("mAEConfig", config);
        JsonObject inventory = new JsonParser().parse("{\"mItems\":[{\"type\":\"pattern\",\"data\":{\"pattern\":{"
            + "\"link\":\"unchanged\",\"frequency\":\"l123\",\"securityKey\":\"l456\",\"connectedTargets\":{},"
            + "\"mGTData\":{},\"mAEParts\":[{\"mP2PFreq\":12}],\"oc:node\":{\"owner\":\"player\"}}}}]}").getAsJsonObject();
        String original = inventory.toString();
        block.add("inventory", inventory);
        aeData(block).add("mAEPatterns", new JsonParser().parse(original));
        aeData(block).getAsJsonArray("mAEParts").get(0).getAsJsonObject().add("mAEPatterns", new JsonParser().parse(original));
        JsonObject gt = new JsonParser().parse("{\"type\":\"gt\",\"data\":{\"mGTData\":{},\"mGTCustomName\":\"link frequency\"}}").getAsJsonObject();
        block.add("gt", gt);
        BlueprintCodec.stripSourceLinks(snapshotTree(block));
        assertTrue(!gt.getAsJsonObject("data").has("mGTData"));
        assertEquals("link frequency", gt.getAsJsonObject("data").get("mGTCustomName").getAsString());
        assertEquals(original, block.get("inventory").toString());
        assertEquals(original, aeData(block).get("mAEPatterns").toString());
        JsonObject part = aeData(block).getAsJsonArray("mAEParts").get(0).getAsJsonObject();
        assertEquals(original, part.get("mAEPatterns").toString());
        for (JsonObject settings : new JsonObject[] { config, part.getAsJsonObject("mPartSettings") }) {
            assertTrue(!settings.has("securityKey"));
            assertTrue(!settings.has("serial"));
            assertTrue(!settings.has("connectedTargets"));
            assertTrue(settings.has("link"));
            assertTrue(settings.has("frequency"));
            assertEquals(1, settings.getAsJsonObject("config").entrySet().size());
        }
    }

    private static JsonObject aeBlock(String parts) {
        return new JsonParser().parse("{\"ae\":{\"type\":\"ae\",\"data\":{\"mAEParts\":" + parts + "}}}").getAsJsonObject();
    }

    private static JsonObject aeData(JsonObject block) {
        return block.getAsJsonObject("ae").getAsJsonObject("data");
    }

    private static JsonObject snapshotTree(JsonObject... blocks) {
        JsonArray entries = new JsonArray();
        for (JsonObject block : blocks) entries.add(block);
        JsonObject tree = new JsonObject();
        tree.add("blocks", entries);
        return tree;
    }

    @Test
    void rejectsUnwhitelistedOrMismatchedIntegrationDiscriminators() {
        JsonObject tree = new JsonParser().parse(BlueprintCodec.encode(emptyBlueprint())).getAsJsonObject();
        for (String tag : new String[] { "java.lang.Runtime", "ae" }) {
            JsonObject block = new JsonParser().parse("{\"x\":0,\"y\":0,\"z\":0,\"spec\":{},\"gt\":{\"type\":\"" + tag + "\",\"data\":{}}}").getAsJsonObject();
            tree.getAsJsonArray("blocks").add(block);
            assertThrows(IllegalArgumentException.class, () -> BlueprintCodec.decode(tree.toString()));
            tree.add("blocks", new com.google.gson.JsonArray());
        }
    }
}
