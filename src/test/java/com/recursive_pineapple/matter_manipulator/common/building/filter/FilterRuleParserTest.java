package com.recursive_pineapple.matter_manipulator.common.building.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class FilterRuleParserTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FilterAST parse(String text) {
        return FilterRuleParser.parseAST(text);
    }

    private static FilterAST.Condition parseCond(String text) {
        return assertInstanceOf(FilterAST.Condition.class, parse(text));
    }

    private static void assertOffset(Offset o, int dx, int dy, int dz) {
        assertEquals(dx, o.dx, "dx");
        assertEquals(dy, o.dy, "dy");
        assertEquals(dz, o.dz, "dz");
    }

    private static void assertSingleOffset(FilterAST.Condition c, int dx, int dy, int dz) {
        assertEquals(OffsetMode.SINGLE, c.position().mode());
        List<Offset> offsets = c.position().offsets();
        assertEquals(1, offsets.size());
        assertOffset(offsets.get(0), dx, dy, dz);
    }

    // ── Block names ───────────────────────────────────────────────────────────

    @Test
    void plainBlockName() {
        FilterAST.Condition c = parseCond("self is stone");
        assertEquals("stone", c.block());
        assertEquals(-1, c.meta());
    }

    @Test
    void namespacedBlockName() {
        assertEquals("minecraft:stone", parseCond("self is minecraft:stone").block());
    }

    @Test
    void metaAtSyntax() {
        FilterAST.Condition c = parseCond("self is stone@4");
        assertEquals("stone", c.block());
        assertEquals(4, c.meta());
    }

    @Test
    void metaKeyword() {
        FilterAST.Condition c = parseCond("self is stone meta 4");
        assertEquals("stone", c.block());
        assertEquals(4, c.meta());
    }

    @Test
    void semicolonInModId() {
        assertEquals("mod;id:block", parseCond("self is mod;id:block").block());
    }

    // ── Negation ──────────────────────────────────────────────────────────────

    @Test
    void isNotKeywordSetsNegated() {
        assertTrue(parseCond("self is not stone").negated());
    }

    @Test
    void notNegatedByDefault() {
        assertFalse(parseCond("self is stone").negated());
    }

    @Test
    void notPrefixOnCondition() {
        FilterAST.Not not = assertInstanceOf(FilterAST.Not.class, parse("not self is stone"));
        FilterAST.Condition inner = assertInstanceOf(FilterAST.Condition.class, not.inner());
        assertFalse(inner.negated());
        assertEquals("stone", inner.block());
    }

    @Test
    void doubleNot() {
        FilterAST.Not outer = assertInstanceOf(FilterAST.Not.class, parse("not not self is stone"));
        assertInstanceOf(FilterAST.Not.class, outer.inner());
    }

    // ── Single positions ──────────────────────────────────────────────────────

    @Test
    void pos_self() {
        assertSingleOffset(parseCond("self is stone"), 0, 0, 0);
    }

    @Test
    void pos_here() {
        assertSingleOffset(parseCond("here is stone"), 0, 0, 0);
    }

    @Test
    void pos_X() {
        assertSingleOffset(parseCond("X is stone"), 0, 0, 0);
    }

    @Test
    void pos_above() {
        assertSingleOffset(parseCond("above is stone"), 0, 1, 0);
    }

    @Test
    void pos_up() {
        assertSingleOffset(parseCond("up is stone"), 0, 1, 0);
    }

    @Test
    void pos_U() {
        assertSingleOffset(parseCond("U is stone"), 0, 1, 0);
    }

    @Test
    void pos_below() {
        assertSingleOffset(parseCond("below is stone"), 0, -1, 0);
    }

    @Test
    void pos_down() {
        assertSingleOffset(parseCond("down is stone"), 0, -1, 0);
    }

    @Test
    void pos_D() {
        assertSingleOffset(parseCond("D is stone"), 0, -1, 0);
    }

    @Test
    void pos_north() {
        assertSingleOffset(parseCond("north is stone"), 0, 0, -1);
    }

    @Test
    void pos_N() {
        assertSingleOffset(parseCond("N is stone"), 0, 0, -1);
    }

    @Test
    void pos_south() {
        assertSingleOffset(parseCond("south is stone"), 0, 0, 1);
    }

    @Test
    void pos_S() {
        assertSingleOffset(parseCond("S is stone"), 0, 0, 1);
    }

    @Test
    void pos_east() {
        assertSingleOffset(parseCond("east is stone"), 1, 0, 0);
    }

    @Test
    void pos_E() {
        assertSingleOffset(parseCond("E is stone"), 1, 0, 0);
    }

    @Test
    void pos_west() {
        assertSingleOffset(parseCond("west is stone"), -1, 0, 0);
    }

    @Test
    void pos_W() {
        assertSingleOffset(parseCond("W is stone"), -1, 0, 0);
    }

    @Test
    void pos_at() {
        assertSingleOffset(parseCond("at 1 2 3 is stone"), 1, 2, 3);
    }

    @Test
    void pos_at_negative() {
        assertSingleOffset(parseCond("at -1 0 -3 is stone"), -1, 0, -3);
    }

    // ── Multi-direction positions ─────────────────────────────────────────────

    @Test
    void pos_any_mode() {
        FilterAST.Condition c = parseCond("any NSWE is stone");
        assertEquals(OffsetMode.ANY, c.position().mode());
        List<Offset> offsets = c.position().offsets();
        assertEquals(4, offsets.size());
        assertOffset(offsets.get(0), 0, 0, -1); // N
        assertOffset(offsets.get(1), 0, 0, 1); // S
        assertOffset(offsets.get(2), -1, 0, 0); // W
        assertOffset(offsets.get(3), 1, 0, 0); // E
    }

    @Test
    void pos_all_mode() {
        FilterAST.Condition c = parseCond("all XUD is stone");
        assertEquals(OffsetMode.ALL, c.position().mode());
        List<Offset> offsets = c.position().offsets();
        assertEquals(3, offsets.size());
        assertOffset(offsets.get(0), 0, 0, 0); // X
        assertOffset(offsets.get(1), 0, 1, 0); // U
        assertOffset(offsets.get(2), 0, -1, 0); // D
    }

    // ── Logical operators ─────────────────────────────────────────────────────

    @Test
    void and_binary() {
        FilterAST.And and = assertInstanceOf(FilterAST.And.class, parse("self is a and self is b"));
        assertInstanceOf(FilterAST.Condition.class, and.left());
        assertInstanceOf(FilterAST.Condition.class, and.right());
    }

    @Test
    void or_binary() {
        FilterAST.Or or = assertInstanceOf(FilterAST.Or.class, parse("self is a or self is b"));
        assertInstanceOf(FilterAST.Condition.class, or.left());
        assertInstanceOf(FilterAST.Condition.class, or.right());
    }

    @Test
    void and_leftAssociative() {
        // A and B and C → And(And(A, B), C)
        FilterAST.And outer = assertInstanceOf(FilterAST.And.class, parse("self is a and self is b and self is c"));
        assertInstanceOf(FilterAST.And.class, outer.left());
        assertInstanceOf(FilterAST.Condition.class, outer.right());
    }

    @Test
    void or_leftAssociative() {
        // A or B or C → Or(Or(A, B), C)
        FilterAST.Or outer = assertInstanceOf(FilterAST.Or.class, parse("self is a or self is b or self is c"));
        assertInstanceOf(FilterAST.Or.class, outer.left());
        assertInstanceOf(FilterAST.Condition.class, outer.right());
    }

    @Test
    void andBindsTighterThanOr_left() {
        // A and B or C → Or(And(A, B), C)
        FilterAST.Or or = assertInstanceOf(FilterAST.Or.class, parse("self is a and self is b or self is c"));
        assertInstanceOf(FilterAST.And.class, or.left());
        assertInstanceOf(FilterAST.Condition.class, or.right());
    }

    @Test
    void andBindsTighterThanOr_right() {
        // A or B and C → Or(A, And(B, C))
        FilterAST.Or or = assertInstanceOf(FilterAST.Or.class, parse("self is a or self is b and self is c"));
        assertInstanceOf(FilterAST.Condition.class, or.left());
        assertInstanceOf(FilterAST.And.class, or.right());
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    @Test
    void parentheses_redundant() {
        // (A) is just A
        assertInstanceOf(FilterAST.Condition.class, parse("(self is stone)"));
    }

    @Test
    void parentheses_overridePrecedence() {
        // A and (B or C) → And(A, Or(B, C))
        FilterAST.And and = assertInstanceOf(FilterAST.And.class, parse("self is a and (self is b or self is c)"));
        assertInstanceOf(FilterAST.Condition.class, and.left());
        assertInstanceOf(FilterAST.Or.class, and.right());
    }

    @Test
    void notOnGroup() {
        // not (A and B) → Not(And(A, B))
        FilterAST.Not not = assertInstanceOf(FilterAST.Not.class, parse("not (self is a and self is b)"));
        assertInstanceOf(FilterAST.And.class, not.inner());
    }

    // ── Whitespace / separators ───────────────────────────────────────────────

    @Test
    void commaAsSeparator() {
        // commas are treated as whitespace
        assertDoesNotThrow(() -> parse("self is stone, and, self is dirt"));
    }

    @Test
    void extraWhitespace() {
        FilterAST.Condition c = parseCond("  self   is   stone  ");
        assertEquals("stone", c.block());
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void error_emptyString() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse(""));
    }

    @Test
    void error_nullText() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse(null));
    }

    @Test
    void error_unexpectedCharacter() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("self is $stone"));
    }

    @Test
    void error_missingIs() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("self stone"));
    }

    @Test
    void error_missingBlockName() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("self is"));
    }

    @Test
    void error_emptyMeta() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("self is stone@"));
    }

    @Test
    void error_nonNumericMeta() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("self is stone@abc"));
    }

    @Test
    void error_unmatchedOpenParen() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("(self is stone"));
    }

    @Test
    void error_trailingJunk() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("self is stone junk"));
    }

    @Test
    void error_invalidMultiDirectionChar() {
        assertThrows(FilterRuleParser.ParseException.class, () -> parse("any NSWEZ is stone"));
    }

    // ── isBlockNamePart character set ─────────────────────────────────────────

    @Test
    void blockNamePart_letters() {
        assertTrue(FilterRuleParser.isBlockNamePart('a'));
        assertTrue(FilterRuleParser.isBlockNamePart('z'));
        assertTrue(FilterRuleParser.isBlockNamePart('A'));
        assertTrue(FilterRuleParser.isBlockNamePart('Z'));
    }

    @Test
    void blockNamePart_digits() {
        assertTrue(FilterRuleParser.isBlockNamePart('0'));
        assertTrue(FilterRuleParser.isBlockNamePart('9'));
    }

    @Test
    void blockNamePart_allowedSpecialChars() {
        assertTrue(FilterRuleParser.isBlockNamePart('_'));
        assertTrue(FilterRuleParser.isBlockNamePart('-'));
        assertTrue(FilterRuleParser.isBlockNamePart(':'));
        assertTrue(FilterRuleParser.isBlockNamePart('.'));
        assertTrue(FilterRuleParser.isBlockNamePart('@'));
        assertTrue(FilterRuleParser.isBlockNamePart(';')); // regression: was incorrectly rejected
    }

    @Test
    void blockNamePart_rejectedChars() {
        assertFalse(FilterRuleParser.isBlockNamePart('$'));
        assertFalse(FilterRuleParser.isBlockNamePart('!'));
        assertFalse(FilterRuleParser.isBlockNamePart('#'));
        assertFalse(FilterRuleParser.isBlockNamePart('('));
        assertFalse(FilterRuleParser.isBlockNamePart(')'));
        assertFalse(FilterRuleParser.isBlockNamePart(' '));
        assertFalse(FilterRuleParser.isBlockNamePart(','));
    }
}
