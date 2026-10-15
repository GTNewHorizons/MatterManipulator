package com.recursive_pineapple.matter_manipulator.common.networking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.world.IBlockAccess;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class NetworkFramingTest {

    private static LaunchClassLoader gameLoader, previousLoader;
    private static Class<?> fixture;

    @BeforeAll
    static void gameClasses() throws Exception {
        String[] classpath = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < urls.length; i++) urls[i] = new File(classpath[i]).toURI().toURL();
        gameLoader = new LaunchClassLoader(urls);
        gameLoader.addClassLoaderExclusion("org.junit.");
        gameLoader.addClassLoaderExclusion("org.opentest4j.");
        previousLoader = Launch.classLoader;
        Launch.classLoader = gameLoader;
        fixture = Class.forName(NetworkFramingTest.class.getName() + "$Fixture", true, gameLoader);
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
    void exactPayloadSurvivesLocalForgeConversion() throws Exception {
        run("exactPayloadSurvivesLocalForgeConversion");
    }

    @Test
    void exactPayloadSurvivesSerializedForgeRoundTrip() throws Exception {
        run("exactPayloadSurvivesSerializedForgeRoundTrip");
    }

    @Test
    void legacyGrowablePayloadReproducesExactExportDecoderLengthError() throws Exception {
        run("legacyGrowablePayloadReproducesExactExportDecoderLengthError");
    }

    @Test
    void truncatedExportChunksRemainRejected() throws Exception {
        run("truncatedExportChunksRemainRejected");
    }

    @Test
    void oversizedExportChunkDeclarationsRemainRejected() throws Exception {
        run("oversizedExportChunkDeclarationsRemainRejected");
    }

    @Test
    void trailingExportBytesRemainRejected() throws Exception {
        run("trailingExportBytesRemainRejected");
    }

    @Test
    void encodingFailureReleasesTemporaryBuffer() throws Exception {
        run("encodingFailureReleasesTemporaryBuffer");
    }

    public static final class Fixture {

        private static final String CHANNEL = "MatterManipulator";
        private static final String BLUEPRINT_ID = "01234567-89ab-cdef-0123-456789abcdef";
        private static final int PACKET_ID = 46;
        private static final int REQUEST = 0x12345678;
        private static final int TOTAL_INDEX = 1 + 4 + 2 + 36;
        private static final int SIZE_INDEX = TOTAL_INDEX + 4 + 4;
        private static final int HEADER_BYTES = SIZE_INDEX + 2;
        private static final int[][] CHUNKS = {
            { 1, 0 }, { 100, 0 }, { 203, 0 }, { 204, 0 }, { BlueprintProtocol.CHUNK_BYTES, 0 },
            { 7, BlueprintProtocol.CHUNK_BYTES }
        };

        public static void exactPayloadSurvivesLocalForgeConversion() throws Exception {
            exactPayloads(false);
        }

        public static void exactPayloadSurvivesSerializedForgeRoundTrip() throws Exception {
            exactPayloads(true);
        }

        private static void exactPayloads(boolean serialized) throws Exception {
            for (int[] chunk : CHUNKS) {
                ExportPacket packet = new ExportPacket(chunk[0], chunk[1]);
                ByteBuf encoded = Network.encodePayload(packet);
                try {
                    assertEquals(0, packet.encodedInto.refCnt(), "Temporary encoding buffer must be released");
                    assertNotSame(packet.encodedInto, encoded);
                    assertEquals(1, encoded.refCnt());
                    assertEquals(0, encoded.readerIndex());
                    assertEquals(HEADER_BYTES + packet.data.length, encoded.readableBytes());
                    assertEquals(encoded.readableBytes(), encoded.capacity());
                    assertEquals(encoded.readableBytes(), encoded.array().length);
                    ByteBuf received = throughForge(encoded, serialized);
                    try {
                        assertArrayEquals(bytes(encoded), bytes(received));
                        assertDecoded(packet, received);
                    } finally {
                        received.release();
                    }
                } finally {
                    encoded.release();
                }
            }
        }

        public static void legacyGrowablePayloadReproducesExactExportDecoderLengthError() throws Exception {
            for (boolean serialized : new boolean[] { false, true }) {
                for (int[] chunk : CHUNKS) {
                    ExportPacket packet = new ExportPacket(chunk[0], chunk[1]);
                    ByteBuf legacy = Unpooled.buffer();
                    try {
                        legacy.writeByte(packet.getPacketID());
                        packet.encode(legacy);
                        ByteBuf received = throughForge(legacy, serialized);
                        try {
                            if (packet.data.length == 203) {
                                // The 53-byte header and 203-byte body exactly fill the initial 256-byte array.
                                assertEquals(legacy.writerIndex(), legacy.capacity());
                                assertDecoded(packet, received);
                            } else {
                                assertTrue(legacy.capacity() > legacy.writerIndex());
                                assertEquals(legacy.capacity(), received.readableBytes());
                                assertEquals(PACKET_ID, received.readUnsignedByte());
                                assertLengthError(packet, received);
                            }
                        } finally {
                            received.release();
                        }
                    } finally {
                        legacy.release();
                    }
                }
            }
        }

        public static void truncatedExportChunksRemainRejected() throws Exception {
            for (int[] chunk : CHUNKS) {
                ExportPacket packet = new ExportPacket(chunk[0], chunk[1]);
                ByteBuf encoded = Network.encodePayload(packet);
                try {
                    ByteBuf truncated = encoded.copy(encoded.readerIndex(), encoded.readableBytes() - 1);
                    try {
                        assertRejectedThroughForge(packet, truncated, "Invalid material export chunk length");
                    } finally {
                        truncated.release();
                    }
                } finally {
                    encoded.release();
                }
            }
        }

        public static void oversizedExportChunkDeclarationsRemainRejected() throws Exception {
            ExportPacket packet = new ExportPacket(BlueprintProtocol.CHUNK_BYTES, 0);
            ByteBuf encoded = Network.encodePayload(packet);
            try {
                int length = encoded.readableBytes() + 1;
                ByteBuf oversized = Unpooled.buffer(length, length);
                try {
                    oversized.writeBytes(encoded, encoded.readerIndex(), encoded.readableBytes());
                    oversized.writeByte(0x7F);
                    oversized.setInt(TOTAL_INDEX, BlueprintProtocol.CHUNK_BYTES + 1);
                    oversized.setShort(SIZE_INDEX, BlueprintProtocol.CHUNK_BYTES + 1);
                    assertRejectedThroughForge(packet, oversized, "Invalid blueprint chunk bounds");
                } finally {
                    oversized.release();
                }
            } finally {
                encoded.release();
            }
        }

        public static void trailingExportBytesRemainRejected() throws Exception {
            for (int[] chunk : CHUNKS) {
                ExportPacket packet = new ExportPacket(chunk[0], chunk[1]);
                ByteBuf encoded = Network.encodePayload(packet);
                try {
                    int length = encoded.readableBytes() + 1;
                    ByteBuf oversized = Unpooled.buffer(length, length);
                    try {
                        oversized.writeBytes(encoded, encoded.readerIndex(), encoded.readableBytes());
                        oversized.writeByte(0x7F);
                        assertRejectedThroughForge(packet, oversized, "Invalid material export chunk length");
                    } finally {
                        oversized.release();
                    }
                } finally {
                    encoded.release();
                }
            }
        }

        public static void encodingFailureReleasesTemporaryBuffer() throws Exception {
            IllegalStateException failure = new IllegalStateException("Deliberate encoding failure");
            ExportPacket packet = new ExportPacket(100, 0) {

                @Override
                public void encode(ByteBuf buffer) {
                    super.encode(buffer);
                    throw failure;
                }
            };
            assertSame(failure, assertThrows(IllegalStateException.class, () -> Network.encodePayload(packet)));
            assertEquals(0, packet.encodedInto.refCnt());
        }

        private static ByteBuf throughForge(ByteBuf encoded, boolean serialized) throws Exception {
            S3FPacketCustomPayload payload = (S3FPacketCustomPayload) new FMLProxyPacket(encoded, CHANNEL).toS3FPacket();
            if (serialized) {
                PacketBuffer wire = new PacketBuffer(Unpooled.buffer());
                try {
                    payload.writePacketData(wire);
                    payload = new S3FPacketCustomPayload();
                    payload.readPacketData(wire);
                    assertEquals(0, wire.readableBytes());
                } finally {
                    wire.release();
                }
            }
            FMLProxyPacket received = new FMLProxyPacket(payload);
            assertEquals(CHANNEL, received.channel());
            return received.payload();
        }

        private static void assertDecoded(ExportPacket packet, ByteBuf received) throws Exception {
            assertEquals(PACKET_ID, received.readUnsignedByte());
            MMPacket decoded = packet.decode(received);
            assertEquals(REQUEST, ((Integer) field(decoded, "request")).intValue());
            assertEquals(BLUEPRINT_ID, field(decoded, "id"));
            assertEquals(packet.total, ((Integer) field(decoded, "total")).intValue());
            assertEquals(packet.offset, ((Integer) field(decoded, "offset")).intValue());
            assertArrayEquals(packet.data, (byte[]) field(decoded, "data"));
            assertEquals(0, received.readableBytes(), "Export decoder must consume the entire framed payload");
        }

        private static void assertLengthError(ExportPacket packet, ByteBuf received) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> packet.decode(received));
            assertEquals("Invalid material export chunk length", error.getMessage());
        }

        private static void assertRejectedThroughForge(ExportPacket packet, ByteBuf encoded, String message) throws Exception {
            for (boolean serialized : new boolean[] { false, true }) {
                ByteBuf received = throughForge(encoded, serialized);
                try {
                    assertEquals(PACKET_ID, received.readUnsignedByte());
                    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> packet.decode(received));
                    assertEquals(message, error.getMessage());
                } finally {
                    received.release();
                }
            }
        }

        private static byte[] bytes(ByteBuf buffer) {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            return bytes;
        }

        private static Object field(MMPacket packet, String name) throws Exception {
            Field field = packet.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(packet);
        }

        private static class ExportPacket extends MMPacket {

            private final MMPacket delegate;
            private final int total, offset;
            private final byte[] data;
            private ByteBuf encodedInto;

            private ExportPacket(int size, int offset) throws Exception {
                this.offset = offset;
                total = offset + size;
                data = new byte[size];
                for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + i / 251 + offset);
                Class<?> type = Class.forName(Messages.class.getName() + "$BlueprintMaterialExportPacket");
                Constructor<?> constructor = type.getDeclaredConstructor(Messages.class);
                constructor.setAccessible(true);
                // A null message avoids initializing Messages and its live network channel; the wrapper supplies the ID.
                delegate = (MMPacket) constructor.newInstance(new Object[] { null });
                setField("request", REQUEST);
                setField("id", BLUEPRINT_ID);
                setField("total", total);
                setField("offset", offset);
                setField("data", data);
            }

            private void setField(String name, Object value) throws Exception {
                Field field = delegate.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(delegate, value);
            }

            @Override
            public byte getPacketID() {
                return (byte) PACKET_ID;
            }

            @Override
            public void encode(ByteBuf buffer) {
                encodedInto = buffer;
                delegate.encode(buffer);
            }

            @Override
            public MMPacket decode(ByteBuf buffer) {
                return delegate.decode(buffer);
            }

            @Override
            public void process(IBlockAccess world) {}
        }
    }
}
