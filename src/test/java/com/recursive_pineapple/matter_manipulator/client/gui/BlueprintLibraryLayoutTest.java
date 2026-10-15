package com.recursive_pineapple.matter_manipulator.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlueprintLibraryLayoutTest {

    @Test
    void centeredPanelAndControlsFitScaledMinecraftResolutions() {
        for (int[] resolution : new int[][] { { 320, 240 }, { 427, 240 }, { 640, 360 }, { 854, 480 }, { 1920, 1080 } }) {
            BlueprintLibraryLayout layout = new BlueprintLibraryLayout(resolution[0], resolution[1]);
            assertTrue(layout.left >= 12);
            assertTrue(layout.top >= 12);
            assertEquals(resolution[0] / 2, layout.left + layout.width / 2);
            assertEquals(resolution[1] / 2, layout.top + layout.height / 2);
            assertTrue(layout.width <= 420);
            assertTrue(layout.height <= 336);
            assertTrue(layout.contentWidth >= 276);
            assertTrue(layout.searchY + 18 < layout.listHeaderY);
            assertTrue(layout.listHeaderY + 14 <= layout.listTop);
            assertTrue(layout.rows >= 1);
            assertTrue(layout.rows <= 16);
            assertTrue(layout.listTop + layout.rows * BlueprintLibraryLayout.ROW_HEIGHT <= layout.pageY - 6);
            assertTrue(layout.pageY + BlueprintLibraryLayout.BUTTON_HEIGHT < layout.nameY);
            assertTrue(layout.nameY + 18 < layout.actionsY);
            assertTrue(layout.actionsY + BlueprintLibraryLayout.BUTTON_HEIGHT < layout.footerY);
            assertTrue(layout.footerY + BlueprintLibraryLayout.BUTTON_HEIGHT < layout.statusY);
            assertTrue(layout.statusY + 9 <= layout.top + layout.height);
        }
    }

    @Test
    void actionAndBothFooterArrangementsFitWithoutOverlappingAtMinimumResolution() {
        for (int[] resolution : new int[][] { { 320, 240 }, { 427, 240 }, { 640, 360 }, { 854, 480 }, { 1920, 1080 } }) {
            BlueprintLibraryLayout layout = new BlueprintLibraryLayout(resolution[0], resolution[1]);
            // Four library actions, three library footer buttons, and two materials footer buttons.
            for (int columns : new int[] { 4, 3, 2 }) {
                int previousRight = layout.contentLeft - 3;
                for (int column = 0; column < columns; column++) {
                    int left = layout.buttonLeft(column, columns);
                    int width = layout.buttonWidth(column, columns);
                    assertEquals(previousRight + 3, left);
                    assertTrue(width >= 66);
                    assertTrue(left + width <= layout.contentLeft + layout.contentWidth);
                    assertEquals(-1, layout.rowAt(left, layout.footerY));
                    assertEquals(-1, layout.rowAt(left + width - 1, layout.footerY + BlueprintLibraryLayout.BUTTON_HEIGHT - 1));
                    previousRight = left + width;
                }
                assertEquals(layout.contentLeft + layout.contentWidth, previousRight);
            }
            assertTrue(layout.actionsY + BlueprintLibraryLayout.BUTTON_HEIGHT < layout.footerY);
            assertTrue(layout.footerY + BlueprintLibraryLayout.BUTTON_HEIGHT < layout.statusY);
        }
    }

    @Test
    void minimumResolutionStillHasACompleteRowAndMaterialsNoticesDoNotOverlapControls() {
        BlueprintLibraryLayout layout = new BlueprintLibraryLayout(320, 240);
        assertEquals(1, layout.rows);
        assertEquals(6, new BlueprintLibraryLayout(854, 480).rows);
        assertTrue(layout.pageY + BlueprintLibraryLayout.BUTTON_HEIGHT < layout.nameY);
        assertTrue(layout.nameY + 5 + 9 < layout.actionsY);
        assertTrue(layout.actionsY + 5 + 9 < layout.footerY);
        assertTrue(layout.searchY + 5 + 9 < layout.listHeaderY);
    }

    @Test
    void rowHitTestingMatchesDrawnRowsWithoutAcceptingHeadersOrFooter() {
        BlueprintLibraryLayout layout = new BlueprintLibraryLayout(854, 480);
        for (int row = 0; row < layout.rows; row++) {
            int y = layout.listTop + row * BlueprintLibraryLayout.ROW_HEIGHT;
            assertEquals(row, layout.rowAt(layout.contentLeft, y));
            assertEquals(row, layout.rowAt(layout.contentLeft + layout.contentWidth - 1, y + BlueprintLibraryLayout.ROW_HEIGHT - 1));
        }
        assertEquals(-1, layout.rowAt(layout.contentLeft - 1, layout.listTop));
        assertEquals(-1, layout.rowAt(layout.contentLeft + layout.contentWidth, layout.listTop));
        assertEquals(-1, layout.rowAt(layout.contentLeft, layout.listTop - 1));
        assertEquals(-1, layout.rowAt(layout.contentLeft, layout.listTop + layout.rows * BlueprintLibraryLayout.ROW_HEIGHT));
        assertEquals(-1, layout.rowAt(layout.contentLeft, layout.listHeaderY));
        assertEquals(-1, layout.rowAt(layout.contentLeft, layout.pageY));
        assertEquals(-1, layout.rowAt(layout.contentLeft, layout.actionsY));
    }

    @Test
    void infoHitAreaMatchesItsButtonAndStaysOutsideTheList() {
        BlueprintLibraryLayout layout = new BlueprintLibraryLayout(320, 240);
        int x = layout.left + layout.width - 28;
        int y = layout.top + 5;
        assertTrue(layout.overInfo(x, y));
        assertTrue(layout.overInfo(x + 17, y + 17));
        assertFalse(layout.overInfo(x - 1, y));
        assertFalse(layout.overInfo(x + 18, y));
        assertFalse(layout.overInfo(x, y + 18));
        assertEquals(-1, layout.rowAt(x, y));
    }
}
