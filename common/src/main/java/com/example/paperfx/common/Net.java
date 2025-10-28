package com.example.paperfx.common;

import com.google.gson.*;

public final class Net {
    private Net() {}

    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public static JsonObject parse(String line) {
        return JsonParser.parseString(line).getAsJsonObject();
    }

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }
}
