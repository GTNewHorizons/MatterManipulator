package com.recursive_pineapple.matter_manipulator.common.building.filter;

import net.minecraft.world.World;

public final class ParsedFilterRule implements FilterRule, StringSerializableRule {

    private final String source;
    private final FilterRule delegate;

    public ParsedFilterRule(String source, FilterRule delegate) {
        this.source = source;
        this.delegate = delegate;
    }

    @Override
    public boolean matches(World world, int x, int y, int z) {
        return delegate.matches(world, x, y, z);
    }

    @Override
    public String asString() {
        return source;
    }

    @Override
    public int hashCode() {
        return this.source.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        ParsedFilterRule other = (ParsedFilterRule) obj;
        return this.source.equals(other.source);
    }
}
