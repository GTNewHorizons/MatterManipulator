package com.recursive_pineapple.matter_manipulator.common.building.movers;

import static com.recursive_pineapple.matter_manipulator.common.building.MultipartAnalysisResult.PartData;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record FMPBlock(PartData[] parts) {

}
