package com.recursive_pineapple.matter_manipulator.common.blueprint;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.joml.Vector3i;

/**
 * Server-only, player-owned storage. The caller must derive owner from the authenticated player, not a client payload.
 * No snapshots belong on manipulator items: store only owner/id references there.
 * Caches retain neither worlds nor servers and are keyed by canonical save-directory paths. All store mutations update
 * both caches. Administrative disk edits are observed after eviction or clearCache(), not on every preview request.
 */
public final class BlueprintStore {

    public static final int MAX_ENTRIES = 128;
    public static final int MAX_VOLUME = BlueprintCodec.MAX_BLOCKS;
    private static final String SUFFIX = ".json.gz";
    private static final int CACHE_BYTES = 16 * 1024 * 1024;
    private static final int CACHE_ENTRIES = 64;
    private static final int CACHE_OWNERS = 32;

    private static final Map<Path, String> SNAPSHOTS = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<Path, Map<String, StructureBlueprint>> INDEXES = new LinkedHashMap<>(16, 0.75f, true);
    private static int cachedBytes;

    private BlueprintStore() {}

    public static List<StructureBlueprint> list(World world, UUID owner) throws IOException {
        return list(saveDirectory(world), owner);
    }

    /** Returns null when the ID does not exist for this owner. */
    public static StructureBlueprint load(World world, UUID owner, String id) throws IOException {
        return load(saveDirectory(world), owner, id);
    }

    public static void save(World world, UUID owner, StructureBlueprint blueprint) throws IOException {
        save(saveDirectory(world), owner, blueprint);
    }

    /** Deleting a nonexistent entry is a no-op. */
    public static void delete(World world, UUID owner, String id) throws IOException {
        delete(saveDirectory(world), owner, id);
    }

    /** Renaming a nonexistent entry throws NoSuchFileException. */
    public static void rename(World world, UUID owner, String id, String name) throws IOException {
        rename(saveDirectory(world), owner, id, name);
    }

    /** Optional lifecycle/admin hook; bounded caches are otherwise isolated by actual save directory. */
    public static synchronized void clearCache() {
        SNAPSHOTS.clear();
        INDEXES.clear();
        cachedBytes = 0;
    }

    private static Path saveDirectory(World world) throws IOException {
        if (world == null || world.isRemote) throw new IllegalArgumentException("Blueprint storage requires a server world");
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) throw new IllegalArgumentException("No active Minecraft server");
        WorldServer overworld = server.worldServerForDimension(0);
        if (overworld == null || overworld.getSaveHandler().getWorldDirectory() == null) { throw new IOException("Overworld save directory is unavailable"); }
        return overworld.getSaveHandler().getWorldDirectory().toPath();
    }

    // Path overloads share the entire production I/O path and allow tests without a running game server.
    static synchronized List<StructureBlueprint> list(Path saveDirectory, UUID owner) throws IOException {
        List<StructureBlueprint> result = new ArrayList<>();
        for (StructureBlueprint blueprint : index(directory(saveDirectory, owner)).values())
            result.add(metadata(blueprint));
        result.sort(
            Comparator.comparing((StructureBlueprint blueprint) -> blueprint.name, String.CASE_INSENSITIVE_ORDER).thenComparing(blueprint -> blueprint.id)
        );
        return result;
    }

    static synchronized StructureBlueprint load(Path saveDirectory, UUID owner, String id) throws IOException {
        BlueprintCodec.validateId(id);
        Path directory = directory(saveDirectory, owner);
        Path file = file(directory, id);
        String json = SNAPSHOTS.get(file);
        if (json != null) return BlueprintCodec.decode(json);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        json = read(file);
        StructureBlueprint blueprint = BlueprintCodec.decode(json);
        if (!id.equals(blueprint.id)) throw new IllegalArgumentException("Blueprint ID does not match its filename");
        cache(file, json);
        return blueprint;
    }

    static synchronized void save(Path saveDirectory, UUID owner, StructureBlueprint blueprint) throws IOException {
        StructureBlueprint portable = BlueprintCodec.portableCopy(blueprint);
        String json = BlueprintCodec.encode(portable);
        Path directory = directory(saveDirectory, owner);
        Path target = file(directory, portable.id);
        Map<String, StructureBlueprint> index = index(directory);
        if (!index.containsKey(portable.id) && index.size() >= MAX_ENTRIES) throw new IllegalArgumentException("Player blueprint limit is " + MAX_ENTRIES);

        Files.createDirectories(directory);
        // Recheck after creation; UUID components and no symlinks prevent accidental path escape.
        directory(saveDirectory, owner);
        Path temporary = Files.createTempFile(directory, ".blueprint-", ".tmp");
        try {
            try (
                FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                GZIPOutputStream gzip = new GZIPOutputStream(Channels.newOutputStream(channel))
            ) {
                gzip.write(json.getBytes(StandardCharsets.UTF_8));
                gzip.finish();
                gzip.flush();
                channel.force(true);
            }
            // Fail rather than fall back to a non-atomic replacement on unsupported filesystems.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        index.put(portable.id, metadata(portable));
        cache(target, json);
    }

    static synchronized void delete(Path saveDirectory, UUID owner, String id) throws IOException {
        BlueprintCodec.validateId(id);
        Path directory = directory(saveDirectory, owner);
        Path target = file(directory, id);
        Files.deleteIfExists(target);
        uncache(target);
        Map<String, StructureBlueprint> index = INDEXES.get(directory);
        if (index != null) index.remove(id);
    }

    static synchronized void rename(Path saveDirectory, UUID owner, String id, String name) throws IOException {
        BlueprintCodec.validateName(name);
        StructureBlueprint blueprint = load(saveDirectory, owner, id);
        if (blueprint == null) throw new NoSuchFileException(id);
        blueprint.name = name;
        save(saveDirectory, owner, blueprint);
    }

    private static Path directory(Path saveDirectory, UUID owner) throws IOException {
        if (saveDirectory == null || owner == null) throw new IllegalArgumentException("Missing save directory or owner UUID");
        Path path = saveDirectory.toFile().getCanonicalFile().toPath();
        for (String component : new String[] {
            "matter_manipulator", "blueprints", owner.toString()
        }) {
            path = path.resolve(component);
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symlink in blueprint storage path");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Blueprint directory is not a directory: " + path);
            }
        }
        return path;
    }

    private static Path file(Path directory, String id) {
        Path file = directory.resolve(BlueprintCodec.validateId(id) + SUFFIX);
        if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Symlink blueprint file");
        return file;
    }

    private static Map<String, StructureBlueprint> index(Path directory) throws IOException {
        Map<String, StructureBlueprint> cached = INDEXES.get(directory);
        if (cached != null) return cached;
        Map<String, StructureBlueprint> index = new LinkedHashMap<>();
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
                for (Path path : stream) {
                    if (index.size() >= MAX_ENTRIES) throw new IllegalArgumentException("Too many stored blueprints for player");
                    String filename = path.getFileName().toString();
                    String id = BlueprintCodec.validateId(filename.substring(0, filename.length() - SUFFIX.length()));
                    Path file = file(directory, id);
                    String json = SNAPSHOTS.get(file);
                    if (json == null) json = read(file);
                    StructureBlueprint blueprint = BlueprintCodec.decode(json);
                    if (!id.equals(blueprint.id)) throw new IllegalArgumentException("Blueprint ID does not match its filename");
                    index.put(id, metadata(blueprint));
                    cache(file, json);
                }
            }
        }
        INDEXES.put(directory, index);
        while (INDEXES.size() > CACHE_OWNERS)
            INDEXES.remove(INDEXES.keySet().iterator().next());
        return index;
    }

    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular blueprint file: " + file);
        if (Files.size(file) > BlueprintCodec.MAX_BYTES + 65536L) throw new IllegalArgumentException("Compressed blueprint file is too large");
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (InputStream raw = Files.newInputStream(file); InputStream input = new GZIPInputStream(raw)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer, 0, Math.min(buffer.length, BlueprintCodec.MAX_BYTES - decoded.size() + 1))) != -1) {
                if (decoded.size() + count > BlueprintCodec.MAX_BYTES) throw new IllegalArgumentException("Decoded blueprint file is too large");
                decoded.write(buffer, 0, count);
            }
        } catch (ZipException | EOFException e) {
            throw new IllegalArgumentException("Invalid compressed blueprint data", e);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded.toByteArray()))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid blueprint UTF-8", e);
        }
    }

    private static StructureBlueprint metadata(StructureBlueprint source) {
        StructureBlueprint metadata = new StructureBlueprint();
        metadata.id = source.id;
        metadata.name = source.name;
        metadata.formatVersion = source.formatVersion;
        metadata.blockCount = source.blockCount;
        metadata.deltas = new Vector3i(source.deltas);
        metadata.blocks = new ArrayList<>();
        return metadata;
    }

    private static void cache(Path file, String json) {
        uncache(file);
        SNAPSHOTS.put(file, json);
        cachedBytes += json.getBytes(StandardCharsets.UTF_8).length;
        while (cachedBytes > CACHE_BYTES || SNAPSHOTS.size() > CACHE_ENTRIES)
            uncache(SNAPSHOTS.keySet().iterator().next());
    }

    private static void uncache(Path file) {
        String removed = SNAPSHOTS.remove(file);
        if (removed != null) cachedBytes -= removed.getBytes(StandardCharsets.UTF_8).length;
    }
}
