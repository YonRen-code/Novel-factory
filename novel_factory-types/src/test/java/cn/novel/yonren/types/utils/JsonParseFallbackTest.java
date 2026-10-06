package cn.novel.yonren.types.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonParseFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parse_validJsonParsesDirectly() {
        String raw = "{\"chapters\":[{\"chapterNo\":1,\"title\":\"标题\"}]}";

        JsonNode result = JsonParseFallback.parse(raw, JsonParseFallbackTest::parseTree);

        assertNotNull(result);
        assertTrue(result.has("chapters"));
    }

    @Test
    void parse_repairsDanglingComma() {
        String bad = "{\"issues\":[{\"dimension\":\"aesthetic\",\"severity\":\"MINOR\",\"evidence\":\"原文\",\"description\":\"AI味\",\"suggestion\":\"改\"},]}";

        JsonNode result = JsonParseFallback.parse(bad, JsonParseFallbackTest::parseTree);

        assertNotNull(result);
        assertTrue(result.has("issues"));
    }

    @Test
    void parse_stripsMarkdownFence() {
        String raw = "```json\n{\"summary\":\"剧情。\"}\n```";

        JsonNode result = JsonParseFallback.parse(raw, JsonParseFallbackTest::parseTree);

        assertNotNull(result);
        assertEquals("剧情。", result.get("summary").asText());
    }

    @Test
    void parse_returnsNullOnGarbage() {
        assertNull(JsonParseFallback.parse("不是 JSON", JsonParseFallbackTest::parseTree));
    }

    @Test
    void parse_returnsNullOnNullAndBlank() {
        assertNull(JsonParseFallback.parse(null, JsonParseFallbackTest::parseTree));
        assertNull(JsonParseFallback.parse("   ", JsonParseFallbackTest::parseTree));
    }

    @Test
    void parse_repairsInteriorQuotesInStringValues() {
        // 模拟 glm-4-air 在 keyEvents 里输出裸引号的故障模式
        String bad = "{\"chapters\":[{\"chapterNo\":1,\"title\":\"青崖蒙冤\","
                + "\"keyEvents\":[\"对方提及\"照雪计划\"\"," + "\"断刀发出微弱震动\"]}]}";

        JsonNode result = JsonParseFallback.parse(bad, JsonParseFallbackTest::parseTree);

        assertNotNull(result);
        assertEquals("对方提及\"照雪计划\"",
                result.get("chapters").get(0).get("keyEvents").get(0).asText());
    }

    @Test
    void parse_repairsCommaInKeyAndInteriorQuotesTogether() {
        // 实测故障：键名吞逗号（"title,"身世"）+ 值内裸引号同时出现
        String bad = "{\"chapters\":[{\"chapterNo\":8,\"title,\"身世\",\"goal\":\"追查身世\","
                + "\"keyEvents\":[\"提及\"寒江令\"\"]}]}";

        JsonNode result = JsonParseFallback.parse(bad, JsonParseFallbackTest::parseTree);

        assertNotNull(result);
        JsonNode chapter = result.get("chapters").get(0);
        assertEquals("身世", chapter.get("title").asText());
        assertEquals("提及\"寒江令\"", chapter.get("keyEvents").get(0).asText());
    }

    @Test
    void parse_supportsTypedTarget() {
        String raw = "{\"chapterNo\":1,\"title\":\"标题\",\"content\":\"正文\"}";

        Chapter chapter = JsonParseFallback.parse(raw, text -> {
            try {
                return MAPPER.readValue(text, Chapter.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertNotNull(chapter);
        assertEquals("标题", chapter.title);
    }

    private static JsonNode parseTree(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chapter {
        public int chapterNo;
        public String title;
        public List<String> keyEvents;
    }

}
