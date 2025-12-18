package com.example.paperfx.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Вспомогательные методы для JSON-сериализации/десериализации (Jackson).
 * <p>
 * Используется и в TCP JSONL-режиме, и в UDP-режиме (1 датаграмма = 1 JSON).
 */
public final class Net {
    private Net() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static JsonNode parse(String line) throws JsonProcessingException {
        return MAPPER.readTree(line);
    }

    public static String toJson(Object o) throws JsonProcessingException {
        return MAPPER.writeValueAsString(o);
    }
}
