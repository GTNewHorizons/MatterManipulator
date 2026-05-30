package com.recursive_pineapple.matter_manipulator.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterAST;
import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterRuleParser;
import com.recursive_pineapple.matter_manipulator.common.building.filter.Offset;
import com.recursive_pineapple.matter_manipulator.common.building.filter.OffsetMode;
import com.recursive_pineapple.matter_manipulator.common.building.filter.OffsetSet;

// ── Expression tree nodes ─────────────────────────────────────────────────────

@SideOnly(Side.CLIENT)
abstract class ExprNode {

    /**
     * Logical connector that joins this node to the previous sibling.
     */
    String conn = "and";
    GroupNode parent;
}

@SideOnly(Side.CLIENT)
class CondNode extends ExprNode {

    /**
     * Bitmask of selected directions using FilterExprTree.DIR_* bits.
     * Ignored when posAt is true.
     * Invariant: at least one bit set when posAt is false.
     */
    int posMask = FilterExprTree.DIR_SELF;
    /**
     * true = "any LETTERS", false = "all LETTERS". Only used when bitCount(posMask) > 1.
     */
    boolean posAny = true;
    /**
     * When true, uses "at dx dy dz" instead of the bitmask.
     */
    boolean posAt = false;
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
enum RenderType {
    CONDITION,
    GROUP_HEADER,
    GROUP_FOOTER,
    CONNECTOR
}

@SideOnly(Side.CLIENT)
class RenderItem {

    final RenderType type;
    final ExprNode node;
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

    // Direction bitmask bits — letter encoding order: N, S, W, E, U, D, X
    static final int DIR_NORTH = 0x01; // N
    static final int DIR_SOUTH = 0x02; // S
    static final int DIR_WEST = 0x04; // W
    static final int DIR_EAST = 0x08; // E
    static final int DIR_ABOVE = 0x10; // U (up / above)
    static final int DIR_BELOW = 0x20; // D (down / below)
    static final int DIR_SELF = 0x40; // X (self)

    /**
     * Flattens the expression tree into an ordered render list.
     *
     * @return total virtual height of the list
     */
    static int flatten(
        List<ExprNode> children,
        List<RenderItem> out,
        int condRowH,
        int groupRowH,
        int groupCloseH,
        int connRowH,
        int rowGap
    ) {
        out.clear();
        int[] cursor = {
            0
        };
        flattenInto(children, 0, out, cursor, condRowH, groupRowH, groupCloseH, connRowH, rowGap);
        return cursor[0];
    }

    private static void flattenInto(
        List<ExprNode> children,
        int depth,
        List<RenderItem> out,
        int[] cursor,
        int condRowH,
        int groupRowH,
        int groupCloseH,
        int connRowH,
        int rowGap
    ) {
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
                flattenInto(grp.children, depth + 1, out, cursor, condRowH, groupRowH, groupCloseH, connRowH, rowGap);
                RenderItem footer = new RenderItem(RenderType.GROUP_FOOTER, grp, depth);
                footer.virtualY = cursor[0];
                footer.rowHeight = groupCloseH;
                out.add(footer);
                cursor[0] += footer.rowHeight;
            }
        }
    }

    /**
     * Serialises the tree back to a filter rule string.
     */
    static String serialize(List<ExprNode> children) {
        return serialize(children, block -> block.isEmpty() ? "<block>" : block);
    }

    /**
     * Serialises the tree, applying {@code blockName} to transform each block string before output.
     * Used by the preview renderer to substitute localized display names.
     */
    static String serialize(List<ExprNode> children, Function<String, String> blockName) {
        StringBuilder sb = new StringBuilder();
        appendChildren(children, sb, blockName);
        return sb.toString();
    }

    private static void appendChildren(List<ExprNode> children, StringBuilder sb, Function<String, String> blockName) {
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(" ").append(children.get(i).conn).append(" ");
            }
            appendNode(children.get(i), sb, blockName);
        }
    }

    private static void appendNode(ExprNode node, StringBuilder sb, Function<String, String> blockName) {
        if (node instanceof final CondNode c) {
            appendPosition(c, sb);
            sb.append(" is");
            if (c.negated) {
                sb.append(" not");
            }
            sb.append(" ").append(blockName.apply(c.block));
        } else if (node instanceof final GroupNode g) {
            sb.append("(");
            appendChildren(g.children, sb, blockName);
            sb.append(")");
        }
    }

    static void appendPosition(CondNode c, StringBuilder sb) {
        if (c.posAt) {
            sb.append("at ").append(c.atX).append(" ").append(c.atY).append(" ").append(c.atZ);
        } else if (Integer.bitCount(c.posMask) <= 1) {
            sb.append(dirToName(c.posMask));
        } else {
            sb.append(c.posAny ? "any " : "all ").append(maskToLetters(c.posMask));
        }
    }

    /**
     * Short human-readable summary of a condition's position, for the dropdown button label.
     */
    static String posSummary(CondNode c) {
        if (c.posAt) { return "at X Y Z"; }
        if (c.posMask == 0) { return "?"; }
        if (Integer.bitCount(c.posMask) == 1) { return dirToName(c.posMask); }
        return (c.posAny ? "any " : "all ") + maskToLetters(c.posMask);
    }

    private static String dirToName(int singleBit) {
        return switch (singleBit) {
            case DIR_NORTH -> "north";
            case DIR_SOUTH -> "south";
            case DIR_WEST -> "west";
            case DIR_EAST -> "east";
            case DIR_ABOVE -> "above";
            case DIR_BELOW -> "below";
            default -> "self";
        };
    }

    /**
     * Encodes active bits to compact direction letters in N,S,W,E,U,D,X order.
     */
    static String maskToLetters(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & DIR_NORTH) != 0) {
            sb.append('N');
        }
        if ((mask & DIR_SOUTH) != 0) {
            sb.append('S');
        }
        if ((mask & DIR_WEST) != 0) {
            sb.append('W');
        }
        if ((mask & DIR_EAST) != 0) {
            sb.append('E');
        }
        if ((mask & DIR_ABOVE) != 0) {
            sb.append('U');
        }
        if ((mask & DIR_BELOW) != 0) {
            sb.append('D');
        }
        if ((mask & DIR_SELF) != 0) {
            sb.append('X');
        }
        return sb.toString();
    }

    /**
     * Reconstructs the expression tree from a filter rule string.
     * Populates root.children; on any parse failure the root is left unchanged.
     */
    static void loadFrom(String text, GroupNode root) {
        if (text == null || text.trim().isEmpty()) { return; }
        try {
            FilterAST ast = FilterRuleParser.parseAST(text);
            List<ExprNode> children = astToNodes(ast, root);
            if (!children.isEmpty()) {
                root.children.clear();
                root.children.addAll(children);
            }
        } catch (Exception ignored) {}
    }

    private static List<ExprNode> astToNodes(FilterAST ast, GroupNode parent) {
        List<ExprNode> result = new ArrayList<>();
        flattenAst(ast, parent, result, "and", false);
        return result;
    }

    /**
     * Recursively flattens an AST node into a list of GUI ExprNodes.
     * <p>
     * insideAnd: when true, an Or sub-expression must be wrapped in a GroupNode to preserve
     * precedence (since And binds tighter than Or).
     */
    private static void flattenAst(FilterAST ast, GroupNode parent, List<ExprNode> out, String conn, boolean insideAnd) {
        if (ast instanceof FilterAST.Condition cond) {
            CondNode c = condToNode(cond, parent);
            c.conn = conn;
            out.add(c);
        } else if (ast instanceof FilterAST.And and) {
            flattenAst(and.left(), parent, out, conn, true);
            flattenAst(and.right(), parent, out, "and", true);
        } else if (ast instanceof FilterAST.Or or) {
            if (insideAnd) {
                // Wrap in a group to prevent the Or from being swallowed by the surrounding And
                GroupNode group = makeGroup(ast, parent);
                group.conn = conn;
                out.add(group);
            } else {
                flattenAst(or.left(), parent, out, conn, false);
                flattenAst(or.right(), parent, out, "or", false);
            }
        } else if (ast instanceof FilterAST.Not not) {
            if (not.inner() instanceof FilterAST.Condition inner) {
                // Flip the negation flag so "not self is stone" renders the same as "self is not stone"
                FilterAST.Condition flipped = new FilterAST.Condition(inner.position(), !inner.negated(), inner.block(), inner.meta());
                CondNode c = condToNode(flipped, parent);
                c.conn = conn;
                out.add(c);
            } else {
                // not (...group...) can't be represented in the GUI; drop the Not and keep the inner tree
                flattenAst(not.inner(), parent, out, conn, insideAnd);
            }
        }
    }

    private static GroupNode makeGroup(FilterAST ast, GroupNode parent) {
        GroupNode group = new GroupNode();
        group.parent = parent;
        group.children.addAll(astToNodes(ast, group));
        if (group.children.isEmpty()) {
            CondNode ph = new CondNode();
            ph.parent = group;
            group.children.add(ph);
        }
        return group;
    }

    private static CondNode condToNode(FilterAST.Condition cond, GroupNode parent) {
        CondNode c = new CondNode();
        c.parent = parent;
        c.negated = cond.negated();
        c.block = cond.meta() >= 0 ? cond.block() + "@" + cond.meta() : cond.block();

        OffsetSet pos = cond.position();
        List<Offset> offsets = pos.offsets();

        if (pos.mode() == OffsetMode.SINGLE) {
            Offset o = offsets.get(0);
            int mask = offsetToMask(o);
            if (mask != 0) {
                c.posMask = mask;
                c.posAt = false;
            } else {
                c.posAt = true;
                c.atX = o.dx;
                c.atY = o.dy;
                c.atZ = o.dz;
            }
        } else {
            c.posAny = (pos.mode() == OffsetMode.ANY);
            c.posMask = 0;
            for (Offset o : offsets) {
                c.posMask |= offsetToMask(o);
            }
            c.posAt = false;
        }
        return c;
    }

    private static int offsetToMask(Offset o) {
        if (o.dx == 0 && o.dy == 0 && o.dz == 0) { return DIR_SELF; }
        if (o.dx == 0 && o.dy == 1 && o.dz == 0) { return DIR_ABOVE; }
        if (o.dx == 0 && o.dy == -1 && o.dz == 0) { return DIR_BELOW; }
        if (o.dx == 0 && o.dy == 0 && o.dz == -1) { return DIR_NORTH; }
        if (o.dx == 0 && o.dy == 0 && o.dz == 1) { return DIR_SOUTH; }
        if (o.dx == 1 && o.dy == 0 && o.dz == 0) { return DIR_EAST; }
        if (o.dx == -1 && o.dy == 0 && o.dz == 0) { return DIR_WEST; }
        return 0;
    }

    /**
     * Returns true if any condition node in the tree has an empty block name.
     */
    static boolean hasEmptyBlock(List<ExprNode> children) {
        for (ExprNode n : children) {
            if (n instanceof CondNode && ((CondNode) n).block.isEmpty()) { return true; }
            if (n instanceof GroupNode && hasEmptyBlock(((GroupNode) n).children)) { return true; }
        }
        return false;
    }
}
