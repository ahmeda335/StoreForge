package dev.storeforge.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    /** Builds a JSON object from alternating key/value strings, escaping values properly. */
    public static String object(String... keyValues) {
        var node = MAPPER.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return node.toString();
    }

    /** A JSON array of strings, properly escaped. */
    public static String array(Object... values) {
        var node = MAPPER.createArrayNode();
        for (Object value : values) {
            node.add(String.valueOf(value));
        }
        return node.toString();
    }
}
