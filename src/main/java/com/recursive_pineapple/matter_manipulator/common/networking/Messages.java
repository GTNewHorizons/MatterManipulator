package com.recursive_pineapple.matter_manipulator.common.networking;

import static com.recursive_pineapple.matter_manipulator.common.utils.Mods.AppliedEnergistics2;
import static com.recursive_pineapple.matter_manipulator.common.utils.Mods.GregTech;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry.TargetPoint;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

import com.gtnewhorizon.gtnhlib.util.CoordinatePacker;
import com.gtnewhorizons.modularui.common.internal.network.NetworkUtils;
import com.recursive_pineapple.matter_manipulator.GlobalMMConfig.DebugConfig;
import com.recursive_pineapple.matter_manipulator.MMMod;
import com.recursive_pineapple.matter_manipulator.asm.Optional;
import com.recursive_pineapple.matter_manipulator.client.gui.BlueprintLibraryScreen;
import com.recursive_pineapple.matter_manipulator.client.gui.BlueprintPreviewScreen;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintClientCache;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintCodec;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterialExport;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintStore;
import com.recursive_pineapple.matter_manipulator.common.blueprint.StructureBlueprint;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Location;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMRenderer;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.BlockRemoveMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.BlockSelectMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.InterfaceCopyMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.P2PInterfaceCopyMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PendingAction;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.PlaceMode;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.MMState.Shape;
import com.recursive_pineapple.matter_manipulator.common.items.manipulator.Transform;
import com.recursive_pineapple.matter_manipulator.common.uplink.IUplinkMulti;
import com.recursive_pineapple.matter_manipulator.common.uplink.MTEMMUplink;
import com.recursive_pineapple.matter_manipulator.common.uplink.UplinkState;
import com.recursive_pineapple.matter_manipulator.common.utils.MMUtils;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods;
import com.recursive_pineapple.matter_manipulator.common.utils.Mods.Names;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Contains all networking messages that the manipulator can send.
 * Entries can be reordered.
 */
public enum Messages {

    MMBPressed(server(simple((player, stack, manipulator, state) -> { manipulator.onMMBPressed(player, stack, state); }))),
    MMBPressedInGUI(server(cursorItemStackPacket((player, pickedStack, manipulator, state, isSneak, hoveredStack) -> {
        manipulator.onMMBPressedInGUI(player, pickedStack, state, isSneak, hoveredStack);
    }))),
    SetRemoveMode(server(enumPacket(BlockRemoveMode.values(), (state, value) -> state.config.removeMode = value))),
    SetPlaceMode(server(enumPacket(PlaceMode.values(), (player, stack, manipulator, state, value) -> {

        int requiredBit = switch (value) {
            case COPYING -> ItemMatterManipulator.ALLOW_COPYING;
            case EXCHANGING -> ItemMatterManipulator.ALLOW_EXCHANGING;
            case GEOMETRY -> 0;
            case MOVING -> ItemMatterManipulator.ALLOW_MOVING;
            case CABLES -> ItemMatterManipulator.ALLOW_CABLES;
        };

        if (state.hasCap(requiredBit)) {
            state.config.placeMode = value;
        }
    }))),
    SetBlockSelectMode(server(enumPacket(BlockSelectMode.values(), (state, value) -> state.config.blockSelectMode = value))),
    SetPendingAction(server(enumPacket(PendingAction.values(), (state, value) -> state.config.action = value))),
    ClearBlocks(server(simple((player, stack, manipulator, state) -> {
        state.config.corners = null;
        state.config.edges = null;
        state.config.faces = null;
        state.config.volumes = null;
        state.config.action = null;
    }))),
    SetShape(server(enumPacket(Shape.values(), (state, value) -> state.config.shape = value))),
    SetLinkExternalHubs(server(simple((player, stack, manipulator, state) -> {
        state.config.linkExternalHubs = !state.config.linkExternalHubs;
    }))),
    SetA(server(locationPacket((player, stack, manipulator, state, location) -> {
        state.config.coordA = new Location(player.worldObj, location);
    }))),
    MoveA(server(simple((player, stack, manipulator, state) -> {
        state.config.action = PendingAction.MOVING_COORDS;
        state.config.coordAOffset = new Vector3i();
        state.config.coordBOffset = null;
        state.config.coordCOffset = null;
    }))),
    SetB(server(locationPacket((player, stack, manipulator, state, location) -> {
        state.config.coordB = new Location(player.worldObj, location);
    }))),
    MoveB(server(simple((player, stack, manipulator, state) -> {
        state.config.action = PendingAction.MOVING_COORDS;
        state.config.coordAOffset = null;
        state.config.coordBOffset = new Vector3i();
        state.config.coordCOffset = null;
    }))),
    SetC(server(locationPacket((player, stack, manipulator, state, location) -> {
        state.config.coordC = new Location(player.worldObj, location);
    }))),
    MoveC(server(simple((player, stack, manipulator, state) -> {
        state.config.action = PendingAction.MOVING_COORDS;
        state.config.coordAOffset = null;
        state.config.coordBOffset = null;
        state.config.coordCOffset = new Vector3i();
    }))),
    SwapRegion(server(simple((player, stack, manipulator, state) -> {
        Location coordA = state.config.coordA;
        Location coordB = state.config.coordB;
        Location coordC = state.config.coordC;

        if (coordA == null || coordB == null || coordC == null) return;

        Vector3i newCoordBVector = new Vector3i(
            coordC.x + coordB.x - coordA.x,
            coordC.y + coordB.y - coordA.y,
            coordC.z + coordB.z - coordA.z
        );

        state.config.coordA = coordC;
        state.config.coordB = new Location(player.worldObj, newCoordBVector);
        state.config.coordC = coordA;
    }))),
    MoveAll(server(simple((player, stack, manipulator, state) -> {
        state.config.action = PendingAction.MOVING_COORDS;

        Vector3i lookingAt = MMUtils.getLookingAtLocation(player);

        if (state.config.coordA == null) {
            state.config.coordAOffset = null;
        } else {
            state.config.coordAOffset = state.config.coordA.toVec()
                .sub(lookingAt);
        }

        if (state.config.coordB == null) {
            state.config.coordBOffset = null;
        } else {
            state.config.coordBOffset = state.config.coordB.toVec()
                .sub(lookingAt);
        }

        if (state.config.coordC == null) {
            state.config.coordCOffset = null;
        } else {
            state.config.coordCOffset = state.config.coordC.toVec()
                .sub(lookingAt);
        }

    }))),
    MoveHere(server(simple((player, stack, manipulator, state) -> {
        if (state.config.shape.requiresC()) {
            if (Location.areCompatible(state.config.coordA, state.config.coordB, state.config.coordC)) {
                Vector3i offsetB = state.config.coordB.toVec()
                    .sub(state.config.coordA.toVec());
                Vector3i offsetC = state.config.coordC.toVec()
                    .sub(state.config.coordA.toVec());

                Vector3i newA = MMUtils.getLookingAtLocation(player);
                Vector3i newB = new Vector3i(newA).add(offsetB);
                Vector3i newC = new Vector3i(newA).add(offsetC);

                state.config.coordA = new Location(player.getEntityWorld(), newA);
                state.config.coordB = new Location(player.getEntityWorld(), newB);
                state.config.coordC = new Location(player.getEntityWorld(), newC);
            }
        } else {
            if (Location.areCompatible(state.config.coordA, state.config.coordB)) {
                Vector3i offsetB = state.config.coordB.toVec()
                    .sub(state.config.coordA.toVec());

                Vector3i newA = MMUtils.getLookingAtLocation(player);
                Vector3i newB = new Vector3i(newA).add(offsetB);

                state.config.coordA = new Location(player.getEntityWorld(), newA);
                state.config.coordB = new Location(player.getEntityWorld(), newB);
            }
        }
    }))),
    ClearCoords(server(simple((player, stack, manipulator, state) -> {
        state.config.clearBlueprint();
        state.config.action = null;
        state.config.coordA = null;
        state.config.coordB = null;
        state.config.coordC = null;
        state.config.coordAOffset = null;
        state.config.coordBOffset = null;
        state.config.coordCOffset = null;
    }))),
    ClearTransform(server(simple((player, stack, manipulator, state) -> {
        state.config.transform = new Transform();
        state.config.arraySpan = null;
    }))),
    MarkCopy(server(simple((player, stack, manipulator, state) -> {
        if (!state.hasCap(ItemMatterManipulator.ALLOW_COPYING)) return;
        state.config.clearBlueprint();
        state.config.action = PendingAction.MARK_COPY_A;
        state.config.coordA = null;
        state.config.coordB = null;
    }))),
    MarkCut(server(simple((player, stack, manipulator, state) -> {
        if (!state.hasCap(ItemMatterManipulator.ALLOW_MOVING)) return;
        state.config.clearBlueprint();
        state.config.action = PendingAction.MARK_CUT_A;
        state.config.coordA = null;
        state.config.coordB = null;
    }))),
    MarkPaste(server(simple((player, stack, manipulator, state) -> {
        state.config.action = PendingAction.MARK_PASTE;
        state.config.coordC = null;
    }))),
    GetRequiredItems(server(new ISimplePacketHandler<IntPacket>() {

        @Override
        public void handle(EntityPlayer player, IntPacket packet) {
            BlueprintTasks.submitPlan((EntityPlayerMP) player, packet.value);
        }

        @Override
        public IntPacket getNewPacket(Messages message, Object value) {
            IntPacket packet = new IntPacket(message);
            packet.value = value == null ? 0 : (Integer) value;
            return packet;
        }
    })),
    ClearManualPlans(server(simple((player, stack, manipulator, state) -> {
        if (state.connectToUplink()) {
            state.uplink.clearManualPlans(player);
        }
    }))),
    CancelAutoPlans(server(simple((player, stack, manipulator, state) -> {
        if (state.connectToUplink()) {
            state.uplink.cancelAutoPlans(player);
        }
    }))),
    ClearWhitelist(server(simple((player, stack, manipulator, state) -> { state.config.replaceWhitelist = null; }))),
    UpdateUplinkState(client(new ISimplePacketHandler<UplinkPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, UplinkPacket packet) {
            World theWorld = Minecraft.getMinecraft().theWorld;

            if (theWorld.provider.dimensionId == packet.worldId) {
                if (Mods.GregTech.isModLoaded() && Mods.AppliedEnergistics2.isModLoaded()) {
                    Location l = packet.getLocation();

                    setState(l.getWorld(), l.x, l.y, l.z, packet.getState());
                }
            }
        }

        @Optional({
            Names.GREG_TECH_NH, Names.APPLIED_ENERGISTICS2
        })
        private void setState(World world, int x, int y, int z, UplinkState state) {
            if (world.getTileEntity(x, y, z) instanceof IGregTechTileEntity igte) {
                if (igte.getMetaTileEntity() instanceof MTEMMUplink uplink) {
                    uplink.setState(state);
                }
            }
        }

        @Override
        public UplinkPacket getNewPacket(Messages message, @Nullable Object value) {
            UplinkPacket packet = new UplinkPacket(message);

            if (value != null) {
                IUplinkMulti uplink = (IUplinkMulti) value;

                packet.setState(uplink.getLocation(), uplink.getState());
            }

            return packet;
        }
    })),
    TooltipResponse(client(new ISimplePacketHandler<Messages.IntPacket>() {

        @Override
        public void handle(EntityPlayer player, IntPacket packet) {
            ItemMatterManipulator.onTooltipResponse(packet.value);
        }

        @Override
        public IntPacket getNewPacket(Messages message, @Nullable Object value) {
            IntPacket packet = new IntPacket(message);
            packet.value = value == null ? 0 : (int) (Integer) value;
            return packet;
        }
    })),
    TooltipQuery(server(new ISimplePacketHandler<Messages.IntPacket>() {

        @Override
        public void handle(EntityPlayer player, IntPacket packet) {
            if (packet.value < 0 || packet.value >= player.openContainer.inventorySlots.size()) return;

            ItemStack stack = player.openContainer.getSlot(packet.value).getStack();

            if (stack != null && stack.getItem() instanceof ItemMatterManipulator) {
                MMState state = ItemMatterManipulator.getState(stack);

                int result = 0;

                if (state.hasCap(ItemMatterManipulator.CONNECTS_TO_AE) && AppliedEnergistics2.isModLoaded()) {
                    if (state.connectToMESystem()) {
                        if (state.canInteractWithAE(player)) {
                            result |= MMUtils.TOOLTIP_AE_WORKS;
                        }
                    }
                }

                if (state.hasCap(ItemMatterManipulator.CONNECTS_TO_UPLINK) && GregTech.isModLoaded()) {
                    if (state.uplinkAddress != null) {
                        if (state.connectToUplink()) {
                            result |= MMUtils.TOOLTIP_UPLINK_WORKS;
                        }
                    }
                }

                Messages.TooltipResponse.sendToPlayer((EntityPlayerMP) player, (Integer) result);
            }
        }

        @Override
        public IntPacket getNewPacket(Messages message, @Nullable Object value) {
            IntPacket packet = new IntPacket(message);
            packet.value = value == null ? 0 : (int) (Integer) value;
            return packet;
        }
    })),
    SetReplaceCribs(server(simple((player, stack, manipulator, state) -> {
        state.config.replaceCribsWithProxies = !state.config.replaceCribsWithProxies;
    }))),
    SetInterfaceCopyMode(server(simple((player, stack, manipulator, state) -> {
        state.config.interfaceCopyMode = state.config.interfaceCopyMode == InterfaceCopyMode.COPY ?
            InterfaceCopyMode.CONVERT_TO_P2P :
            InterfaceCopyMode.COPY;
    }))),
    SetP2PInterfaceCopyMode(server(simple((player, stack, manipulator, state) -> {
        state.config.p2pInterfaceCopyMode = state.config.p2pInterfaceCopyMode == P2PInterfaceCopyMode.LINK_TO_ORIGINAL ?
            P2PInterfaceCopyMode.COPY_PATTERNS :
            P2PInterfaceCopyMode.LINK_TO_ORIGINAL;
    }))),
    SetCopyAEPatterns(server(simple((player, stack, manipulator, state) -> {
        state.config.shouldCopyAEPatterns = !state.config.shouldCopyAEPatterns;
    }))),
    SetArray(server(locationPacket((player, stack, manipulator, state, span) -> {
        state.config.arraySpan = span;
    }))),
    ResetArray(server(simple((player, stack, manipulator, state) -> {
        state.config.arraySpan = null;
    }))),
    ResetTransform(server(simple((player, stack, manipulator, state) -> {
        state.config.transform = new Transform();
    }))),
    ToggleTransformFlip(server(intPacket((player, stack, manipulator, state, value) -> {
        Transform transform = state.config.transform;
        if (transform == null) state.config.transform = (transform = new Transform());

        if ((value & Transform.FLIP_X) != 0) transform.flipX ^= true;
        if ((value & Transform.FLIP_Y) != 0) transform.flipY ^= true;
        if ((value & Transform.FLIP_Z) != 0) transform.flipZ ^= true;
    }))),
    RotateTransform(server(intPacket((player, stack, manipulator, state, value) -> {
        if (state.config.transform == null) state.config.transform = new Transform();

        int dir = value & 0xFF;

        if (dir < 0 || dir >= ForgeDirection.VALID_DIRECTIONS.length) return;

        int amount = ((value >> 8) & 0xFF) != 0 ? 1 : -1;

        Transform transform = state.config.transform;
        if (transform == null) state.config.transform = (transform = new Transform());

        transform.rotate(ForgeDirection.VALID_DIRECTIONS[dir], amount);
    }))),
    PlaySound(client(new ISimplePacketHandler<Messages.SoundPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, SoundPacket packet) {
            if (packet.worldId == Minecraft.getMinecraft().theWorld.provider.dimensionId) {
                int x = CoordinatePacker.unpackX(packet.location);
                int y = CoordinatePacker.unpackY(packet.location);
                int z = CoordinatePacker.unpackZ(packet.location);

                SoundResource[] sounds = SoundResource.values();

                if (packet.sound < 0 || packet.sound >= sounds.length) return;

                Minecraft.getMinecraft().theWorld.playSound(x, y, z, sounds[packet.sound].toString(), packet.strength, packet.pitch, false);
            }
        }

        @Override
        public SoundPacket getNewPacket(Messages message, @Nullable Object value) {
            SoundPacket packet = new SoundPacket(message);

            @SuppressWarnings("unchecked")
            Pair<Location, SoundResource> sound = (Pair<Location, SoundResource>) value;

            if (sound != null) {
                packet.worldId = sound.left().worldId;
                packet.location = CoordinatePacker.pack(sound.left().x, sound.left().y, sound.left().z);
                packet.sound = sound.right().ordinal();
            }

            return packet;
        }
    })),
    BuildStatus(client(new ISimplePacketHandler<Messages.BuildStatusPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, BuildStatusPacket packet) {
            MMRenderer.setStatusHints(packet.errors, packet.warnings);
        }

        @Override
        public BuildStatusPacket getNewPacket(Messages message, @Nullable Object value) {
            BuildStatusPacket packet = new BuildStatusPacket(message);

            @SuppressWarnings("unchecked")
            Pair<LongList, LongList> pair = (Pair<LongList, LongList>) value;

            if (pair != null) {
                packet.errors = pair.left();
                packet.warnings = pair.right();
            }

            return packet;
        }
    })),

    BlueprintRequest(server(new ISimplePacketHandler<BlueprintRequestPacket>() {

        @Override
        public void handle(EntityPlayer player, BlueprintRequestPacket packet) {
            BlueprintTasks.submit((EntityPlayerMP) player, packet);
        }

        @Override
        public BlueprintRequestPacket getNewPacket(Messages message, Object value) {
            return new BlueprintRequestPacket(message, (BlueprintRequestData) value);
        }
    })),
    BlueprintList(client(new ISimplePacketHandler<BlueprintListPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, BlueprintListPacket packet) {
            BlueprintTasks.enqueueClient(() -> BlueprintLibraryScreen.onList(packet.request, packet.entries));
        }

        @Override
        public BlueprintListPacket getNewPacket(Messages message, Object value) {
            return value == null ? new BlueprintListPacket(message) : (BlueprintListPacket) value;
        }
    })),
    BlueprintData(client(new ISimplePacketHandler<BlueprintDataPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, BlueprintDataPacket packet) {
            BlueprintTasks.enqueueClient(
                () -> BlueprintClientCache.receive(
                    packet.request,
                    packet.owner,
                    packet.id,
                    packet.total,
                    packet.offset,
                    packet.data
                )
            );
        }

        @Override
        public BlueprintDataPacket getNewPacket(Messages message, Object value) {
            return value == null ? new BlueprintDataPacket(message) : (BlueprintDataPacket) value;
        }
    })),
    BlueprintResult(client(new ISimplePacketHandler<BlueprintResultPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, BlueprintResultPacket packet) {
            BlueprintTasks.enqueueClient(() -> {
                BlueprintClientCache.onResult(packet.request, packet.result == BlueprintStatus.OK);
                BlueprintLibraryScreen.onResult(packet.request, packet.result, packet.detail);
                BlueprintPreviewScreen.onResult(packet.request, packet.result);
            });
        }

        @Override
        public BlueprintResultPacket getNewPacket(Messages message, Object value) {
            return value == null ? new BlueprintResultPacket(message) : (BlueprintResultPacket) value;
        }
    })),
    BlueprintMaterialList(client(new ISimplePacketHandler<BlueprintMaterialListPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, BlueprintMaterialListPacket packet) {
            BlueprintTasks.enqueueClient(
                () -> BlueprintLibraryScreen.onMaterials(
                    packet.request,
                    packet.id,
                    packet.page,
                    packet.totalTypes,
                    packet.entries
                )
            );
        }

        @Override
        public BlueprintMaterialListPacket getNewPacket(Messages message, Object value) {
            return value == null ? new BlueprintMaterialListPacket(message) : (BlueprintMaterialListPacket) value;
        }
    })),
    BlueprintMaterialExportData(client(new ISimplePacketHandler<BlueprintMaterialExportPacket>() {

        @Override
        @SideOnly(Side.CLIENT)
        public void handle(EntityPlayer player, BlueprintMaterialExportPacket packet) {
            BlueprintTasks.enqueueClient(
                () -> BlueprintLibraryScreen.onMaterialExportData(packet.request, packet.id, packet.total, packet.offset, packet.data)
            );
        }

        @Override
        public BlueprintMaterialExportPacket getNewPacket(Messages message, Object value) {
            return value == null ? new BlueprintMaterialExportPacket(message) : (BlueprintMaterialExportPacket) value;
        }
    })),

    ;

    public enum BlueprintOperation {
        LIST,
        SAVE,
        SELECT,
        RENAME,
        DELETE,
        FETCH,
        MATERIALS,
        BOOKMARKS
    }

    public enum BlueprintStatus {

        OK,
        DENIED,
        INVALID,
        NOT_FOUND,
        QUOTA,
        REGION,
        VOLUME,
        RANGE,
        UNLOADED,
        STORAGE,
        BUSY,
        INVALID_NAME;

        public String translationKey() {

            return "mm.blueprint.result." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final class BlueprintRequestData {

        public final BlueprintOperation operation;
        public final int request;
        public final String id, name;
        public final int page, pageSize;

        public BlueprintRequestData(BlueprintOperation operation, int request, String id, String name) {
            this(operation, request, id, name, 0, 8);
        }

        public BlueprintRequestData(BlueprintOperation operation, int request, String id, String name, int page, int pageSize) {
            this.operation = operation;
            this.request = request;
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.page = page;
            this.pageSize = pageSize;
        }
    }

    public static final class BlueprintEntry {

        public final String id, name;
        public final Vector3i deltas;
        public final int blockCount;

        public BlueprintEntry(String id, String name, Vector3i deltas, int blockCount) {
            this.id = id;
            this.name = name;
            this.deltas = new Vector3i(deltas);
            this.blockCount = blockCount;
        }
    }

    private static boolean isOnline(EntityPlayerMP player) {
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        return server != null && !player.isDead && server.getConfigurationManager().playerEntityList.contains(player);
    }

    private static void handleRequiredItems(EntityPlayerMP player, int flags) {
        if (!isOnline(player)) return;
        try {
            ItemStack held = player.inventory.getCurrentItem();
            if (held == null || !(held.getItem() instanceof ItemMatterManipulator manipulator)) {
                MMUtils.sendErrorToPlayer(player, "mm.blueprint.result.denied");
                return;
            }
            MMState state = ItemMatterManipulator.getState(held);
            if (state.config.placeMode != PlaceMode.COPYING || !state.hasCap(ItemMatterManipulator.ALLOW_COPYING) || !state.canUseBlueprint(player)) {
                MMUtils.sendErrorToPlayer(player, "mm.blueprint.result.denied");
                return;
            }
            if (state.config.isBlueprint() && state.getBlueprint(player.worldObj) == null) {
                MMUtils.sendErrorToPlayer(player, "mm.blueprint.result.not_found");
                return;
            }
            MMUtils.createPlanImpl(player, state, manipulator, flags);
        } catch (RuntimeException e) {
            MMMod.LOG.warn("Could not analyze manipulator plan for " + player.getUniqueID(), e);
            MMUtils.sendErrorToPlayer(player, "mm.blueprint.result.storage");
        }
    }

    private static void sendBlueprintResult(EntityPlayerMP player, int request, BlueprintStatus status) {
        sendBlueprintResult(player, request, status, "");
    }

    private static void sendBlueprintResult(EntityPlayerMP player, int request, BlueprintStatus status, String detail) {
        BlueprintResultPacket response = new BlueprintResultPacket(BlueprintResult);
        response.request = request;
        response.result = status;
        response.detail = detail;
        BlueprintResult.sendToPlayer(player, response);
    }

    private static void handleBlueprint(EntityPlayerMP player, BlueprintRequestPacket packet) {
        if (!isOnline(player)) return;
        BlueprintStatus result = BlueprintStatus.OK;
        String detail = "";
        try {
            ItemStack held = player.inventory.getCurrentItem();
            if (held == null || !(held.getItem() instanceof ItemMatterManipulator manipulator)) { throw new BlueprintFailure(BlueprintStatus.DENIED); }
            MMState state = ItemMatterManipulator.getState(held);
            if (!state.hasCap(ItemMatterManipulator.ALLOW_COPYING)) throw new BlueprintFailure(BlueprintStatus.DENIED);
            World world = player.worldObj;
            UUID owner = player.getUniqueID();
            BlueprintRequestData data = packet.data;
            if (data.operation == null) throw new BlueprintFailure(BlueprintStatus.INVALID);
            // A pending build owns its original snapshot until item use stops. Never replace it underneath that build.
            if (
                player.isUsingItem() && (data.operation == BlueprintOperation.SELECT || data.operation == BlueprintOperation.DELETE)
            ) {
                throw new BlueprintFailure(BlueprintStatus.BUSY);
            }
            switch (data.operation) {
                case LIST -> {
                    BlueprintListPacket response = new BlueprintListPacket(BlueprintList);
                    response.request = data.request;
                    for (StructureBlueprint blueprint : BlueprintStore.list(world, owner)) {
                        BlueprintProtocol.validateName(blueprint.name);
                        response.entries.add(
                            new BlueprintEntry(
                                blueprint.id,
                                blueprint.name,
                                blueprint.deltas,
                                blueprint.blockCount
                            )
                        );
                    }
                    BlueprintList.sendToPlayer(player, response);
                }
                case SAVE -> {
                    if (state.config.placeMode != PlaceMode.COPYING || state.config.isBlueprint()) { throw new BlueprintFailure(BlueprintStatus.REGION); }
                    validateBlueprintName(data.name);
                    if (BlueprintStore.list(world, owner).size() >= BlueprintStore.MAX_ENTRIES) { throw new BlueprintFailure(BlueprintStatus.QUOTA); }
                    validateCapture(player, manipulator, state);
                    StructureBlueprint blueprint = StructureBlueprint.capture(
                        BlockAnalyzer.analyzeRegion(world, state.config.coordA, state.config.coordB, true),
                        data.name
                    );
                    BlueprintStore.save(world, owner, blueprint);
                }
                case SELECT, FETCH -> {
                    BlueprintProtocol.validateId(data.id);
                    // The authenticated player's UUID is the only owner used, including for lazy fetches.
                    StructureBlueprint blueprint = BlueprintStore.load(world, owner, data.id);
                    if (blueprint == null) throw new BlueprintFailure(BlueprintStatus.NOT_FOUND);
                    byte[] json = BlueprintCodec.encode(blueprint).getBytes(StandardCharsets.UTF_8);
                    if (data.operation == BlueprintOperation.SELECT) {
                        state.config.blueprintId = blueprint.id;
                        state.config.blueprintOwner = owner.toString();
                        state.config.blueprintDeltas = new Vector3i(blueprint.deltas);
                        state.config.placeMode = PlaceMode.COPYING;
                        state.config.coordC = null;
                        state.config.action = PendingAction.MARK_PASTE;
                        state.config.coordAOffset = null;
                        state.config.coordBOffset = null;
                        state.config.coordCOffset = null;
                        state.config.arraySpan = null;
                        ItemMatterManipulator.setState(held, state);
                    }
                    for (int offset = 0; offset < json.length; offset += BlueprintProtocol.CHUNK_BYTES) {
                        BlueprintDataPacket response = new BlueprintDataPacket(BlueprintData);
                        response.request = data.request;
                        response.owner = owner.toString();
                        response.id = blueprint.id;
                        response.total = json.length;
                        response.offset = offset;
                        response.data = Arrays.copyOfRange(json, offset, Math.min(json.length, offset + BlueprintProtocol.CHUNK_BYTES));
                        BlueprintData.sendToPlayer(player, response);
                    }
                }
                case MATERIALS -> {
                    BlueprintProtocol.validateId(data.id);
                    BlueprintProtocol.validateMaterialPage(data.page, data.pageSize);
                    StructureBlueprint blueprint = BlueprintStore.load(world, owner, data.id);
                    if (blueprint == null) throw new BlueprintFailure(BlueprintStatus.NOT_FOUND);
                    List<BlueprintMaterials.Entry> materials = BlueprintMaterials.analyze(player, state, blueprint);
                    int start = BlueprintProtocol.materialOffset(data.page, data.pageSize, materials.size());
                    BlueprintMaterialListPacket response = new BlueprintMaterialListPacket(BlueprintMaterialList);
                    response.request = data.request;
                    response.id = blueprint.id;
                    response.page = data.page;
                    response.pageSize = data.pageSize;
                    response.totalTypes = materials.size();
                    response.entries.addAll(materials.subList(start, Math.min(materials.size(), start + data.pageSize)));
                    BlueprintMaterialList.sendToPlayer(player, response);
                }
                case BOOKMARKS -> {
                    BlueprintProtocol.validateId(data.id);
                    StructureBlueprint blueprint = BlueprintStore.load(world, owner, data.id);
                    if (blueprint == null) throw new BlueprintFailure(BlueprintStatus.NOT_FOUND);
                    // Finish analysis and bounded encoding before sending the first chunk. Never export just a visible page.
                    byte[] snapshot = BlueprintMaterialExport.encode(BlueprintMaterials.export(player, state, blueprint));
                    for (int offset = 0; offset < snapshot.length; offset += BlueprintProtocol.CHUNK_BYTES) {
                        BlueprintMaterialExportPacket response = new BlueprintMaterialExportPacket(BlueprintMaterialExportData);
                        response.request = data.request;
                        response.id = blueprint.id;
                        response.total = snapshot.length;
                        response.offset = offset;
                        response.data = Arrays.copyOfRange(snapshot, offset, Math.min(snapshot.length, offset + BlueprintProtocol.CHUNK_BYTES));
                        BlueprintMaterialExportData.sendToPlayer(player, response);
                    }
                }
                case RENAME -> {
                    BlueprintProtocol.validateId(data.id);
                    validateBlueprintName(data.name);
                    if (BlueprintStore.load(world, owner, data.id) == null) throw new BlueprintFailure(BlueprintStatus.NOT_FOUND);
                    BlueprintStore.rename(world, owner, data.id, data.name);
                }
                case DELETE -> {
                    BlueprintProtocol.validateId(data.id);
                    BlueprintStore.delete(world, owner, data.id);
                    if (data.id.equals(state.config.blueprintId) && owner.toString().equals(state.config.blueprintOwner)) {
                        state.config.clearBlueprint();
                        state.config.action = null;
                        ItemMatterManipulator.setState(held, state);
                    }
                }
            }
        } catch (BlueprintFailure e) {
            result = e.status;
        } catch (IllegalArgumentException e) {
            MMMod.LOG.warn("Rejected blueprint " + packet.data.operation + " request for " + player.getUniqueID(), e);
            result = BlueprintStatus.INVALID;
            detail = BlueprintProtocol.errorDetail(e);
        } catch (IOException e) {
            MMMod.LOG.warn("Blueprint storage request failed for " + player.getUniqueID(), e);
            result = BlueprintStatus.STORAGE;
        } catch (RuntimeException e) {
            MMMod.LOG.error("Blueprint request failed for " + player.getUniqueID(), e);
            result = BlueprintStatus.STORAGE;
        }
        sendBlueprintResult(player, packet.data.request, result, detail);
    }

    private static void validateBlueprintName(String name) {
        try {
            BlueprintProtocol.validateName(name);
        } catch (IllegalArgumentException e) {
            throw new BlueprintFailure(BlueprintStatus.INVALID_NAME);
        }
    }

    private static void validateCapture(EntityPlayerMP player, ItemMatterManipulator manipulator, MMState state) {
        Location a = state.config.coordA, b = state.config.coordB;
        World world = player.worldObj;
        if (
            !Location.areCompatible(a, b) || a.worldId != world.provider.dimensionId ||
                a.y < 0 ||
                b.y < 0 ||
                a.y >= world.getHeight() ||
                b.y >= world.getHeight() ||
                Math.abs((long) a.x) > 30000000 ||
                Math.abs((long) b.x) > 30000000 ||
                Math.abs((long) a.z) > 30000000 ||
                Math.abs((long) b.z) > 30000000
        ) {
            throw new BlueprintFailure(BlueprintStatus.REGION);
        }
        try {
            BlueprintProtocol.checkedVolume((long) b.x - a.x, (long) b.y - a.y, (long) b.z - a.z);
        } catch (IllegalArgumentException e) {
            throw new BlueprintFailure(BlueprintStatus.VOLUME);
        }
        int minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x);
        int minZ = Math.min(a.z, b.z), maxZ = Math.max(a.z, b.z);
        // chunkExists does not obtain/generate chunks. Never call getChunkFromChunkCoords during validation.
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (!world.getChunkProvider().chunkExists(x, z)) throw new BlueprintFailure(BlueprintStatus.UNLOADED);
            }
        }
        if (manipulator.tier.maxRange >= 0) {
            double range2 = (double) manipulator.tier.maxRange * manipulator.tier.maxRange;
            // The farthest point of an axis-aligned box is a corner, so all eight corners suffice.
            for (int x : new int[] {
                a.x, b.x
            }) {
                for (int y : new int[] {
                    a.y, b.y
                }) {
                    for (int z : new int[] {
                        a.z, b.z
                    }) {
                        double dx = x - player.posX, dy = y - player.posY, dz = z - player.posZ;
                        if (dx * dx + dy * dy + dz * dz > range2) throw new BlueprintFailure(BlueprintStatus.RANGE);
                    }
                }
            }
        }
    }

    private static final class BlueprintFailure extends RuntimeException {

        private final BlueprintStatus status;

        private BlueprintFailure(BlueprintStatus status) {
            this.status = status;
        }
    }

    private static final class BlueprintRequestPacket extends SimplePacket {

        private BlueprintRequestData data;
        private BlueprintOperation operation;

        private BlueprintRequestPacket(Messages message, BlueprintRequestData data) {
            super(message);
            this.data = data;
            operation = data == null ? null : data.operation;
        }

        @Override
        public void encode(ByteBuf buffer) {
            buffer.writeByte(data.operation.ordinal());
            buffer.writeInt(data.request);
            BlueprintProtocol.writeText(buffer, data.id, 36);
            BlueprintProtocol.writeText(buffer, data.name, BlueprintProtocol.MAX_NAME_BYTES);
            if (data.operation == BlueprintOperation.MATERIALS) {
                BlueprintProtocol.validateMaterialPage(data.page, data.pageSize);
                buffer.writeInt(data.page);
                buffer.writeByte(data.pageSize);
            }
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            int operation = buffer.readUnsignedByte();
            if (operation >= BlueprintOperation.values().length) throw new IllegalArgumentException("Invalid blueprint operation");
            int request = buffer.readInt();
            String id = BlueprintProtocol.readText(buffer, 36);
            String name = BlueprintProtocol.readText(buffer, BlueprintProtocol.MAX_NAME_BYTES);
            BlueprintOperation action = BlueprintOperation.values()[operation];
            int page = action == BlueprintOperation.MATERIALS ? buffer.readInt() : 0;
            int pageSize = action == BlueprintOperation.MATERIALS ? buffer.readUnsignedByte() : 8;
            if (action == BlueprintOperation.MATERIALS) BlueprintProtocol.validateMaterialPage(page, pageSize);
            return new BlueprintRequestPacket(message, new BlueprintRequestData(action, request, id, name, page, pageSize));
        }
    }

    private static final class BlueprintListPacket extends SimplePacket {

        private int request;
        private final List<BlueprintEntry> entries = new ArrayList<>();

        private BlueprintListPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            if (entries.size() > BlueprintStore.MAX_ENTRIES) throw new IllegalArgumentException("Too many blueprint entries");
            buffer.writeInt(request);
            buffer.writeShort(entries.size());
            for (BlueprintEntry entry : entries) {
                BlueprintProtocol.validateName(entry.name);
                BlueprintProtocol.writeText(buffer, entry.id, 36);
                BlueprintProtocol.writeText(buffer, entry.name, BlueprintProtocol.MAX_NAME_BYTES);
                buffer.writeInt(entry.deltas.x);
                buffer.writeInt(entry.deltas.y);
                buffer.writeInt(entry.deltas.z);
                buffer.writeInt(entry.blockCount);
            }
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            BlueprintListPacket packet = new BlueprintListPacket(message);
            packet.request = buffer.readInt();
            int count = buffer.readUnsignedShort();
            if (count > BlueprintStore.MAX_ENTRIES) throw new IllegalArgumentException("Too many blueprint entries");
            for (int i = 0; i < count; i++) {
                String id = BlueprintProtocol.readText(buffer, 36), name = BlueprintProtocol.readText(buffer, BlueprintProtocol.MAX_NAME_BYTES);
                BlueprintProtocol.validateId(id);
                BlueprintProtocol.validateName(name);
                Vector3i deltas = new Vector3i(buffer.readInt(), buffer.readInt(), buffer.readInt());
                BlueprintProtocol.checkedVolume(deltas.x, deltas.y, deltas.z);
                int blocks = buffer.readInt();
                if (blocks < 0 || blocks > BlueprintCodec.MAX_BLOCKS) throw new IllegalArgumentException("Invalid blueprint block count");
                packet.entries.add(new BlueprintEntry(id, name, deltas, blocks));
            }
            return packet;
        }
    }

    private static final class BlueprintMaterialListPacket extends SimplePacket {

        private int request, page, pageSize, totalTypes;
        private String id;
        private final List<BlueprintMaterials.Entry> entries = new ArrayList<>();

        private BlueprintMaterialListPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            BlueprintProtocol.validateId(id);
            int start = BlueprintProtocol.materialOffset(page, pageSize, totalTypes);
            if (entries.size() != Math.min(pageSize, totalTypes - start)) throw new IllegalArgumentException("Invalid material page size");
            buffer.writeInt(request);
            BlueprintProtocol.writeText(buffer, id, 36);
            buffer.writeInt(page);
            buffer.writeByte(pageSize);
            buffer.writeInt(totalTypes);
            buffer.writeByte(entries.size());
            for (BlueprintMaterials.Entry entry : entries)
                BlueprintProtocol.writeMaterial(buffer, entry);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            BlueprintMaterialListPacket packet = new BlueprintMaterialListPacket(message);
            packet.request = buffer.readInt();
            packet.id = BlueprintProtocol.readText(buffer, 36);
            BlueprintProtocol.validateId(packet.id);
            packet.page = buffer.readInt();
            packet.pageSize = buffer.readUnsignedByte();
            packet.totalTypes = buffer.readInt();
            int start = BlueprintProtocol.materialOffset(packet.page, packet.pageSize, packet.totalTypes);
            int count = buffer.readUnsignedByte();
            if (count != Math.min(packet.pageSize, packet.totalTypes - start)) throw new IllegalArgumentException("Invalid material page size");
            for (int i = 0; i < count; i++)
                packet.entries.add(BlueprintProtocol.readMaterial(buffer));
            return packet;
        }
    }

    private static final class BlueprintMaterialExportPacket extends SimplePacket {

        private int request, total, offset;
        private String id;
        private byte[] data;

        private BlueprintMaterialExportPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            BlueprintProtocol.validateId(id);
            BlueprintProtocol.validateChunk(total, offset, data.length);
            buffer.writeInt(request);
            BlueprintProtocol.writeText(buffer, id, 36);
            buffer.writeInt(total);
            buffer.writeInt(offset);
            buffer.writeShort(data.length);
            buffer.writeBytes(data);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            BlueprintMaterialExportPacket packet = new BlueprintMaterialExportPacket(message);
            packet.request = buffer.readInt();
            packet.id = BlueprintProtocol.readText(buffer, 36);
            BlueprintProtocol.validateId(packet.id);
            packet.total = buffer.readInt();
            packet.offset = buffer.readInt();
            int size = buffer.readUnsignedShort();
            BlueprintProtocol.validateChunk(packet.total, packet.offset, size);
            if (size != buffer.readableBytes()) throw new IllegalArgumentException("Invalid material export chunk length");
            packet.data = new byte[size];
            buffer.readBytes(packet.data);
            return packet;
        }
    }

    private static final class BlueprintDataPacket extends SimplePacket {

        private int request, total, offset;
        private String owner, id;
        private byte[] data;

        private BlueprintDataPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            BlueprintProtocol.validateChunk(total, offset, data.length);
            buffer.writeInt(request);
            BlueprintProtocol.writeText(buffer, owner, 36);
            BlueprintProtocol.writeText(buffer, id, 36);
            buffer.writeInt(total);
            buffer.writeInt(offset);
            buffer.writeShort(data.length);
            buffer.writeBytes(data);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            BlueprintDataPacket packet = new BlueprintDataPacket(message);
            packet.request = buffer.readInt();
            packet.owner = BlueprintProtocol.readText(buffer, 36);
            packet.id = BlueprintProtocol.readText(buffer, 36);
            BlueprintProtocol.validateId(packet.owner);
            BlueprintProtocol.validateId(packet.id);
            packet.total = buffer.readInt();
            packet.offset = buffer.readInt();
            int size = buffer.readUnsignedShort();
            BlueprintProtocol.validateChunk(packet.total, packet.offset, size);
            if (size > buffer.readableBytes()) throw new IllegalArgumentException("Truncated blueprint chunk");
            packet.data = new byte[size];
            buffer.readBytes(packet.data);
            return packet;
        }
    }

    private static final class BlueprintResultPacket extends SimplePacket {

        private int request;
        private BlueprintStatus result;
        private String detail = "";

        private BlueprintResultPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            buffer.writeInt(request);
            buffer.writeByte(result.ordinal());
            BlueprintProtocol.writeText(buffer, detail, BlueprintProtocol.MAX_ERROR_BYTES);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            BlueprintResultPacket packet = new BlueprintResultPacket(message);
            packet.request = buffer.readInt();
            int status = buffer.readUnsignedByte();
            if (status >= BlueprintStatus.values().length) throw new IllegalArgumentException("Invalid blueprint result");
            packet.result = BlueprintStatus.values()[status];
            if (buffer.isReadable()) packet.detail = BlueprintProtocol.readText(buffer, BlueprintProtocol.MAX_ERROR_BYTES);
            return packet;
        }
    }

    private static final class BlueprintQueue {

        private final ArrayDeque<BlueprintTask> tasks = new ArrayDeque<>();
        private final int capacity;

        private BlueprintQueue(int capacity) {
            this.capacity = capacity;
        }

        synchronized boolean offer(EntityPlayerMP player, Runnable task) {
            if (tasks.size() >= capacity) return false;
            tasks.addLast(new BlueprintTask(player, task));
            return true;
        }

        void drain(int limit) {
            for (int i = 0; i < limit; i++) {
                BlueprintTask task;
                synchronized (this) {
                    task = tasks.pollFirst();
                }
                if (task == null) return;
                try {
                    task.action.run();
                } catch (RuntimeException e) {
                    MMMod.LOG.error("Blueprint queued task failed", e);
                }
            }
        }

        synchronized void remove(EntityPlayer player) {
            tasks.removeIf(task -> task.player == player);
        }

        synchronized void clear() {
            tasks.clear();
        }
    }

    private static final class BlueprintTask {

        private final EntityPlayerMP player;
        private final Runnable action;

        private BlueprintTask(EntityPlayerMP player, Runnable action) {
            this.player = player;
            this.action = action;
        }
    }

    /** Call from the parent mod's serverStopped lifecycle hook; logout cleanup is registered automatically. */
    public static void clearServerTasks() {
        synchronized (BlueprintTasks.class) {
            BlueprintTasks.SERVER.clear();
            BlueprintTasks.FEEDBACK.clear();
            BlueprintTasks.RATES.clear();
        }
    }

    /** Registered on FML's event bus by init(); all storage, world scans and client mutations run on tick threads. */
    public static final class BlueprintTasks {

        private static boolean initialized;
        private static final BlueprintQueue SERVER = new BlueprintQueue(256);
        // Rejections must not depend on work-queue capacity or send shared-channel packets from Netty callbacks.
        private static final BlueprintQueue FEEDBACK = new BlueprintQueue(128);
        // Two full 128-chunk transfers plus their result/list packets must fit before the next client tick.
        private static final BlueprintQueue CLIENT = new BlueprintQueue(512);
        private static final Map<EntityPlayerMP, BlueprintRate> RATES = new WeakHashMap<>();

        private static synchronized void submit(EntityPlayerMP player, BlueprintRequestPacket packet) {
            BlueprintRate rate = RATES.computeIfAbsent(player, ignored -> new BlueprintRate());
            long now = System.currentTimeMillis();
            if (rate.accept(packet.operation, now) && SERVER.offer(player, () -> handleBlueprint(player, packet))) return;
            // Lazy fetches retry. Explicit requests get prompt feedback, capped independently to prevent reply floods.
            if (packet.operation != BlueprintOperation.FETCH && rate.allowFeedback(now)) {
                FEEDBACK.offer(player, () -> {
                    if (isOnline(player)) sendBlueprintResult(player, packet.data.request, BlueprintStatus.BUSY);
                });
            }
        }

        private static synchronized void submitPlan(EntityPlayerMP player, int flags) {
            BlueprintRate rate = RATES.computeIfAbsent(player, ignored -> new BlueprintRate());
            long now = System.currentTimeMillis();
            if (rate.acceptPlan(now) && SERVER.offer(player, () -> handleRequiredItems(player, flags))) return;
            if (rate.allowFeedback(now)) {
                FEEDBACK.offer(player, () -> {
                    if (isOnline(player)) MMUtils.sendErrorToPlayer(player, "mm.blueprint.result.busy");
                });
            }
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            synchronized (BlueprintTasks.class) {
                SERVER.remove(event.player);
                FEEDBACK.remove(event.player);
                RATES.remove(event.player);
            }
        }

        @SubscribeEvent
        public void serverTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            FEEDBACK.drain(16);
            SERVER.drain(8);
        }

        @SideOnly(Side.CLIENT)
        private static void enqueueClient(Runnable task) {
            World world = Minecraft.getMinecraft().theWorld;
            CLIENT.offer(null, () -> {
                if (world != null && world == Minecraft.getMinecraft().theWorld) task.run();
            });
        }

        @SubscribeEvent
        @SideOnly(Side.CLIENT)
        public void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            BlueprintClientCache.checkWorld(Minecraft.getMinecraft().theWorld);
            CLIENT.drain(256);
        }

        @SubscribeEvent
        @SideOnly(Side.CLIENT)
        public void disconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            CLIENT.clear();
            BlueprintClientCache.clearCache();
        }
    }

    private ISimplePacketHandler<? extends SimplePacket> handler;

    private <T extends SimplePacket> Messages(ISimplePacketHandler<T> handler) {
        this.handler = handler;
    }

    public SimplePacket getNewPacket() {
        return handler.getNewPacket(this, null);
    }

    public SimplePacket getNewPacket(Object data) {
        return handler.getNewPacket(this, data);
    }

    public void sendToServer() {
        sendToServer(null);
    }

    public void sendToServer(Object data) {
        if (DebugConfig.debug) {
            MMMod.LOG.info("Sending packet to server: " + this + "; " + data);
        }
        CHANNEL.sendToServer(getNewPacket(data));
    }

    public void sendToPlayer(EntityPlayerMP player) {
        sendToPlayer(player, null);
    }

    public void sendToPlayer(EntityPlayerMP player, Object data) {
        if (DebugConfig.debug) {
            MMMod.LOG.info("Sending packet to player: " + this + "; " + data + "; " + player);
        }
        CHANNEL.sendToPlayer(getNewPacket(data), player);
    }

    public void sendToPlayersAround(Location location) {
        sendToPlayersAround(location, null);
    }

    public void sendToPlayersAround(Location location, Object data) {
        if (DebugConfig.debug) {
            MMMod.LOG
                .info("Sending packet to players around " + location.toString() + ": " + this + "; " + data);
        }
        CHANNEL.sendToAllAround(
            getNewPacket(data),
            new TargetPoint(location.worldId, location.x, location.y, location.z, 256d)
        );
    }

    public void sendToPlayersWithinRange(Location location, Object data) {
        if (DebugConfig.debug) {
            MMMod.LOG
                .info("Sending packet to players that are watching " + location.toString() + ": " + this + "; " + data);
        }

        World world = location.getWorld();

        MMPacket packet = getNewPacket(data);

        for (EntityPlayer p : world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) p;

            Chunk chunk = world.getChunkFromBlockCoords(location.x, location.z);
            if (player.getServerForPlayer().getPlayerManager().isPlayerWatchingChunk(player, chunk.xPosition, chunk.zPosition)) {
                CHANNEL.sendToPlayer(packet, player);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void handle(EntityPlayer player, SimplePacket packet) {
        if (DebugConfig.debug) {
            MMMod.LOG
                .info("Handling packet: " + this + "; " + packet + "; " + player + "; " + NetworkUtils.isClient());
        }
        ((ISimplePacketHandler<SimplePacket>) handler).handle(player, packet);
    }

    private static final Network CHANNEL = createNetwork();

    private static Network createNetwork() {
        ArrayList<MMPacket> packets = new ArrayList<>();

        for (Messages message : values()) {
            try {
                packets.add(message.getNewPacket());
            } catch (Throwable t) {
                throw new RuntimeException("Could not construct packet in createNetwork", t);
            }
        }

        return new Network("MatterManipulator", packets.toArray(new MMPacket[0]));
    }

    /**
     * Makes sure this class is loaded (initializes {@link #CHANNEL}).
     */
    public static synchronized void init() {
        if (BlueprintTasks.initialized) return;
        BlueprintTasks.initialized = true;
        FMLCommonHandler.instance().bus().register(new BlueprintTasks());
    }

    /**
     * Something that can handle and create simple packets.
     */
    private static interface ISimplePacketHandler<T extends SimplePacket> {

        public void handle(EntityPlayer player, T packet);

        /**
         * Gets a new packet. Called once on initialization with {@code data = null}.
         */
        public T getNewPacket(Messages message, @Nullable Object data);
    }

    /**
     * A packet that doesn't contain any data.
     */
    private static class SimplePacket extends MMPacket {

        public final Messages message;

        public SimplePacket(Messages message) {
            this.message = message;
        }

        @Override
        public byte getPacketID() {
            return (byte) message.ordinal();
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            return message.getNewPacket();
        }

        @Override
        public void encode(ByteBuf buffer) {

        }

        private EntityPlayerMP playerMP;

        @Override
        public void setINetHandler(INetHandler handler) {
            playerMP = handler instanceof NetHandlerPlayServer server ? server.playerEntity : null;
        }

        @Override
        public void process(IBlockAccess world) {
            message.handle(playerMP, this);
        }
    }

    /**
     * A packet that contains a single int.
     */
    private static class IntPacket extends SimplePacket {

        public int value;

        public IntPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            buffer.writeInt(value);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            IntPacket message = new IntPacket(super.message);
            message.value = buffer.readInt();
            return message;
        }

        @Override
        public String toString() {
            return "IntPacket [message=" + message
                + ", value="
                + value
                + " (0b"
                + Integer.toBinaryString(value)
                + ")"
                + "]";
        }
    }

    /**
     * A packet that contains the data needed for updating an uplink.
     */
    private static class UplinkPacket extends SimplePacket {

        public int worldId;
        public long location;
        public byte state;

        public UplinkPacket(Messages message) {
            super(message);
        }

        public void setState(Location location, UplinkState state) {
            this.worldId = location.worldId;
            this.location = CoordinatePacker.pack(location.x, location.y, location.z);
            this.state = (byte) state.ordinal();
        }

        public Location getLocation() {
            return new Location(
                worldId,
                CoordinatePacker.unpackX(location),
                CoordinatePacker.unpackY(location),
                CoordinatePacker.unpackZ(location)
            );
        }

        public UplinkState getState() {
            return UplinkState.values()[state];
        }

        @Override
        public void encode(ByteBuf buffer) {
            buffer.writeInt(worldId);
            buffer.writeLong(location);
            buffer.writeByte(state);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            UplinkPacket message = new UplinkPacket(super.message);
            message.worldId = buffer.readInt();
            message.location = buffer.readLong();
            message.state = buffer.readByte();
            return message;
        }
    }

    /**
     * Wraps a handler that must be called on the server.
     */
    private static <T extends SimplePacket> ISimplePacketHandler<T> server(ISimplePacketHandler<T> next) {
        return new ISimplePacketHandler<T>() {

            @Override
            public void handle(EntityPlayer player, T packet) {
                if (player == null) {
                    MMMod.LOG
                        .error("Client received server packet, it will be ignored: " + packet.message.name());
                    return;
                }

                next.handle(player, packet);
            }

            @Override
            public T getNewPacket(Messages message, @Nullable Object data) {
                return next.getNewPacket(message, data);
            }
        };
    }

    /**
     * Wraps a handler that must be called on the client.
     */
    private static <T extends SimplePacket> ISimplePacketHandler<T> client(ISimplePacketHandler<T> next) {
        return new ISimplePacketHandler<T>() {

            @Override
            public void handle(EntityPlayer player, T packet) {
                if (player != null) {
                    MMMod.LOG
                        .error("Server received client packet, it will be ignored: " + packet.message.name());
                    return;
                }

                handleImpl(packet);
            }

            @SideOnly(Side.CLIENT)
            private void handleImpl(T packet) {
                next.handle(Minecraft.getMinecraft().thePlayer, packet);
            }

            @Override
            public T getNewPacket(Messages message, @Nullable Object data) {
                return next.getNewPacket(message, data);
            }
        };
    }

    private static interface ISimpleHandler {

        public void handle(EntityPlayer player, ItemStack stack, ItemMatterManipulator manipulator, MMState state);
    }

    /**
     * Handles all manipulator-related state loading & saving.
     * Useful for packets that only change a manipulator's state.
     */
    private static ISimplePacketHandler<SimplePacket> simple(ISimpleHandler handler) {
        return new ISimplePacketHandler<Messages.SimplePacket>() {

            @Override
            public void handle(EntityPlayer player, SimplePacket packet) {
                ItemStack held = player.inventory.getCurrentItem();

                if (held != null && held.getItem() instanceof ItemMatterManipulator manipulator) {
                    MMState state = ItemMatterManipulator.getState(held);

                    handler.handle(player, held, manipulator, state);

                    ItemMatterManipulator.setState(held, state);
                }
            }

            @Override
            public SimplePacket getNewPacket(Messages message, @Nullable Object unused) {
                return new SimplePacket(message);
            }
        };
    }

    /**
     * Wraps a handler that updates an enum within a manipulator's state.
     */
    private static <E extends Enum<E>> ISimplePacketHandler<IntPacket> enumPacket(
        E[] values,
        BiConsumer<MMState, E> setter
    ) {
        return new ISimplePacketHandler<IntPacket>() {

            @Override
            public void handle(EntityPlayer player, IntPacket packet) {
                E value = packet.value < 0 || packet.value >= values.length ? null : values[packet.value];

                ItemStack held = player.inventory.getCurrentItem();

                if (held != null && held.getItem() instanceof ItemMatterManipulator) {
                    MMState state = ItemMatterManipulator.getState(held);

                    setter.accept(state, value);

                    ItemMatterManipulator.setState(held, state);
                }
            }

            @SuppressWarnings("unchecked")
            @Override
            public IntPacket getNewPacket(Messages message, @Nullable Object value) {
                IntPacket packet = new IntPacket(message);
                packet.value = value == null ? -1 : ((E) value).ordinal();
                return packet;
            }
        };
    }

    private static interface IEnumSetter<E extends Enum<E>> {

        public void set(
            EntityPlayer player,
            ItemStack stack,
            ItemMatterManipulator manipulator,
            MMState state,
            E value
        );
    }

    /**
     * Wraps a handler that updates an enum. (provides more params than normal)
     */
    private static <E extends Enum<E>> ISimplePacketHandler<IntPacket> enumPacket(E[] values, IEnumSetter<E> setter) {
        return new ISimplePacketHandler<IntPacket>() {

            @Override
            public void handle(EntityPlayer player, IntPacket packet) {
                E value = packet.value < 0 || packet.value >= values.length ? null : values[packet.value];

                ItemStack held = player.inventory.getCurrentItem();

                if (held != null && held.getItem() instanceof ItemMatterManipulator manipulator) {
                    MMState state = ItemMatterManipulator.getState(held);

                    setter.set(player, held, manipulator, state, value);

                    ItemMatterManipulator.setState(held, state);
                }
            }

            @SuppressWarnings("unchecked")
            @Override
            public IntPacket getNewPacket(Messages message, @Nullable Object value) {
                IntPacket packet = new IntPacket(message);
                packet.value = value == null ? -1 : ((E) value).ordinal();
                return packet;
            }
        };
    }

    private static interface IIntSetter {

        public void set(
            EntityPlayer player,
            ItemStack stack,
            ItemMatterManipulator manipulator,
            MMState state,
            int value
        );
    }

    private static ISimplePacketHandler<IntPacket> intPacket(IIntSetter setter) {
        return new ISimplePacketHandler<Messages.IntPacket>() {

            @Override
            public void handle(EntityPlayer player, IntPacket packet) {
                ItemStack held = player.inventory.getCurrentItem();

                if (held != null && held.getItem() instanceof ItemMatterManipulator manipulator) {
                    MMState state = ItemMatterManipulator.getState(held);

                    setter.set(player, held, manipulator, state, packet.value);

                    ItemMatterManipulator.setState(held, state);
                }
            }

            @Override
            public IntPacket getNewPacket(Messages message, @Nullable Object value) {
                IntPacket packet = new IntPacket(message);
                packet.value = value == null ? 0 : (int) (Integer) value;
                return packet;
            }
        };
    }

    public static void sendSoundToPlayer(EntityPlayerMP player, World world, int x, int y, int z, SoundResource sound, float strength, float pitch) {
        SoundPacket packet = new SoundPacket(Messages.PlaySound);

        packet.worldId = world.provider.dimensionId;
        packet.location = CoordinatePacker.pack(x, y, z);
        packet.sound = sound.ordinal();
        packet.strength = strength;
        packet.pitch = pitch;

        CHANNEL.sendToPlayer(packet, player);
    }

    public static void sendSoundToAllWithinRange(World world, int x, int y, int z, SoundResource sound, float strength, float pitch) {
        SoundPacket packet = new SoundPacket(Messages.PlaySound);

        packet.worldId = world.provider.dimensionId;
        packet.location = CoordinatePacker.pack(x, y, z);
        packet.sound = sound.ordinal();
        packet.strength = strength;
        packet.pitch = pitch;

        for (EntityPlayer p : world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) p;

            Chunk chunk = world.getChunkFromBlockCoords(x, z);
            if (player.getServerForPlayer().getPlayerManager().isPlayerWatchingChunk(player, chunk.xPosition, chunk.zPosition)) {
                CHANNEL.sendToPlayer(packet, player);
            }
        }
    }

    private static class SoundPacket extends SimplePacket {

        public int worldId;
        public long location;
        public int sound;
        public float strength, pitch;

        public SoundPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            buffer.writeInt(worldId);
            buffer.writeLong(location);
            buffer.writeInt(sound);
            buffer.writeFloat(strength);
            buffer.writeFloat(pitch);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            SoundPacket message = new SoundPacket(super.message);

            message.worldId = buffer.readInt();
            message.location = buffer.readLong();
            message.sound = buffer.readInt();
            message.strength = buffer.readFloat();
            message.pitch = buffer.readFloat();

            return message;
        }
    }

    private static class LocationPacket extends SimplePacket {

        public long location;

        public LocationPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            buffer.writeLong(location);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            LocationPacket message = new LocationPacket(super.message);

            message.location = buffer.readLong();

            return message;
        }
    }

    private static interface ILocationSetter {

        public void set(
            EntityPlayer player,
            ItemStack stack,
            ItemMatterManipulator manipulator,
            MMState state,
            Vector3i location
        );
    }

    private static ISimplePacketHandler<LocationPacket> locationPacket(ILocationSetter setter) {
        return new ISimplePacketHandler<Messages.LocationPacket>() {

            @Override
            public void handle(EntityPlayer player, LocationPacket packet) {
                ItemStack held = player.inventory.getCurrentItem();

                if (held != null && held.getItem() instanceof ItemMatterManipulator manipulator) {
                    MMState state = ItemMatterManipulator.getState(held);

                    Vector3i v = new Vector3i(
                        CoordinatePacker.unpackX(packet.location),
                        CoordinatePacker.unpackY(packet.location),
                        CoordinatePacker.unpackZ(packet.location)
                    );

                    setter.set(player, held, manipulator, state, v);

                    ItemMatterManipulator.setState(held, state);
                }
            }

            @Override
            public LocationPacket getNewPacket(Messages message, @Nullable Object value) {
                LocationPacket packet = new LocationPacket(message);

                if (value instanceof Location l) {
                    packet.location = CoordinatePacker.pack(l.x, l.y, l.z);
                } else if (value instanceof Vector3i v) {
                    packet.location = CoordinatePacker.pack(v.x, v.y, v.z);
                }

                return packet;
            }
        };
    }

    private static class BuildStatusPacket extends SimplePacket {

        public LongList errors, warnings;

        public BuildStatusPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            buffer.writeInt(errors == null ? 0 : errors.size());

            if (errors != null) {
                int size = errors.size();
                for (int i = 0; i < size; i++) {
                    buffer.writeLong(errors.getLong(i));
                }
            }

            buffer.writeInt(warnings == null ? 0 : warnings.size());

            if (warnings != null) {
                int size = warnings.size();
                for (int i = 0; i < size; i++) {
                    buffer.writeLong(warnings.getLong(i));
                }
            }
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            BuildStatusPacket message = new BuildStatusPacket(super.message);

            int size = buffer.readInt();

            if (size > 0) {
                LongList errors = new LongArrayList();
                errors.size(size);

                for (int i = 0; i < size; i++) {
                    errors.set(i, buffer.readLong());
                }

                message.errors = errors;
            }

            size = buffer.readInt();

            if (size > 0) {
                LongList warnings = new LongArrayList();
                warnings.size(size);

                for (int i = 0; i < size; i++) {
                    warnings.set(i, buffer.readLong());
                }

                message.warnings = warnings;
            }

            return message;
        }
    }

    private static interface ICursorItemStackSetter {

        public void set(
            EntityPlayer player,
            ItemStack mmStack,
            ItemMatterManipulator manipulator,
            MMState state,
            boolean isSneak,
            ItemStack itemStack
        );
    }

    public static class CursorItemStackData {

        ItemStack itemStack;
        boolean isSneak;

        public CursorItemStackData(ItemStack itemStack, boolean isSneak) {
            this.itemStack = itemStack;
            this.isSneak = isSneak;
        }
    }

    private static ISimplePacketHandler<CursorItemStackPacket> cursorItemStackPacket(ICursorItemStackSetter setter) {
        return new ISimplePacketHandler<CursorItemStackPacket>() {

            @Override
            public void handle(EntityPlayer player, CursorItemStackPacket packet) {
                ItemStack picked = player.inventory.getItemStack();

                if (picked != null && picked.getItem() instanceof ItemMatterManipulator manipulator) {
                    MMState state = ItemMatterManipulator.getState(picked);

                    setter.set(player, picked, manipulator, state, packet.isSneak, packet.itemStack);

                    ItemMatterManipulator.setState(picked, state);
                }
            }

            @Override
            public CursorItemStackPacket getNewPacket(Messages message, @Nullable Object value) {
                CursorItemStackPacket packet = new CursorItemStackPacket(message);
                if (value == null) return packet;

                if (value instanceof CursorItemStackData cursorItemStackData) {
                    packet.itemStack = cursorItemStackData.itemStack;
                    packet.isSneak = cursorItemStackData.isSneak;
                }
                return packet;
            }
        };
    }

    private static class CursorItemStackPacket extends SimplePacket {

        public ItemStack itemStack;
        public boolean isSneak;

        public CursorItemStackPacket(Messages message) {
            super(message);
        }

        @Override
        public void encode(ByteBuf buffer) {
            ByteBufUtils.writeItemStack(buffer, itemStack);
            buffer.writeBoolean(isSneak);
        }

        @Override
        public MMPacket decode(ByteBuf buffer) {
            CursorItemStackPacket message = new CursorItemStackPacket(super.message);
            message.itemStack = ByteBufUtils.readItemStack(buffer);
            return message;
        }
    }
}

/** Wire bounds are checked before allocating memory; kept independent of channel initialization for protocol tests. */
final class BlueprintProtocol {

    static final int CHUNK_BYTES = 16 * 1024;
    static final int MAX_ERROR_BYTES = 384;
    static final int MAX_MATERIAL_PAGE_SIZE = 16;
    static final int MAX_MATERIAL_TYPES = 65536;
    static final int MAX_MATERIAL_PACKET_BYTES = 1 + 4 + 2 + 36 + 4 + 1 + 4 + 1 + MAX_MATERIAL_PAGE_SIZE * (2 + 256 + 4 + 2 + 512 + 8 + 1);
    // A valid name has at most 64 UTF-16 code units: BMP characters cost at most 3 UTF-8 bytes per unit.
    static final int MAX_NAME_BYTES = 64 * 3;
    static final int MAX_LIST_PACKET_BYTES = 1 + 4 + 2 + BlueprintStore.MAX_ENTRIES * (2 + 36 + 2 + MAX_NAME_BYTES + 16);

    private BlueprintProtocol() {}

    static void validateMaterialPage(int page, int pageSize) {
        if (page < 0 || page >= MAX_MATERIAL_TYPES || pageSize < 1 || pageSize > MAX_MATERIAL_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid material page request");
        }
    }

    static int materialOffset(int page, int pageSize, int totalTypes) {
        validateMaterialPage(page, pageSize);
        if (totalTypes < 0 || totalTypes > MAX_MATERIAL_TYPES) throw new IllegalArgumentException("Invalid material type count");
        return (int) Math.min((long) totalTypes, (long) page * pageSize);
    }

    static void writeMaterial(ByteBuf buffer, BlueprintMaterials.Entry entry) {
        validateMaterial(entry.registryName, entry.amount);
        writeText(buffer, entry.registryName, 256);
        buffer.writeInt(entry.metadata);
        writeText(buffer, entry.displayName, 512);
        buffer.writeLong(entry.amount);
        buffer.writeBoolean(entry.configured);
    }

    static BlueprintMaterials.Entry readMaterial(ByteBuf buffer) {
        String id = readText(buffer, 256);
        int metadata = buffer.readInt();
        String displayName = readText(buffer, 512);
        long amount = buffer.readLong();
        boolean configured = buffer.readBoolean();
        validateMaterial(id, amount);
        return new BlueprintMaterials.Entry(id, metadata, displayName, amount, configured);
    }

    private static void validateMaterial(String id, long amount) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0 || amount <= 0) {
            throw new IllegalArgumentException("Invalid material entry");
        }
    }

    static long checkedVolume(long x, long y, long z) {
        long volume = 1;
        for (long delta : new long[] {
            x, y, z
        }) {
            if (delta == Long.MIN_VALUE) throw new IllegalArgumentException("Invalid region delta");
            long length = Math.abs(delta) + 1;
            if (length <= 0 || length > BlueprintCodec.MAX_BLOCKS / volume) { throw new IllegalArgumentException("Blueprint region is too large"); }
            volume *= length;
        }
        return volume;
    }

    static void validateChunk(int total, int offset, int size) {
        if (
            total < 1 || total > BlueprintCodec.MAX_BYTES ||
                offset < 0 ||
                offset >= total ||
                offset % CHUNK_BYTES != 0 ||
                size < 1 ||
                size > CHUNK_BYTES ||
                size != Math.min(CHUNK_BYTES, total - offset)
        ) {
            throw new IllegalArgumentException("Invalid blueprint chunk bounds");
        }
    }

    static void validateId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid blueprint UUID");
    }

    static String errorDetail(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        String message = cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
        StringBuilder detail = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < message.length();) {
            int codePoint = message.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || codePoint == '\u00a7' || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) codePoint = ' ';
            int length = codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2 : codePoint <= 0xFFFF ? 3 : 4;
            if (bytes + length > MAX_ERROR_BYTES) break;
            detail.appendCodePoint(codePoint);
            bytes += length;
        }
        return detail.toString();
    }

    static void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 64) throw new IllegalArgumentException("Invalid blueprint name");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("Invalid blueprint name");
            if (Character.isHighSurrogate(c)) {
                if (++i >= name.length() || !Character.isLowSurrogate(name.charAt(i))) { throw new IllegalArgumentException("Malformed blueprint name"); }
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Malformed blueprint name");
        }
    }

    static void writeText(ByteBuf buffer, String value, int maxBytes) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
            if (bytes.remaining() > maxBytes) throw new IllegalArgumentException("Blueprint text is too long");
            buffer.writeShort(bytes.remaining());
            buffer.writeBytes(bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid blueprint UTF-16", e);
        }
    }

    static String readText(ByteBuf buffer, int maxBytes) {
        int size = buffer.readUnsignedShort();
        if (size > maxBytes || size > buffer.readableBytes()) throw new IllegalArgumentException("Invalid blueprint text length");
        byte[] bytes = new byte[size];
        buffer.readBytes(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid blueprint UTF-8", e);
        }
    }
}

/** Per-session limits; SELECT cannot be starved by automatic FETCH or unrelated explicit requests. */
final class BlueprintRate {

    private long window, lastSave, lastPlan, lastExport;
    private boolean saved, planned, exported;
    private int requests, fetches, selections, lists, feedback, materials;

    boolean accept(Messages.BlueprintOperation operation, long now) {
        resetWindow(now);
        if (operation == null) return false;
        if (operation == Messages.BlueprintOperation.SELECT) {
            if (selections >= 2) return false;
            selections++;
            return true;
        }
        if (operation == Messages.BlueprintOperation.BOOKMARKS) {
            if (exported && now - lastExport < 2000) return false;
            lastExport = now;
            exported = true;
            return true;
        }
        if (requests >= 8) return false;
        if (operation == Messages.BlueprintOperation.FETCH && fetches >= 2) return false;
        if (operation == Messages.BlueprintOperation.LIST && lists >= 2) return false;
        if (operation == Messages.BlueprintOperation.MATERIALS && materials >= 2) return false;
        if (operation == Messages.BlueprintOperation.SAVE && saved && now - lastSave < 2000) return false;
        requests++;
        if (operation == Messages.BlueprintOperation.FETCH) fetches++;
        if (operation == Messages.BlueprintOperation.LIST) lists++;
        if (operation == Messages.BlueprintOperation.MATERIALS) materials++;
        if (operation == Messages.BlueprintOperation.SAVE) {
            lastSave = now;
            saved = true;
        }
        return true;
    }

    boolean acceptPlan(long now) {
        if (planned && now - lastPlan < 1000) return false;
        lastPlan = now;
        planned = true;
        return true;
    }

    boolean allowFeedback(long now) {
        resetWindow(now);
        if (feedback >= 2) return false;
        feedback++;
        return true;
    }

    private void resetWindow(long now) {
        if (now < window || now - window >= 1000) {
            window = now;
            requests = fetches = selections = lists = feedback = materials = 0;
        }
    }
}
