package cn.novel.yonren.domain.novel.service.armory.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文风指纹库测试：长度过滤、疲劳词剔除、含数字优先、滚动上限 50 FIFO、渲染禁令措辞
 */
class StyleFingerprintTest {

    private final StyleStatService service = new StyleStatService();

    @Test
    void extract_prefersDigitSentences_withinLengthBounds() {
        String content = "短句。"
                + "他数了数袋里的灵石，整整320枚，够他在外门撑过半年。"
                + "这是一句不带任何数字与疲劳词的普通叙述，长度刚好达到提取下限的标准。"
                + "这一句实在太长太长太长太长太长太长太长太长太长太长太长太长太长太长太长太长太长太长太长太长太长，超出上限不参与提取。";

        List<String> picked = service.extractFingerprints(content);

        assertEquals(2, picked.size(), "单章至多 2 条");
        assertTrue(picked.get(0).contains("320枚"), "含数字的信息密度句优先排在首位");
    }

    @Test
    void extract_dropsFatigueSentences() {
        // "喉结滚动" 在疲劳词表：含疲劳词的句子不入指纹库
        String content = "他喉结滚动了一下，把到嘴边的质问咽了回去，转身走向大殿深处的长廊。";
        assertTrue(service.extractFingerprints(content).isEmpty());
    }

    @Test
    void merge_rollsOverAtFifty() {
        List<String> fingerprints = new ArrayList<>();
        List<String> additions = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            additions.add("指纹句子" + i);
        }
        service.mergeFingerprints(fingerprints, additions);

        assertEquals(50, fingerprints.size());
        assertEquals("指纹句子10", fingerprints.get(0), "FIFO 挤出最老");
        assertEquals("指纹句子59", fingerprints.get(fingerprints.size() - 1));
    }

    @Test
    void merge_skipsDuplicates() {
        List<String> fingerprints = new ArrayList<>(List.of("已有指纹句"));
        service.mergeFingerprints(fingerprints, List.of("已有指纹句", "新指纹句"));

        assertEquals(2, fingerprints.size());
    }

    @Test
    void render_containsBaselineWordingAndBan() {
        String rendered = service.renderFingerprints(List.of("他数了数袋里的灵石，整整三百二十枚。"));

        assertTrue(rendered.contains("文风基准"));
        assertTrue(rendered.contains("严禁逐字复用"));
    }

    @Test
    void render_empty_returnsEmpty() {
        assertEquals("", service.renderFingerprints(null));
        assertEquals("", service.renderFingerprints(List.of()));
    }
}
