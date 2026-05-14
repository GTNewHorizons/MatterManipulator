package com.recursive_pineapple.matter_manipulator.common.persist;

import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterRule;
import com.recursive_pineapple.matter_manipulator.common.building.filter.FilterRuleParser;
import com.recursive_pineapple.matter_manipulator.common.building.filter.StringSerializableRule;

public class FilterRuleJsonAdapter implements JsonSerializer<FilterRule>, JsonDeserializer<FilterRule> {

    @Override
    public FilterRule deserialize(final JsonElement json, final Type typeOfT, final JsonDeserializationContext context) throws JsonParseException {
        if (json == null || json.isJsonNull() || (json.isJsonObject() && json.getAsJsonObject().entrySet().isEmpty())) { return null; }

        if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) { throw new JsonParseException("FilterRule must be a string"); }

        String text = json.getAsString();

        try {
            return FilterRuleParser.parse(text);
        } catch (RuntimeException e) {
            throw new JsonParseException("Failed to parse FilterRule: " + text, e);
        }
    }

    @Override
    public JsonElement serialize(final FilterRule src, final Type typeOfSrc, final JsonSerializationContext context) {
        if (src == null) { return JsonNull.INSTANCE; }

        if (!(src instanceof StringSerializableRule)) {
            throw new JsonParseException(
                "FilterRule is not string-serializable. " +
                    "Rules should originate from FilterRuleParser.parse(...) "
                    +
                    "or implement StringSerializableRule."
            );
        }
        return new JsonPrimitive(((StringSerializableRule) src).asString());
    }
}
