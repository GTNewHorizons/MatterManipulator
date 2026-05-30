package com.recursive_pineapple.matter_manipulator.common.building.filter;

import com.github.bsideup.jabel.Desugar;

public interface FilterAST {

    @Desugar
    record Condition(FilterRuleParser.OffsetSet position, boolean negated, String block, int meta)
        implements FilterAST {}

    @Desugar
    record And(FilterAST left, FilterAST right) implements FilterAST {}

    @Desugar
    record Or(FilterAST left, FilterAST right) implements FilterAST {}

    @Desugar
    record Not(FilterAST inner) implements FilterAST {}
}
