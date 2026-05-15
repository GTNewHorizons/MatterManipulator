package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

// ── Expression tree nodes ─────────────────────────────────────────────────────

@SideOnly(Side.CLIENT)
abstract class ExprNode {
    /** Logical connector that joins this node to the previous sibling. */
    String conn = "and";
    GroupNode parent;
}

@SideOnly(Side.CLIENT)
class CondNode extends ExprNode {
    /**
     * 0–10 = named position; 11 (FilterExprTree.POS_AT) = "at dx dy dz".
     */
    int posIdx = 0;
    int atX = 0, atY = 0, atZ = 0;
    boolean negated = false;
    String block = "";
}

@SideOnly(Side.CLIENT)
class GroupNode extends ExprNode {
    final List<ExprNode> children = new ArrayList<>();
}

// ── Render list types ─────────────────────────────────────────────────────────

@SideOnly(Side.CLIENT)
enum RenderType {CONDITION, GROUP_HEADER, GROUP_FOOTER, CONNECTOR}

@SideOnly(Side.CLIENT)
class RenderItem {
    final RenderType type;
    final ExprNode node;  // for CONN: the node that follows the connector
    final int depth;
    int virtualY, rowHeight;

    RenderItem(RenderType type, ExprNode node, int depth) {
        this.type = type;
        this.node = node;
        this.depth = depth;
    }
}

// ── Position constants + tree utilities ───────────────────────────────────────

@SideOnly(Side.CLIENT)
class FilterExprTree {

    static final int POS_AT = 11;

    static final String[] POSITION_NAMES = {
        "self", "above", "below", "north", "south", "east", "west",
        "any NSEW", "all NSEW", "any NSEWUD", "all NSEWUD"
    };

    static final String[] POSITION_LABELS = {
        "self", "above", "below", "north", "south", "east", "west",
        "any NSEW", "all NSEW", "any NSEWUD", "all NSEWUD", "at X Y Z"
    };

    /**
     * Flattens the expression tree into an ordered render list.
     * Each item's {@code virtualY} and {@code rowHeight} are filled in.
     *
     * @return total virtual height of the list
     */
    static int flatten(List<ExprNode> children, List<RenderItem> out,
                       int condRowH, int groupRowH, int groupCloseH, int connRowH, int rowGap) {
        out.clear();
        int[] cursor = {0};
        flattenInto(children, 0, out, cursor, condRowH, groupRowH, groupCloseH, connRowH, rowGap);
        return cursor[0];
    }

    private static void flattenInto(List<ExprNode> children, int depth, List<RenderItem> out,
                                    int[] cursor, int condRowH, int groupRowH, int groupCloseH, int connRowH, int rowGap) {

        for (int i = 0; i < children.size(); i++) {
            ExprNode child = children.get(i);

            if (i > 0) {
                RenderItem conn = new RenderItem(RenderType.CONNECTOR, child, depth);
                conn.virtualY = cursor[0];
                conn.rowHeight = connRowH + rowGap;
                out.add(conn);
                cursor[0] += conn.rowHeight;
            }

            if (child instanceof CondNode) {
                RenderItem item = new RenderItem(RenderType.CONDITION, child, depth);
                item.virtualY = cursor[0];
                item.rowHeight = condRowH + rowGap;
                out.add(item);
                cursor[0] += item.rowHeight;

            } else if (child instanceof final GroupNode grp) {

                RenderItem header = new RenderItem(RenderType.GROUP_HEADER, grp, depth);
                header.virtualY = cursor[0];
                header.rowHeight = groupRowH + rowGap;
                out.add(header);
                cursor[0] += header.rowHeight;

                flattenInto(grp.children, depth + 1, out, cursor,
                    condRowH, groupRowH, groupCloseH, connRowH, rowGap);

                RenderItem footer = new RenderItem(RenderType.GROUP_FOOTER, grp, depth);
                footer.virtualY = cursor[0];
                footer.rowHeight = groupCloseH + rowGap;
                out.add(footer);
                cursor[0] += footer.rowHeight;
            }
        }
    }

    /**
     * Serialises the tree back to a filter rule string.
     */
    static String serialize(List<ExprNode> children) {
        StringBuilder sb = new StringBuilder();
        appendChildren(children, sb);
        return sb.toString();
    }

    private static void appendChildren(List<ExprNode> children, StringBuilder sb) {
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(" ").append(children.get(i).conn).append(" ");
            }
            appendNode(children.get(i), sb);
        }
    }

    private static void appendNode(ExprNode node, StringBuilder sb) {
        if (node instanceof final CondNode c) {
            if (c.posIdx == POS_AT) {
                sb.append("at ").append(c.atX).append(" ").append(c.atY).append(" ").append(c.atZ);
            } else {
                sb.append(POSITION_NAMES[c.posIdx]);
            }
            sb.append(" is");
            if (c.negated) {
                sb.append(" not");
            }
            sb.append(" ").append(c.block.isEmpty() ? "<block>" : c.block);

        } else if (node instanceof final GroupNode g) {
            sb.append("(");
            appendChildren(g.children, sb);
            sb.append(")");
        }
    }

    /**
     * Returns true if any condition node in the tree has an empty block name.
     */
    static boolean hasEmptyBlock(List<ExprNode> children) {
        for (ExprNode n : children) {
            if (n instanceof CondNode && ((CondNode) n).block.isEmpty()) {
                return true;
            }
            if (n instanceof GroupNode && hasEmptyBlock(((GroupNode) n).children)) {
                return true;
            }
        }
        return false;
    }
}
