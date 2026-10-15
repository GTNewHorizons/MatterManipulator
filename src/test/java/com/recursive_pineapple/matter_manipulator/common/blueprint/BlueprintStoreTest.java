package com.recursive_pineapple.matter_manipulator.common.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintStoreTest {

    @TempDir
    Path root;
    final UUID owner = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void clearCaches() {
        BlueprintStore.clearCache();
    }

    private Path file(String id) {
        return root.resolve("matter_manipulator").resolve("blueprints").resolve(owner.toString()).resolve(id + ".json.gz");
    }

    @Test
    void savesCompressedAndListsIndependentMetadata() throws IOException {
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        BlueprintStore.save(root, owner, blueprint);
        byte[] header = Files.readAllBytes(file(blueprint.id));
        assertEquals(0x1f, header[0] & 255);
        assertEquals(0x8b, header[1] & 255);
        List<StructureBlueprint> listed = BlueprintStore.list(root, owner);
        assertEquals(1, listed.size());
        assertTrue(listed.get(0).blocks.isEmpty());
        listed.get(0).name = "Mutated";
        listed.get(0).deltas.x = 99;
        assertEquals(blueprint.name, BlueprintStore.list(root, owner).get(0).name);
        assertEquals(-2, BlueprintStore.list(root, owner).get(0).deltas.x);
        BlueprintStore.clearCache();
        assertEquals(blueprint.name, BlueprintStore.load(root, owner, blueprint.id).name);
        try (java.util.stream.Stream<Path> paths = Files.list(file(blueprint.id).getParent())) {
            assertEquals(1, paths.count());
        }
    }

    @Test
    void ownersAndActualSaveDirectoriesAreIsolated() throws IOException {
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        BlueprintStore.save(root, owner, blueprint);
        assertNull(BlueprintStore.load(root, UUID.randomUUID(), blueprint.id));
        assertTrue(BlueprintStore.list(root, UUID.randomUUID()).isEmpty());
        assertNull(BlueprintStore.load(root.resolve("another-world"), owner, blueprint.id));
        assertTrue(BlueprintStore.list(root.resolve("another-world"), owner).isEmpty());
    }

    @Test
    void cacheAvoidsDiskReadsAndReturnedSnapshotsAreIndependent() throws IOException {
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        BlueprintStore.save(root, owner, blueprint);
        StructureBlueprint loaded = BlueprintStore.load(root, owner, blueprint.id);
        loaded.name = "Mutated";
        loaded.deltas.x = 50;
        Files.delete(file(blueprint.id));
        assertEquals(blueprint.name, BlueprintStore.load(root, owner, blueprint.id).name);
        assertEquals(-2, BlueprintStore.load(root, owner, blueprint.id).deltas.x);
        BlueprintStore.clearCache();
        assertNull(BlueprintStore.load(root, owner, blueprint.id));
    }

    @Test
    void renameOverwriteAndDeleteUpdateDiskAndCaches() throws IOException {
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        BlueprintStore.save(root, owner, blueprint);
        BlueprintStore.rename(root, owner, blueprint.id, "Renamed");
        assertEquals("Renamed", BlueprintStore.load(root, owner, blueprint.id).name);
        assertEquals("Renamed", BlueprintStore.list(root, owner).get(0).name);
        BlueprintStore.clearCache();
        assertEquals("Renamed", BlueprintStore.load(root, owner, blueprint.id).name);
        BlueprintStore.delete(root, owner, blueprint.id);
        assertNull(BlueprintStore.load(root, owner, blueprint.id));
        assertTrue(BlueprintStore.list(root, owner).isEmpty());
        BlueprintStore.delete(root, owner, blueprint.id);
        assertThrows(NoSuchFileException.class, () -> BlueprintStore.rename(root, owner, blueprint.id, "Missing"));
    }

    @Test
    void enforcesQuotaButAllowsUpdatingExistingEntry() throws IOException {
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        for (int i = 0; i < BlueprintStore.MAX_ENTRIES; i++) {
            blueprint.id = UUID.randomUUID().toString();
            BlueprintStore.save(root, owner, blueprint);
        }
        BlueprintStore.rename(root, owner, blueprint.id, "Existing entry");
        assertEquals(BlueprintStore.MAX_ENTRIES, BlueprintStore.list(root, owner).size());
        StructureBlueprint extra = BlueprintCodecTest.emptyBlueprint();
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.save(root, owner, extra));
        BlueprintStore.delete(root, owner, blueprint.id);
        BlueprintStore.save(root, owner, extra);
        assertNotNull(BlueprintStore.load(root, owner, extra.id));
    }

    @Test
    void rejectsTraversalNullOwnersAndOversizedRegions() {
        for (String id : new String[] { "../escape", "..\\escape", "1-1-1-1-1", "not-a-uuid" }) {
            assertThrows(IllegalArgumentException.class, () -> BlueprintStore.load(root, owner, id));
            assertThrows(IllegalArgumentException.class, () -> BlueprintStore.delete(root, owner, id));
        }
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.list(root, null));
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        blueprint.deltas.set(32, 31, 31);
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.save(root, owner, blueprint));
        assertTrue(!Files.exists(root.resolve("matter_manipulator")));
    }

    @Test
    void boundedDecompressionRejectsHighlyCompressedOversizedPayload() throws IOException {
        String id = UUID.randomUUID().toString();
        Files.createDirectories(file(id).getParent());
        try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file(id)))) {
            byte[] chunk = new byte[8192];
            for (int i = 0; i < BlueprintCodec.MAX_BYTES / chunk.length + 1; i++) gzip.write(chunk);
        }
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.load(root, owner, id));
    }

    @Test
    void rejectsFilenameIdMismatchInvalidUtf8AndCorruptCompression() throws IOException {
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        String other = UUID.randomUUID().toString();
        Files.createDirectories(file(other).getParent());
        try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file(other)))) {
            gzip.write(BlueprintCodec.encode(blueprint).getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.load(root, owner, other));
        try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file(other)))) {
            gzip.write(new byte[] { (byte) 0xc3, (byte) 0x28 });
        }
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.load(root, owner, other));
        Files.write(file(other), new byte[] { 1, 2, 3 });
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.load(root, owner, other));
    }

    @Test
    void reportsFilesystemFailuresAsIoExceptions() throws IOException {
        Files.write(root.resolve("matter_manipulator"), new byte[] { 1 });
        assertThrows(IOException.class, () -> BlueprintStore.list(root, owner));
        assertThrows(IOException.class, () -> BlueprintStore.save(root, owner, BlueprintCodecTest.emptyBlueprint()));
    }

    @Test
    void invalidRenameDoesNotReplaceExistingFile() throws IOException {
        StructureBlueprint blueprint = BlueprintCodecTest.emptyBlueprint();
        BlueprintStore.save(root, owner, blueprint);
        byte[] before = Files.readAllBytes(file(blueprint.id));
        assertThrows(IllegalArgumentException.class, () -> BlueprintStore.rename(root, owner, blueprint.id, ""));
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(file(blueprint.id)));
    }
}
