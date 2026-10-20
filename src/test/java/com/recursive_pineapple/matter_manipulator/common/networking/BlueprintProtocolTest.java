package com.recursive_pineapple.matter_manipulator.common.networking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintCodec;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterialExport;
import com.recursive_pineapple.matter_manipulator.common.blueprint.BlueprintMaterials;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class BlueprintProtocolTest {
    @Test
    void inclusiveVolumeAcceptsReversedAndMaximumSizedRegions() {
        assertEquals(1, BlueprintProtocol.checkedVolume(0, 0, 0));
        assertEquals(BlueprintCodec.MAX_BLOCKS, BlueprintProtocol.checkedVolume(-255, 255, -255));
        assertEquals(BlueprintCodec.MAX_BLOCKS, BlueprintProtocol.checkedVolume(BlueprintCodec.MAX_BLOCKS - 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(256, 255, 255));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(BlueprintCodec.MAX_BLOCKS, 0, 0));
    }

    @Test
    void volumeRejectsOverflowBeforeMultiplication() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(4294967295L, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(Long.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(Long.MIN_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(1000000000, 1000000000, 1000000000));
    }

    @Test
    void chunksAcceptExactMaximumAndShortFinalChunk() {
        int chunk = BlueprintProtocol.CHUNK_BYTES, max = BlueprintCodec.MAX_BYTES;
        for (int offset = 0; offset < max; offset += chunk) BlueprintProtocol.validateChunk(max, offset, chunk);
        assertDoesNotThrow(() -> BlueprintProtocol.validateChunk(chunk + 7, chunk, 7));
        assertDoesNotThrow(() -> BlueprintProtocol.validateChunk(1, 0, 1));
    }

    @Test
    void chunksRejectOversizedMisalignedAndInconsistentBounds() {
        int chunk = BlueprintProtocol.CHUNK_BYTES, max = BlueprintCodec.MAX_BYTES;
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(max + 1, 0, chunk));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(max, 0, chunk + 1));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(max, 1, chunk));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(max, -chunk, chunk));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(max, max, 1));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(5, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateChunk(Integer.MAX_VALUE, Integer.MAX_VALUE - 1, chunk));
    }

    @Test
    void boundedTextRoundTripsUnicodeUsingByteLengths() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            BlueprintProtocol.writeText(buffer, "\u5efa\u7bc9 \uD83C\uDFE0", 32);
            assertEquals("\u5efa\u7bc9 \uD83C\uDFE0", BlueprintProtocol.readText(buffer, 32));
            assertEquals(0, buffer.readableBytes());
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.writeText(buffer, "\u5efa\u7bc9", 5));
        } finally {
            buffer.release();
        }
    }

    @Test
    void textRejectsLengthAbuseTruncationAndMalformedUtf8() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeShort(65535);
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.readText(buffer, 256));
            buffer.clear().writeShort(36).writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.readText(buffer, 36));
            buffer.clear().writeShort(2).writeByte(0xC3).writeByte(0x28);
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.readText(buffer, 36));
        } finally {
            buffer.release();
        }
    }

    @Test
    void fullUnicodeNamesFitAnUnfragmentedListWithoutTruncation() {
        assertTrue(BlueprintProtocol.MAX_LIST_PACKET_BYTES < 32768);
        String name = new String(new char[64]).replace('\0', '\u5efa');
        assertDoesNotThrow(() -> BlueprintProtocol.validateName(name));
        ByteBuf buffer = Unpooled.buffer();
        try {
            BlueprintProtocol.writeText(buffer, name, BlueprintProtocol.MAX_NAME_BYTES);
            assertEquals(2 + 192, buffer.readableBytes());
            assertEquals(name, BlueprintProtocol.readText(buffer, BlueprintProtocol.MAX_NAME_BYTES));
            String supplementary = new String(new char[32]).replace("\0", "\uD83C\uDFE0");
            assertEquals(64, supplementary.length());
            assertDoesNotThrow(() -> BlueprintProtocol.validateName(supplementary));
            buffer.clear();
            BlueprintProtocol.writeText(buffer, supplementary, BlueprintProtocol.MAX_NAME_BYTES);
            assertEquals(supplementary, BlueprintProtocol.readText(buffer, BlueprintProtocol.MAX_NAME_BYTES));
        } finally {
            buffer.release();
        }
    }

    @Test
    void namesRejectUnpairedSurrogatesInsteadOfSilentlyReplacingThem() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateName("broken\uD83C"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateName("broken\uDFE0"));
        ByteBuf buffer = Unpooled.buffer();
        try {
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.writeText(buffer, "broken\uD83C", 192));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void selectionsHaveCapacityEvenAfterAutomaticFetchAndExplicitBudgetsAreExhausted() {
        BlueprintRate rate = new BlueprintRate();
        long now = 10000;
        assertTrue(rate.accept(Messages.BlueprintOperation.FETCH, now));
        assertTrue(rate.accept(Messages.BlueprintOperation.FETCH, now));
        assertFalse(rate.accept(Messages.BlueprintOperation.FETCH, now));
        for (int i = 0; i < 6; i++) assertTrue(rate.accept(Messages.BlueprintOperation.RENAME, now));
        assertFalse(rate.accept(Messages.BlueprintOperation.RENAME, now));
        assertTrue(rate.accept(Messages.BlueprintOperation.SELECT, now));
        assertTrue(rate.accept(Messages.BlueprintOperation.SELECT, now));
        assertFalse(rate.accept(Messages.BlueprintOperation.SELECT, now));
        assertTrue(rate.accept(Messages.BlueprintOperation.SELECT, now + 1000));
    }

    @Test
    void rejectionRepliesAndHeavyRequestsAreBoundedIndependently() {
        BlueprintRate rate = new BlueprintRate();
        long now = 10000;
        for (int i = 0; i < 10000; i++) rate.accept(Messages.BlueprintOperation.FETCH, now);
        assertTrue(rate.allowFeedback(now));
        assertTrue(rate.allowFeedback(now));
        assertFalse(rate.allowFeedback(now));
        assertTrue(rate.allowFeedback(now + 1000));
        assertTrue(rate.accept(Messages.BlueprintOperation.SAVE, now + 1000));
        assertFalse(rate.accept(Messages.BlueprintOperation.SAVE, now + 2000));
        assertTrue(rate.accept(Messages.BlueprintOperation.SAVE, now + 3000));
        assertTrue(rate.acceptPlan(now));
        assertFalse(rate.acceptPlan(now + 999));
        assertTrue(rate.acceptPlan(now + 1000));
    }

    @Test
    void materialPaginationBoundsWorkWithoutOverflowOrLargeAllocations() {
        assertEquals(0, BlueprintProtocol.materialOffset(0, 7, 0));
        assertEquals(7, BlueprintProtocol.materialOffset(1, 7, 20));
        assertEquals(20, BlueprintProtocol.materialOffset(3, 7, 20));
        assertEquals(65535, BlueprintProtocol.materialOffset(65535, 1, 65536));
        assertEquals(65536, BlueprintProtocol.materialOffset(65535, 16, 65536));
        for (int page : new int[] { -1, 65536, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.materialOffset(page, 16, 65536));
        }
        for (int size : new int[] { 0, -1, 17, Integer.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.materialOffset(0, size, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.materialOffset(0, 16, -1));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.materialOffset(0, 16, 65537));
        assertTrue(BlueprintProtocol.MAX_MATERIAL_PACKET_BYTES < 32768);
    }

    @Test
    void materialRowsRoundTripLongTotalsUnicodeAndConfiguredVariantsWithoutNbt() {
        BlueprintMaterials.Entry source = new BlueprintMaterials.Entry("appliedenergistics2:item.ItemMultiPart", 32,
            "ME \u5efa\u7bc9 \uD83C\uDFE0", Long.MAX_VALUE, true);
        ByteBuf buffer = Unpooled.buffer();
        try {
            BlueprintProtocol.writeMaterial(buffer, source);
            BlueprintMaterials.Entry result = BlueprintProtocol.readMaterial(buffer);
            assertEquals(source.registryName, result.registryName);
            assertEquals(source.metadata, result.metadata);
            assertEquals(source.displayName, result.displayName);
            assertEquals(Long.MAX_VALUE, result.amount);
            assertTrue(result.configured);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void materialRowsRejectInvalidIdsNonpositiveCountsAndOversizedStrings() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (String id : new String[] { "", "minecraft:", ":stone", "minecraft:stone:invalid", "stone" }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.writeMaterial(buffer,
                    new BlueprintMaterials.Entry(id, 0, "Item", 1, false)));
            }
            for (long amount : new long[] { 0, -1, Long.MIN_VALUE }) {
                assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.writeMaterial(buffer,
                    new BlueprintMaterials.Entry("minecraft:stone", 0, "Item", amount, false)));
            }
            assertThrows(IllegalArgumentException.class, () -> new BlueprintMaterials.Entry(
                new String(new char[257]).replace('\0', 'a'), 0, "Item", 1, false));
            buffer.clear();
            BlueprintProtocol.writeText(buffer, "minecraft:stone", 256);
            buffer.writeInt(0);
            BlueprintProtocol.writeText(buffer, "Item", 512);
            buffer.writeLong(-1);
            buffer.writeBoolean(false);
            assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.readMaterial(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void materialsRateBudgetCannotStarveSelectionAndResetsEachSecond() {
        BlueprintRate rate = new BlueprintRate();
        assertTrue(rate.accept(Messages.BlueprintOperation.MATERIALS, 10000));
        assertTrue(rate.accept(Messages.BlueprintOperation.MATERIALS, 10000));
        assertFalse(rate.accept(Messages.BlueprintOperation.MATERIALS, 10000));
        assertTrue(rate.accept(Messages.BlueprintOperation.SELECT, 10000));
        assertTrue(rate.accept(Messages.BlueprintOperation.MATERIALS, 11000));
    }

    @Test
    void bookmarkExportsHaveAnIndependentTwoSecondBudgetAndCannotStarveExplicitSelection() {
        BlueprintRate rate = new BlueprintRate();
        long now = 10000;
        for (int i = 0; i < 8; i++) assertTrue(rate.accept(Messages.BlueprintOperation.RENAME, now));
        assertFalse(rate.accept(Messages.BlueprintOperation.RENAME, now));
        assertTrue(rate.accept(Messages.BlueprintOperation.BOOKMARKS, now));
        for (int i = 0; i < 1000; i++) assertFalse(rate.accept(Messages.BlueprintOperation.BOOKMARKS, now));
        assertTrue(rate.accept(Messages.BlueprintOperation.SELECT, now));
        assertTrue(rate.accept(Messages.BlueprintOperation.SELECT, now));
        assertFalse(rate.accept(Messages.BlueprintOperation.SELECT, now));
        assertFalse(rate.accept(Messages.BlueprintOperation.BOOKMARKS, now + 1000));
        assertFalse(rate.accept(Messages.BlueprintOperation.BOOKMARKS, now + 1999));
        assertTrue(rate.accept(Messages.BlueprintOperation.BOOKMARKS, now + 2000));
        for (int i = 0; i < 8; i++) assertTrue(rate.accept(Messages.BlueprintOperation.RENAME, now + 2000));
        assertTrue(rate.accept(Messages.BlueprintOperation.SELECT, now + 2000));
    }

    @Test
    void materialExportAndBlueprintBoundsAreWholeChunks() {
        assertEquals(128, BlueprintMaterialExport.MAX_BYTES / BlueprintProtocol.CHUNK_BYTES);
        assertEquals(2048, BlueprintCodec.MAX_BYTES / BlueprintProtocol.CHUNK_BYTES);
        assertEquals(0, BlueprintCodec.MAX_BYTES % BlueprintProtocol.CHUNK_BYTES);
    }

    @Test
    void errorDetailsExposeUnderlyingCodecFailureWithoutFormattingOrControlCharacters() {
        Throwable error = new IllegalArgumentException("Invalid blueprint data", new IllegalStateException("AE config\nfailed\u00a7c\u0000"));
        String detail = BlueprintProtocol.errorDetail(error);
        assertEquals("IllegalStateException: AE config failed c ", detail);
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\u00a7"));
    }

    @Test
    void errorDetailsAreBoundedUtf8AndNeverSplitSurrogatePairs() {
        String message = new String(new char[300]).replace("\0", "\uD83C\uDFE0");
        String detail = BlueprintProtocol.errorDetail(new IllegalArgumentException(message));
        ByteBuf buffer = Unpooled.buffer();
        try {
            BlueprintProtocol.writeText(buffer, detail, BlueprintProtocol.MAX_ERROR_BYTES);
            assertTrue(buffer.readableBytes() <= 2 + BlueprintProtocol.MAX_ERROR_BYTES);
            assertEquals(detail, BlueprintProtocol.readText(buffer, BlueprintProtocol.MAX_ERROR_BYTES));
            assertDoesNotThrow(() -> BlueprintProtocol.writeText(buffer,
                BlueprintProtocol.errorDetail(new IllegalArgumentException("broken\uD83C")), BlueprintProtocol.MAX_ERROR_BYTES));
        } finally {
            buffer.release();
        }
    }

    @Test
    void namesAndIdsRejectTraversalAndControlCharacters() {
        String id = UUID.randomUUID().toString();
        assertDoesNotThrow(() -> BlueprintProtocol.validateId(id));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateId("../" + id));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateId("1-1-1-1-1"));
        assertDoesNotThrow(() -> BlueprintProtocol.validateName("Factory \u5efa\u7bc9"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateName("   "));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateName("factory\nother"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateName("factory\u007f"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.validateName(new String(new char[65]).replace('\0', 'a')));
    }
}
