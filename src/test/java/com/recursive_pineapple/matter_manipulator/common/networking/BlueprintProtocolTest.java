package com.recursive_pineapple.matter_manipulator.common.networking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class BlueprintProtocolTest {
    @Test
    void inclusiveVolumeAcceptsReversedAndMaximumSizedRegions() {
        assertEquals(1, BlueprintProtocol.checkedVolume(0, 0, 0));
        assertEquals(32768, BlueprintProtocol.checkedVolume(-31, 31, -31));
        assertEquals(32768, BlueprintProtocol.checkedVolume(32767, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(32, 31, 31));
        assertThrows(IllegalArgumentException.class, () -> BlueprintProtocol.checkedVolume(32768, 0, 0));
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
        int chunk = 16 * 1024, max = 2 * 1024 * 1024;
        for (int offset = 0; offset < max; offset += chunk) BlueprintProtocol.validateChunk(max, offset, chunk);
        assertDoesNotThrow(() -> BlueprintProtocol.validateChunk(chunk + 7, chunk, 7));
        assertDoesNotThrow(() -> BlueprintProtocol.validateChunk(1, 0, 1));
    }

    @Test
    void chunksRejectOversizedMisalignedAndInconsistentBounds() {
        int chunk = 16 * 1024, max = 2 * 1024 * 1024;
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
