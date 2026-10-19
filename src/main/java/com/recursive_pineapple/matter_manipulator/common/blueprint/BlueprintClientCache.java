package com.recursive_pineapple.matter_manipulator.common.blueprint;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.client.gui.BlueprintLibraryScreen;
import com.recursive_pineapple.matter_manipulator.client.gui.BlueprintPreviewScreen;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMRenderer;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintOperation;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintRequestData;
import com.recursive_pineapple.matter_manipulator.common.networking.Messages.BlueprintStatus;

/** Client snapshots only, never build authority. No world or connection can reuse another session's snapshots. */
@SideOnly(Side.CLIENT)
public final class BlueprintClientCache {

    private static final int CHUNK_BYTES = 16 * 1024;
    private static final int MAX_CACHED_BYTES = 8 * 1024 * 1024;
    private static final long RETRY_MS = 5000, TRANSFER_MS = 15000;
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(8, 0.75f, true);
    private static final Map<Integer, Transfer> PENDING = new LinkedHashMap<>();
    private static final Map<String, Long> ATTEMPTS = new LinkedHashMap<>();
    private static World currentWorld;
    private static Object connection;
    private static int sequence, cachedBytes;

    private BlueprintClientCache() {}

    /** Lazy preview path for MMState: only the currently equipped, player-owned active reference can request data. */
    public static synchronized StructureBlueprint get(World world, String owner, String id) {
        checkWorld(world);
        Minecraft mc = Minecraft.getMinecraft();
        if (
            world == null || !world.isRemote || mc.thePlayer == null || id == null || owner == null || !owner.equals(mc.thePlayer.getUniqueID().toString())
        ) return null;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemMatterManipulator)) return null;
        MMState state = ItemMatterManipulator.getState(held);
        if (
            !state.hasCap(ItemMatterManipulator.ALLOW_COPYING) || !id.equals(state.config.blueprintId) || !owner.equals(state.config.blueprintOwner)
        ) return null;
        return lookup(owner, id);
    }

    private static StructureBlueprint lookup(String owner, String id) {
        String key = owner + "/" + id;
        Cached cached = CACHE.get(key);
        if (cached != null) return cached.blueprint;
        long now = System.currentTimeMillis();
        expire(now);
        Long previous = ATTEMPTS.get(key);
        if (PENDING.size() < 2 && (previous == null || now - previous >= RETRY_MS)) {
            request(owner, id, BlueprintOperation.FETCH);
        }
        return null;
    }

    /** Library preview path: any blueprint the authenticated player owns; the server re-checks ownership on FETCH. */
    public static synchronized StructureBlueprint preview(String id) {
        Minecraft mc = Minecraft.getMinecraft();
        checkWorld(mc.theWorld);
        if (mc.thePlayer == null || mc.theWorld == null || id == null) return null;
        Cached cached = CACHE.get(mc.thePlayer.getUniqueID().toString() + "/" + id);
        return cached == null ? null : cached.blueprint;
    }

    /** Explicit preview download; returns the request token, or -1 without a session. */
    public static synchronized int fetchPreview(String id) {
        Minecraft mc = Minecraft.getMinecraft();
        checkWorld(mc.theWorld);
        if (mc.thePlayer == null || mc.theWorld == null || id == null) return -1;
        return request(mc.thePlayer.getUniqueID().toString(), id, BlueprintOperation.FETCH);
    }

    public static synchronized int nextRequestId() {
        // Do not reset across disconnects: delayed packets must never match a fresh session's request.
        return ++sequence;
    }

    public static synchronized int select(String id) {
        Minecraft mc = Minecraft.getMinecraft();
        checkWorld(mc.theWorld);
        if (mc.thePlayer == null || mc.theWorld == null) return -1;
        return request(mc.thePlayer.getUniqueID().toString(), id, BlueprintOperation.SELECT);
    }

    private static int request(String owner, String id, BlueprintOperation operation) {
        expire(System.currentTimeMillis());
        PENDING.values().removeIf(transfer -> transfer.owner.equals(owner) && transfer.id.equals(id));
        while (PENDING.size() >= 2)
            PENDING.remove(PENDING.keySet().iterator().next());
        int token = nextRequestId();
        PENDING.put(token, new Transfer(owner, id));
        ATTEMPTS.put(owner + "/" + id, System.currentTimeMillis());
        while (ATTEMPTS.size() > 128)
            ATTEMPTS.remove(ATTEMPTS.keySet().iterator().next());
        Messages.BlueprintRequest.sendToServer(new BlueprintRequestData(operation, token, id, null));
        return token;
    }

    /** Invoked only by the server-direction-checked packet handler, on the client tick thread. */
    public static synchronized void receive(int request, String owner, String id, int total, int offset, byte[] bytes) {
        checkWorld(Minecraft.getMinecraft().theWorld);
        expire(System.currentTimeMillis());
        Transfer transfer = PENDING.get(request);
        if (transfer == null || !transfer.owner.equals(owner) || !transfer.id.equals(id)) return;
        if (
            total < 1 || total > BlueprintCodec.MAX_BYTES ||
                bytes == null ||
                bytes.length < 1 ||
                bytes.length > CHUNK_BYTES ||
                offset != transfer.received ||
                offset < 0 ||
                offset >= total ||
                offset % CHUNK_BYTES != 0 ||
                bytes.length != Math.min(CHUNK_BYTES, total - offset) ||
                (transfer.bytes != null && total != transfer.bytes.length)
        ) {
            fail(request);
            return;
        }
        if (transfer.bytes == null) transfer.bytes = new byte[total];
        System.arraycopy(bytes, 0, transfer.bytes, offset, bytes.length);
        transfer.received += bytes.length;
        if (transfer.received != total) return;
        PENDING.remove(request);
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(transfer.bytes))
                .toString();
            StructureBlueprint blueprint = BlueprintCodec.decode(json);
            if (!id.equals(blueprint.id)) throw new IllegalArgumentException("Blueprint response ID mismatch");
            String key = owner + "/" + id;
            Cached old = CACHE.remove(key);
            if (old != null) cachedBytes -= old.bytes;
            CACHE.put(key, new Cached(blueprint, total));
            cachedBytes += total;
            while (cachedBytes > MAX_CACHED_BYTES || CACHE.size() > 4) {
                cachedBytes -= CACHE.remove(CACHE.keySet().iterator().next()).bytes;
            }
            MMRenderer.markNeedsReanalysis();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            notifyError(request, BlueprintStatus.INVALID.translationKey());
        }
    }

    private static void fail(int request) {
        PENDING.remove(request);
        notifyError(request, BlueprintStatus.INVALID.translationKey());
    }

    private static void notifyError(int request, String translationKey) {
        BlueprintLibraryScreen.onError(request, translationKey);
        BlueprintPreviewScreen.onError(request, translationKey);
    }

    public static synchronized void onResult(int request, boolean success) {
        if (!success) PENDING.remove(request);
    }

    public static synchronized void invalidate(String id) {
        Iterator<Map.Entry<String, Cached>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Cached> entry = iterator.next();
            if (entry.getValue().blueprint.id.equals(id)) {
                cachedBytes -= entry.getValue().bytes;
                iterator.remove();
            }
        }
        PENDING.values().removeIf(transfer -> transfer.id.equals(id));
    }

    private static void expire(long now) {
        PENDING.values().removeIf(transfer -> now - transfer.created > TRANSFER_MS);
    }

    public static synchronized void checkWorld(World world) {
        Object nextConnection = Minecraft.getMinecraft().getNetHandler();
        if (world != currentWorld || nextConnection != connection) {
            clearCache();
            currentWorld = world;
            connection = nextConnection;
        }
    }

    /** Safe lifecycle hook; also called automatically on disconnect and world/dimension/connection changes. */
    public static synchronized void clearCache() {
        CACHE.clear();
        PENDING.clear();
        ATTEMPTS.clear();
        cachedBytes = 0;
        currentWorld = null;
        connection = null;
    }

    private static final class Cached {

        private final StructureBlueprint blueprint;
        private final int bytes;

        private Cached(StructureBlueprint blueprint, int bytes) {
            this.blueprint = blueprint;
            this.bytes = bytes;
        }
    }

    private static final class Transfer {

        private final String owner, id;
        private final long created = System.currentTimeMillis();
        private byte[] bytes;
        private int received;

        private Transfer(String owner, String id) {
            this.owner = owner;
            this.id = id;
        }
    }
}
