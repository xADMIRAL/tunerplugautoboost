package io.github.xadmiral.boostautotune.plugin.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test
    void parsesTheShapesTheApiUses() {
        String text = "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"Hi \\u0416 \\\"q\\\" \\n\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"read_parameters\",\"input\":{\"names\":[\"fc_rpm\",\"OvrRunC\"]}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":1234,\"output_tokens\":56,\"x\":-1.5e2,\"ok\":true,\"none\":null}}";
        Map<String, Object> m = Json.obj(Json.parse(text));
        assertNotNull(m);
        assertEquals("msg_1", Json.str(m, "id"));
        List<Object> content = Json.arr(m.get("content"));
        assertEquals(2, content.size());
        assertEquals("Hi \u0416 \"q\" \n", Json.str(Json.obj(content.get(0)), "text"));
        Map<String, Object> tool = Json.obj(content.get(1));
        assertEquals("read_parameters", Json.str(tool, "name"));
        assertEquals("fc_rpm", Json.arr(Json.obj(tool.get("input")).get("names")).get(0));
        Map<String, Object> usage = Json.obj(m.get("usage"));
        assertEquals(1234L, usage.get("input_tokens"));
        assertEquals(-150.0, Json.num(usage, "x", 0), 1e-9);
        assertEquals(Boolean.TRUE, usage.get("ok"));
        assertTrue(usage.containsKey("none"));
        assertNull(usage.get("none"));
    }

    @Test
    void writesWhatItParsesBack() {
        Map<String, Object> m = Json.object();
        m.put("a", "line\nbreak \"quoted\" back\\slash \u0007");
        m.put("n", 42L);
        m.put("d", 0.5);
        m.put("whole", 3.0);
        m.put("nan", Double.NaN);
        m.put("arr", new double[]{1, 2.25});
        m.put("b", Boolean.FALSE);
        m.put("z", null);
        String text = Json.write(m);
        assertEquals("{\"a\":\"line\\nbreak \\\"quoted\\\" back\\\\slash \\u0007\",\"n\":42,\"d\":0.5,\"whole\":3,\"nan\":null,\"arr\":[1,2.25],\"b\":false,\"z\":null}", text);
        Map<String, Object> back = Json.obj(Json.parse(text));
        assertEquals(m.get("a"), back.get("a"));
        assertEquals(42L, back.get("n"));
        assertEquals(0.5, Json.num(back, "d", 0), 1e-12);
        assertEquals(3L, back.get("whole"));
        assertEquals(2, Json.arr(back.get("arr")).size());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                Json.parse("{\"a\":1,}");
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                Json.parse("[1 2]");
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                Json.parse("\"open");
            }
        });
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            public void execute() {
                Json.parse("{} x");
            }
        });
    }
}
