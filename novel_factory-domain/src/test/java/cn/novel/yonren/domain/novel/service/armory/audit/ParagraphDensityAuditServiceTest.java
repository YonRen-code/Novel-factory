package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 段落信息增量审校测试。
 *
 * <p>要点：只产出**局部改写补丁**（不是整章重写），且任何环节失败都必须保留原稿——
 * 它是一个增强件，不得阻断生成。默认关闭。
 */
class ParagraphDensityAuditServiceTest {

    /** 中间那段是典型的"原地循环同一种情绪"，无任何状态变化 */
    private static final String CONTENT =
            "江燃站在窗前，外面在下雨。\n\n"
                    + "他感到很愤怒。他真的很愤怒。\n\n"
                    + "他打开电脑，看到了许知意发来的消息。";

    private LlmGateway llmGateway;
    private StoryProperties storyProperties;
    private ParagraphDensityAuditService service;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        storyProperties = new StoryProperties();
        service = new ParagraphDensityAuditService(llmGateway, storyProperties);
    }

    @Test
    void disabledByDefault_neverCallsLlm() {
        assertFalse(storyProperties.getParagraphAudit().isEnabled(), "新链路默认关闭");

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
        verifyNoInteractions(llmGateway);
    }

    @Test
    void emptyPatches_keepsOriginal() {
        enable();
        stub("{\"patches\":[]}");

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
    }

    @Test
    void validPatch_isAppliedLocally() {
        enable();
        stub("{\"patches\":[{\"anchor\":\"他感到很愤怒。他真的很愤怒。\","
                + "\"replacement\":\"他攥紧了鼠标，指节泛白。\"}]}");

        String result = service.auditAndRewrite(module(), item(), CONTENT, 1);

        assertNotNull(result);
        assertTrue(result.contains("他攥紧了鼠标，指节泛白。"), "应套用改写");
        assertFalse(result.contains("他真的很愤怒。"), "注水句应被替换掉");
        // 局部改写：其余段落原样保留
        assertTrue(result.contains("江燃站在窗前，外面在下雨。"));
        assertTrue(result.contains("他打开电脑，看到了许知意发来的消息。"));
    }

    @Test
    void anchorNotFoundInContent_keepsOriginal() {
        enable();
        // 锚点必须在正文中逐字存在且唯一——找不到即整批作废（宁可少改，不可改错位置）
        stub("{\"patches\":[{\"anchor\":\"这句话正文里根本没有\",\"replacement\":\"x\"}]}");

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
    }

    @Test
    void maxPatchesIsEnforced() {
        enable();
        storyProperties.getParagraphAudit().setMaxPatches(1);
        // 给出两条锚点唯一的补丁，但上限为 1 → 只套用第一条
        stub("{\"patches\":["
                + "{\"anchor\":\"他感到很愤怒。他真的很愤怒。\",\"replacement\":\"他攥紧了鼠标。\"},"
                + "{\"anchor\":\"江燃站在窗前，外面在下雨。\",\"replacement\":\"雨敲在窗玻璃上。\"}]}");

        String result = service.auditAndRewrite(module(), item(), CONTENT, 1);

        assertNotNull(result);
        assertTrue(result.contains("他攥紧了鼠标。"));
        assertTrue(result.contains("江燃站在窗前，外面在下雨。"), "超出上限的补丁不得被套用");
    }

    @Test
    void llmFailure_keepsOriginal() {
        enable();
        when(llmGateway.complete(any(), any())).thenThrow(new RuntimeException("boom"));

        assertNull(service.auditAndRewrite(module(), item(), CONTENT, 1));
    }

    @Test
    void promptCarriesIncrementalCriteriaAndSafetyRules() {
        enable();
        stub("{\"patches\":[]}");

        service.auditAndRewrite(module(), item(), CONTENT, 7);

        ArgumentCaptor<LlmCall> captor = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway).complete(any(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();

        assertTrue(prompt.contains("没有带来任何信息增量"), "必须给出信息增量的判定标准");
        assertTrue(prompt.contains("信息增量指"), "必须说明什么算增量（否则模型会把有变化的段落也改掉）");
        assertTrue(prompt.contains("不要改变剧情走向") && prompt.contains("不要新增情节"),
                "改写必须限定在挤水，不得改剧情");
        assertTrue(prompt.contains("anchor 必须逐字来自正文"), "必须强调锚点逐字性");
        assertTrue(prompt.contains("第") || prompt.contains("正文"), "必须带上正文");
        assertEquals("paragraph-audit-第7章", captor.getValue().getLabel());
    }

    private void enable() {
        storyProperties.getParagraphAudit().setEnabled(true);
    }

    private void stub(String response) {
        when(llmGateway.complete(any(), any())).thenReturn(response);
    }

    private StoryVO.Module module() {
        return new StoryVO.Module();
    }

    private ChapterPlanItemEntity item() {
        return ChapterPlanItemEntity.builder()
                .chapterNo(7).title("第七章").goal("江燃掩饰身份")
                .keyEvents(List.of("收到消息")).endingHook("他停下手里的动作")
                .build();
    }
}
