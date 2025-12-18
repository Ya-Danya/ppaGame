package com.example.paperfx.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Вспомогательные методы для JSON (Jackson) и протокола JSONL. */
public final class Net {
    private Net() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /**
     * Парсит входящую строку JSON в дерево {@link JsonNode}.
     */

    public static JsonNode parse(String line) throws JsonProcessingException {
        return MAPPER.readTree(line);
    }
    /**
     * Сериализует объект/DTO в строку JSON.
     */

    public static String toJson(Object o) throws JsonProcessingException {
        return MAPPER.writeValueAsString(o);
    }
}