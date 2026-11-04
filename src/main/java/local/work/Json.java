package local.work;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static ObjectNode object() { return MAPPER.createObjectNode(); }
    static ObjectNode obj(Object... pairs) {
        ObjectNode node = object();
        for (int i = 0; i < pairs.length; i += 2) node.set((String) pairs[i], MAPPER.valueToTree(pairs[i + 1]));
        return node;
    }
    static JsonNode read(String text) {
        try { return MAPPER.readTree(text); }
        catch (IOException e) { throw new IllegalArgumentException("JSON 형식을 확인하세요.", e); }
    }
    static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
    }
    static String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " 값을 입력하세요.");
        return value;
    }
}
