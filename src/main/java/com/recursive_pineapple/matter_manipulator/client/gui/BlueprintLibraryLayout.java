package com.recursive_pineapple.matter_manipulator.client.gui;

/** Shared geometry for drawing and hit testing at Minecraft's scaled GUI resolutions. */
final class BlueprintLibraryLayout {

    static final int ROW_HEIGHT = 24;
    static final int BUTTON_HEIGHT = 18;

    final int left, top, width, height;
    final int contentLeft, contentWidth;
    final int searchY, listHeaderY, listTop, rows;
    final int pageY, nameY, actionsY, footerY, statusY;

    BlueprintLibraryLayout(int screenWidth, int screenHeight) {
        width = Math.min(420, screenWidth - 24);
        height = Math.min(336, screenHeight - 24);
        left = (screenWidth - width) / 2;
        top = (screenHeight - height) / 2;
        contentLeft = left + 10;
        contentWidth = width - 20;
        searchY = top + 34;
        listHeaderY = top + 58;
        listTop = top + 74;
        pageY = top + height - 105;
        rows = Math.max(1, Math.min(16, (pageY - 6 - listTop) / ROW_HEIGHT));
        nameY = top + height - 81;
        actionsY = top + height - 55;
        footerY = top + height - 31;
        statusY = top + height - 11;
    }

    int buttonLeft(int column, int columns) {
        return contentLeft + contentWidth / columns * column;
    }

    int buttonWidth(int column, int columns) {
        int end = column + 1 == columns ? contentLeft + contentWidth : buttonLeft(column + 1, columns) - 3;
        return end - buttonLeft(column, columns);
    }

    int rowAt(int mouseX, int mouseY) {
        if (mouseX < contentLeft || mouseX >= contentLeft + contentWidth || mouseY < listTop || mouseY >= listTop + rows * ROW_HEIGHT) return -1;
        return (mouseY - listTop) / ROW_HEIGHT;
    }

    boolean overInfo(int mouseX, int mouseY) {
        return mouseX >= left + width - 28 && mouseX < left + width - 10 && mouseY >= top + 5 && mouseY < top + 23;
    }
}
