package com.recursive_pineapple.matter_manipulator.client.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class FilterExprTreeTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String ser(ExprNode... nodes) {
        return FilterExprTree.serialize(Arrays.asList(nodes));
    }

    private static CondNode cond(String block) {
        CondNode c = new CondNode();
        c.block = block;
        return c;
    }

    private static CondNode connected(CondNode c, String conn) {
        c.conn = conn;
        return c;
    }

    private static String roundTrip(String text) {
        GroupNode root = new GroupNode();
        FilterExprTree.loadFrom(text, root);
        return FilterExprTree.serialize(root.children);
    }

    // ── serialize: position variants ──────────────────────────────────────────

    @Test
    void serialize_self() {
        assertEquals("self is stone", ser(cond("stone")));
    }

    @Test
    void serialize_above() {
        CondNode c = cond("stone");
        c.posMask = FilterExprTree.DIR_ABOVE;
        assertEquals("above is stone", ser(c));
    }

    @Test
    void serialize_at() {
        CondNode c = cond("stone");
        c.posAt = true;
        c.atX = 1;
        c.atY = 2;
        c.atZ = 3;
        assertEquals("at 1 2 3 is stone", ser(c));
    }

    @Test
    void serialize_at_negative() {
        CondNode c = cond("stone");
        c.posAt = true;
        c.atX = -1;
        c.atY = 0;
        c.atZ = -3;
        assertEquals("at -1 0 -3 is stone", ser(c));
    }

    @Test
    void serialize_any() {
        CondNode c = cond("stone");
        c.posMask = FilterExprTree.DIR_NORTH | FilterExprTree.DIR_SOUTH | FilterExprTree.DIR_WEST | FilterExprTree.DIR_EAST;
        c.posAny = true;
        assertEquals("any NSWE is stone", ser(c));
    }

    @Test
    void serialize_all() {
        CondNode c = cond("stone");
        c.posMask = FilterExprTree.DIR_NORTH | FilterExprTree.DIR_SOUTH | FilterExprTree.DIR_WEST | FilterExprTree.DIR_EAST;
        c.posAny = false;
        assertEquals("all NSWE is stone", ser(c));
    }

    // ── serialize: condition variants ─────────────────────────────────────────

    @Test
    void serialize_negated() {
        CondNode c = cond("stone");
        c.negated = true;
        assertEquals("self is not stone", ser(c));
    }

    @Test
    void serialize_withMeta() {
        // The GUI stores the full "name@meta" string in the block field
        assertEquals("self is stone@4", ser(cond("stone@4")));
    }

    @Test
    void serialize_emptyBlock_placeholder() {
        assertEquals("self is <block>", ser(cond("")));
    }

    // ── serialize: connectors + groups ────────────────────────────────────────

    @Test
    void serialize_twoConditions_and() {
        assertEquals("self is a and self is b", ser(cond("a"), connected(cond("b"), "and")));
    }

    @Test
    void serialize_twoConditions_or() {
        assertEquals("self is a or self is b", ser(cond("a"), connected(cond("b"), "or")));
    }

    @Test
    void serialize_threeConditions_mixed() {
        assertEquals(
            "self is a and self is b or self is c",
            ser(cond("a"), connected(cond("b"), "and"), connected(cond("c"), "or"))
        );
    }

    @Test
    void serialize_group() {
        GroupNode group = new GroupNode();
        group.children.add(cond("a"));
        group.children.add(connected(cond("b"), "and"));
        assertEquals("(self is a and self is b)", ser(group));
    }

    @Test
    void serialize_group_connected() {
        GroupNode group = new GroupNode();
        group.children.add(cond("a"));
        group.children.add(connected(cond("b"), "or"));
        group.conn = "and";
        assertEquals("self is c and (self is a or self is b)", ser(cond("c"), group));
    }

    // ── loadFrom round-trips ──────────────────────────────────────────────────

    @Test
    void roundTrip_simple() {
        assertEquals("self is stone", roundTrip("self is stone"));
    }

    @Test
    void roundTrip_negated() {
        assertEquals("self is not stone", roundTrip("self is not stone"));
    }

    @Test
    void roundTrip_position_above() {
        assertEquals("above is stone", roundTrip("above is stone"));
    }

    @Test
    void roundTrip_position_at() {
        assertEquals("at 1 2 3 is stone", roundTrip("at 1 2 3 is stone"));
    }

    @Test
    void roundTrip_position_anyNSWE() {
        assertEquals("any NSWE is stone", roundTrip("any NSWE is stone"));
    }

    @Test
    void roundTrip_andChain() {
        assertEquals(
            "self is a and self is b and self is c",
            roundTrip("self is a and self is b and self is c")
        );
    }

    @Test
    void roundTrip_orChain() {
        assertEquals(
            "self is a or self is b or self is c",
            roundTrip("self is a or self is b or self is c")
        );
    }

    @Test
    void roundTrip_andThenOr_staysFlat() {
        // Or(And(A,B), C) flattens to [A, B(and), C(or)] — no grouping needed since And already binds tighter
        assertEquals(
            "self is a and self is b or self is c",
            roundTrip("self is a and self is b or self is c")
        );
    }

    @Test
    void roundTrip_orThenAnd_staysFlat() {
        // Or(A, And(B,C)) flattens to [A, B(or), C(and)] — And already has the right precedence when reparsed
        assertEquals(
            "self is a or self is b and self is c",
            roundTrip("self is a or self is b and self is c")
        );
    }

    @Test
    void roundTrip_explicitGroup_andOutside() {
        // And(A, Or(B,C)) — the Or is insideAnd so flattenAst wraps it in a GroupNode
        assertEquals(
            "self is a and (self is b or self is c)",
            roundTrip("self is a and (self is b or self is c)")
        );
    }

    @Test
    void roundTrip_explicitGroup_andInside() {
        // And(Or(A,B), C) — the Or is insideAnd so it gets wrapped
        assertEquals(
            "(self is a or self is b) and self is c",
            roundTrip("(self is a or self is b) and self is c")
        );
    }

    @Test
    void roundTrip_notCondition_becomesIsNot() {
        // Not(Condition(negated=false)) → CondNode(negated=true) — semantically identical
        assertEquals("self is not stone", roundTrip("not self is stone"));
    }

    @Test
    void roundTrip_notGroup_notIsDropped() {
        // Not(And(...)) can't be represented in the GUI; Not is dropped, inner tree is preserved
        assertEquals("self is a and self is b", roundTrip("not (self is a and self is b)"));
    }

    // ── loadFrom: error handling ──────────────────────────────────────────────

    @Test
    void loadFrom_parseError_leavesRootUnchanged() {
        GroupNode root = new GroupNode();
        CondNode original = cond("original");
        root.children.add(original);

        FilterExprTree.loadFrom("self is $invalid", root);

        assertEquals(1, root.children.size());
        assertSame(original, root.children.get(0));
    }

    @Test
    void loadFrom_emptyText_leavesRootUnchanged() {
        GroupNode root = new GroupNode();
        CondNode original = cond("original");
        root.children.add(original);

        FilterExprTree.loadFrom("", root);

        assertEquals(1, root.children.size());
        assertSame(original, root.children.get(0));
    }
}
