package com.recursive_pineapple.matter_manipulator.common.building.filter;

import java.util.List;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record OffsetSet(OffsetMode mode, List<Offset> offsets) {}
