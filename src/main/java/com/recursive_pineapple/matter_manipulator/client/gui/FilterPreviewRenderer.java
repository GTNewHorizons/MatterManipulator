package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterRuleParser;

/**
 * Builds human-readable and syntax-highlighted filter previews, and draws the hover tooltip.
 * All methods are stateless and static.
 */
@SideOnly(Side.CLIENT)
public class FilterPreviewRenderer {

    // ── Preview string ─────────────────────────────────────────────────────

    /**
     * Parses a filter rule string and returns a one-line human-readable preview with localized block names.
     */
    public static String buildFromString(String filterText) {
        GroupNode root = new GroupNode();
        FilterExprTree.loadFrom(filterText, root);
        return build(root);
    }

    /**
     * Returns the one-line human-readable preview of the entire filter.
     */
    static String build(GroupNode root) {
        return FilterExprTree.serialize(root.children, FilterPreviewRenderer::localizedBlockName);
    }

    // ── Block name lookup ──────────────────────────────────────────────────

    /**
     * Returns the localized, quoted display name for a block string like "modid:name@meta".
     */
    static String localizedBlockName(String blockStr) {
        if (blockStr.isEmpty()) { return "<block>"; }
        int meta = 0;
        String registryName = blockStr;
        int atIdx = blockStr.indexOf('@');
        if (atIdx >= 0) {
            try {
                meta = Integer.parseInt(blockStr.substring(atIdx + 1));
            } catch (NumberFormatException ignored) {}
            registryName = blockStr.substring(0, atIdx);
        }
        Block block = FilterRuleParser.findBlock(registryName);
        if (block == null) { return "\"" + blockStr + "\""; }
        Item item = Item.getItemFromBlock(block);
        if (item == null) { return "\"" + block.getLocalizedName() + "\""; }
        return "\"" + new ItemStack(item, 1, meta).getDisplayName() + "\"";
    }

    // ── Formatted tooltip lines ────────────────────────────────────────────

    /**
     * Builds syntax-highlighted lines for the hover tooltip.
     * One line per condition; groups are indented.
     * Color scheme:
     * §b aqua — position keywords (north, self, any NSE, …)
     * §3 dark aqua — numbers in "at X Y Z"
     * §e yellow — "is", "and"
     * §6 gold — "or"
     * §a green — quoted block name
     * §7 gray — group parentheses
     */
    static List<String> buildFormattedLines(GroupNode root) {
        List<String> lines = new ArrayList<>();
        appendFormattedChildren(root.children, lines, 0, null);
        return lines;
    }

    private static void appendFormattedChildren(List<ExprNode> children, List<String> lines, int depth, String firstConn) {
        for (int i = 0; i < children.size(); i++) {
            appendFormattedNode(children.get(i), lines, depth, i == 0 ? firstConn : children.get(i).conn);
        }
    }

    private static void appendFormattedNode(ExprNode node, List<String> lines, int depth, String conn) {
        StringBuilder indSb = new StringBuilder();
        for (int d = 0; d < depth; d++) {
            indSb.append("  ");
        }
        String ind = indSb.toString();
        String connStr = conn == null ? "" : ("and".equals(conn) ? "§eand §r" : "§6or §r");
        if (node instanceof final CondNode c) {
            StringBuilder sb = new StringBuilder(ind).append(connStr);
            if (c.posAt) {
                sb.append("§bat §3").append(c.atX).append(" ").append(c.atY).append(" ").append(c.atZ);
            } else {
                sb.append("§b").append(FilterExprTree.posSummary(c));
            }
            sb.append(c.negated ? " §eis not§r " : " §eis§r ");
            sb.append("§a").append(localizedBlockName(c.block));
            lines.add(sb.toString());
        } else if (node instanceof final GroupNode g) {
            lines.add(ind + connStr + "§7(");
            appendFormattedChildren(g.children, lines, depth + 1, null);
            lines.add(ind + "§7)");
        }
    }

    // ── Tooltip drawing ────────────────────────────────────────────────────

    static void drawTooltip(FontRenderer fr, List<String> lines, int mouseX, int mouseY, int screenW, int screenH) {
        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, fr.getStringWidth(line));
        }
        int boxW = maxW + 8;
        int boxH = lines.size() * 10 + 6;
        int bx = Math.min(mouseX, screenW - boxW - 4);
        int by = mouseY - boxH - 4;
        if (by < 4) {
            by = mouseY + 14;
        }
        GuiScreen.drawRect(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, 0xFF555555);
        GuiScreen.drawRect(bx, by, bx + boxW, by + boxH, 0xFF1E1E1E);
        for (int i = 0; i < lines.size(); i++) {
            fr.drawString(lines.get(i), bx + 4, by + 3 + i * 10, 0xFFFFFF);
        }
    }
}
