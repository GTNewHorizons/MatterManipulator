package com.recursive_pineapple.matter_manipulator.common.building.filter;

import cpw.mods.fml.common.registry.GameRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;

import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;

import com.github.bsideup.jabel.Desugar;

public final class FilterRuleParser {

    private final List<Token> tokens;
    private int index;

    private FilterRuleParser(String text) {
        this.tokens = tokenize(text);
        this.index = 0;
    }

    public static FilterRule parse(String text) {
        if (text == null || text.trim().isEmpty()) { throw new ParseException("Filter text cannot be empty"); }

        FilterRuleParser parser = new FilterRuleParser(text);
        FilterRule filter = parser.parseOr();

        parser.expect(TokenType.END, "end of filter");

        return new ParsedFilterRule(text, filter);
    }

    private FilterRule parseOr() {
        FilterRule result = parseAnd();

        while (matchWord("or")) {
            result = new OrFilterRule(result, parseAnd());
        }

        return result;
    }

    private FilterRule parseAnd() {
        FilterRule result = parsePrimary();

        while (matchWord("and")) {
            result = new AndFilterRule(result, parsePrimary());
        }

        return result;
    }

    private FilterRule parsePrimary() {
        if (match(TokenType.LEFT_PAREN)) {
            FilterRule inner = parseOr();
            expect(TokenType.RIGHT_PAREN, ")");
            return inner;
        }

        if (matchWord("not")) { return new NotFilterRule(parsePrimary()); }

        return parseBlockCheck();
    }

    private FilterRule parseBlockCheck() {
        OffsetSet offsetSet = parseOffsetSet();

        expectWord("is");

        boolean negate = matchWord("not");
        FilterBlockSpec blockSpec = parseBlockSpec();

        FilterRule rule = new BlockEqualsFilterRule(offsetSet, blockSpec.block, blockSpec.meta);
        if (negate) { return new NotFilterRule(rule); }

        return rule;
    }

    private Offset parseOffset() {
        Token token = peek();

        if (token.isWord("self") || token.isWord("here")) {
            next();
            return new Offset(0, 0, 0);
        }

        if (token.isWord("above") || token.isWord("up") || token.isWord("U")) {
            next();
            return new Offset(0, 1, 0);
        }

        if (token.isWord("below") || token.isWord("down") || token.isWord("D")) {
            next();
            return new Offset(0, -1, 0);
        }

        if (token.isWord("north") || token.isWord("N")) {
            next();
            return new Offset(0, 0, -1);
        }

        if (token.isWord("south") || token.isWord("S")) {
            next();
            return new Offset(0, 0, 1);
        }

        if (token.isWord("east") || token.isWord("E")) {
            next();
            return new Offset(1, 0, 0);
        }

        if (token.isWord("west") || token.isWord("W")) {
            next();
            return new Offset(-1, 0, 0);
        }

        if (matchWord("at")) {
            int dx = expectNumber("dx");
            int dy = expectNumber("dy");
            int dz = expectNumber("dz");

            return new Offset(dx, dy, dz);
        }

        throw error("Expected position: self, above, below, north, south, east, west, or at x y z");
    }

    private OffsetSet parseOffsetSet() {
        OffsetMode mode = OffsetMode.SINGLE;

        if (matchWord("any")) {
            mode = OffsetMode.ANY;
        } else if (matchWord("all")) {
            mode = OffsetMode.ALL;
        }

        Token token = peek();
        if (mode == OffsetMode.SINGLE) {
            if (token.isWord("self") || token.isWord("here") || token.isWord("X")) {
                next();
                return new OffsetSet(mode, Arrays.asList(new Offset(0, 0, 0)));
            }

            if (token.isWord("above") || token.isWord("up") || token.isWord("U")) {
                next();
                return new OffsetSet(mode, Arrays.asList(new Offset(0, 1, 0)));
            }

            if (token.isWord("below") || token.isWord("down") || token.isWord("D")) {
                next();
                return new OffsetSet(mode, Arrays.asList(new Offset(0, -1, 0)));
            }

            if (token.isWord("north") || token.isWord("N")) {
                next();
                return new OffsetSet(mode, Arrays.asList(new Offset(0, 0, -1)));
            }

            if (token.isWord("south") || token.isWord("S")) {
                next();
                return new OffsetSet(mode, Arrays.asList(new Offset(0, 0, 1)));
            }

            if (token.isWord("east") || token.isWord("E")) {
                next();
                return new OffsetSet(mode, Arrays.asList(new Offset(1, 0, 0)));
            }

            if (token.isWord("west") || token.isWord("W")) {
                next();
                return new OffsetSet(mode, Arrays.asList(new Offset(-1, 0, 0)));
            }

            if (token.isWord("at")) {
                next();

                int dx = expectNumber("dx");
                int dy = expectNumber("dy");
                int dz = expectNumber("dz");

                return new OffsetSet(mode, Arrays.asList(new Offset(dx, dy, dz)));
            }
            throw error("Expected position: self, here, above, below, north, south, east, west, X, N, S, E, W, U, D, or at x y z");
        } else {
            if (token.type == TokenType.WORD) {
                String word = token.text;

                List<Offset> offsets = new ArrayList<>();

                for (char c : word.toCharArray()) {
                    switch (c) {
                        case 'X' -> offsets.add(new Offset(0, 0, 0));
                        case 'N' -> offsets.add(new Offset(0, 0, -1));
                        case 'S' -> offsets.add(new Offset(0, 0, 1));
                        case 'W' -> offsets.add(new Offset(-1, 0, 0));
                        case 'E' -> offsets.add(new Offset(1, 0, 0));
                        case 'U' -> offsets.add(new Offset(0, 1, 0));
                        case 'D' -> offsets.add(new Offset(0, -1, 0));
                        default -> throw error(
                            "Invalid offset character '" + c
                                + "' in '"
                                + word
                                +
                                "'. Expected only X, N, S, W, E, U, or D."
                        );
                    }
                }

                if (!offsets.isEmpty()) {
                    next();

                    return new OffsetSet(mode, offsets);
                }
            }

            throw error("Expected compact offset after '" + mode.name().toLowerCase() + "': one or more of X, N, S, W, E, U, D, such as NSWE or XUD");
        }
    }

    private FilterBlockSpec parseBlockSpec() {
        Token token = expect(TokenType.WORD, "block name");

        String raw = token.text;

        int meta = -1; // -1 = any metadata / ignore metadata

        int at = raw.indexOf('@');
        if (at >= 0) {
            String metaText = raw.substring(at + 1);
            raw = raw.substring(0, at);

            if (metaText.isEmpty()) { throw error("Expected metadata value after '@'"); }

            try {
                meta = Integer.parseInt(metaText);
            } catch (NumberFormatException e) {
                throw error("Invalid metadata '" + metaText + "'");
            }
        }

        if (matchWord("meta")) {
            meta = expectNumber("metadata");
        }

        Block block;
        int colon = raw.indexOf(':');
        if (colon < 0) {
            block = GameRegistry.findBlock("minecraft", raw);
        } else {
            String modId = raw.substring(0, colon);
            String name = raw.substring(colon + 1);
            block = GameRegistry.findBlock(modId, name);
        }

        if (block == null) { throw error("Unknown block '" + token.text + "'"); }

        return new FilterBlockSpec(block, meta);
    }

    private static String normalizeWord(String text) {
        return text.trim().toLowerCase().replace('-', '_');
    }

    private static String normalizeBlockName(String text) {
        return text.trim().toLowerCase().replace('-', '_');
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token next() {
        return tokens.get(index++);
    }

    private boolean match(TokenType type) {
        if (peek().type == type) {
            next();
            return true;
        }

        return false;
    }

    private boolean matchWord(String word) {
        if (peek().isWord(word)) {
            next();
            return true;
        }

        return false;
    }

    private void expectWord(String word) {
        if (!matchWord(word)) { throw error("Expected '" + word + "'"); }
    }

    private Token expect(TokenType type, String expected) {
        Token token = peek();

        if (token.type != type) { throw error("Expected " + expected + ", got '" + token.text + "'"); }

        return next();
    }

    private int expectNumber(String name) {
        Token token = expect(TokenType.NUMBER, name);

        try {
            return Integer.parseInt(token.text);
        } catch (NumberFormatException e) {
            throw error("Invalid number '" + token.text + "'");
        }
    }

    private ParseException error(String message) {
        return new ParseException(message + " at token " + index);
    }

    private static List<Token> tokenize(String text) {
        List<Token> result = new ArrayList<Token>();

        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }

            if (c == '(') {
                result.add(new Token(TokenType.LEFT_PAREN, "("));
                i++;
                continue;
            }

            if (c == ')') {
                result.add(new Token(TokenType.RIGHT_PAREN, ")"));
                i++;
                continue;
            }

            if (c == '-' || Character.isDigit(c)) {
                int start = i;
                i++;

                while (i < text.length() && Character.isDigit(text.charAt(i))) {
                    i++;
                }

                result.add(new Token(TokenType.NUMBER, text.substring(start, i)));
                continue;
            }

            if (isBlockNameStart(c)) {
                int start = i;
                i++;

                while (i < text.length() && isBlockNamePart(text.charAt(i))) {
                    i++;
                }

                result.add(new Token(TokenType.WORD, text.substring(start, i)));
                continue;
            }

            throw new ParseException("Unexpected character '" + c + "' at index " + i);
        }

        result.add(new Token(TokenType.END, "<end>"));
        return result;
    }

    private static boolean isBlockNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isBlockNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '.' || c == '@';
    }

    public static final class Offset {

        public final int dx;
        public final int dy;
        public final int dz;

        private Offset(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    enum OffsetMode {
        SINGLE,
        ANY,
        ALL
    }

    @Desugar
    public record OffsetSet(OffsetMode mode, List<Offset> offsets) {}

    private enum TokenType {
        WORD,
        NUMBER,
        LEFT_PAREN,
        RIGHT_PAREN,
        END
    }

    private static final class Token {

        private final TokenType type;
        private final String text;

        private Token(TokenType type, String text) {
            this.type = type;
            this.text = text;
        }

        private boolean isWord(String word) {
            return type == TokenType.WORD && normalizeWord(text).equals(normalizeWord(word));
        }
    }

    public static final class ParseException extends RuntimeException {

        public ParseException(String message) {
            super(message);
        }
    }

    private static final class FilterBlockSpec {

        private final Block block;
        private final int meta;

        private FilterBlockSpec(Block block, int meta) {
            this.block = block;
            this.meta = meta;
        }
    }
}
