package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.QualityReviewProperties;
import cn.novel.yonren.types.enums.ModelScene;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全书质量评分测试（2026-09-27）：
 * 抽样均匀性、按窗口触发的门禁、趋势行内容、以及**全程 fail-soft**（模型异常/输出不可解析都不得抛出）。
 */
class QualityReviewServiceTest {

    private LlmGateway llmGateway;
    private IStoryRepository storyRepository;
    private QualityReviewProperties properties;

    private final Path storyDir = Paths.get("docs/workspace/stories/20260927-story-0001");

    /** 模型输出不带 chapterNo：顺带覆盖"单章调用时回填章号"的容错分支 */
    private static final String REVIEW_JSON = "{\"reviews\":[{\"personaConsistency\":8,\"prose\":6,"
            + "\"dialogue\":6,\"expectation\":6,\"payoff\":6,\"problems\":[\"第3段空转\"],"
            + "\"highlight\":\"搪瓷缸那一脚\"}]}";

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        storyRepository = mock(IStoryRepository.class);
        properties = new QualityReviewProperties();
        properties.setIntervalChapters(10);
        properties.setSampleSize(3);
        properties.setRubricVersion("v1");
    }

    private QualityReviewService service() {
        return new QualityReviewService(llmGateway, storyRepository, properties);
    }

    private StoryVO storyVO() {
        StoryVO vo = new StoryVO();
        vo.setModule(new StoryVO.Module());
        return vo;
    }

    // ------------------------------------------------------------------ 抽样

    @Test
    void sampleEvenly_keepsHeadAndTailWithoutDuplicates() {
        List<Integer> all = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            all.add(i);
        }

        List<Integer> picked = QualityReviewService.sampleEvenly(all, 3);

        assertEquals(List.of(1, 6, 10), picked, "首尾必取且等距：漏掉窗口前段会让'前段退化'完全不可见");
    }

    @Test
    void sampleEvenly_smallWindowTakesAll() {
        assertEquals(List.of(1, 2, 3), QualityReviewService.sampleEvenly(List.of(1, 2, 3), 5));
        assertEquals(List.of(1), QualityReviewService.sampleEvenly(List.of(1, 2, 3), 1));
        assertTrue(QualityReviewService.sampleEvenly(List.of(), 3).isEmpty());
    }

    // ------------------------------------------------------------- 触发门禁

    @Test
    void skipsWhenChapterNotOnIntervalBoundary() throws Exception {
        assertDoesNotThrow(() -> service().reviewWindow(storyDir, storyVO(), List.of(), 9));

        verify(llmGateway, never()).complete(any(), any());
        verify(storyRepository, never()).appendQualityTrend(any(), anyString());
        // 未到窗口边界时连章节清单都不该读（避免每章一次无谓 IO）
        verify(storyRepository, never()).readChapterNumbers(any());
    }

    @Test
    void skipsWhenDisabled() throws Exception {
        properties.setEnabled(false);

        assertDoesNotThrow(() -> service().reviewWindow(storyDir, storyVO(), List.of(), 10));

        verify(llmGateway, never()).complete(any(), any());
        verify(storyRepository, never()).appendQualityTrend(any(), anyString());
    }

    // ------------------------------------------------------------- 趋势行

    @Test
    void writesTrendLineWithScoresAndMechanicalMetrics() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(numbers(1, 10));
        when(storyRepository.readChapter(eq(storyDir), anyInt()))
                .thenAnswer(inv -> ChapterContentEntity.builder()
                        .chapterNo(inv.getArgument(1))
                        .title("第" + inv.getArgument(1) + "章")
                        .content("正文内容".repeat(50))
                        .build());
        when(llmGateway.complete(any(), any())).thenReturn(REVIEW_JSON);

        service().reviewWindow(storyDir, storyVO(), summaries(), 10);

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(storyRepository).appendQualityTrend(eq(storyDir), line.capture());
        JSONObject row = JSON.parseObject(line.getValue());

        assertEquals("v1", row.getString("rubricVersion"), "量表版本必须随行落盘，否则趋势线会混两把尺子");
        assertEquals(1, row.getIntValue("windowStart"));
        assertEquals(10, row.getIntValue("windowEnd"));
        assertEquals(List.of(1, 6, 10), row.getJSONArray("sampledChapters").toJavaList(Integer.class));
        assertEquals(3, row.getJSONArray("samples").size());
        // 模型漏填章号时应回填为本次评分的章号（否则该样本无法归因到具体章节）
        assertEquals(1, row.getJSONArray("samples").getJSONObject(0).getIntValue("chapterNo"));
        assertEquals(6, row.getJSONArray("samples").getJSONObject(1).getIntValue("chapterNo"));
        assertEquals(10, row.getJSONArray("samples").getJSONObject(2).getIntValue("chapterNo"));

        JSONObject avg = row.getJSONObject("avg");
        assertEquals(8.0, avg.getDoubleValue("personaConsistency"), 0.001);
        assertEquals(6.4, row.getDoubleValue("overall"), 0.001, "综合分=五维等权平均");

        JSONObject mech = row.getJSONObject("mechanical");
        assertEquals(10, mech.getIntValue("chapterCount"));
        assertEquals(1369, mech.getIntValue("minValidChars"));
        assertEquals(1936.9, mech.getDoubleValue("avgValidChars"), 0.001, "其余 9 章 2000 字 + 1 章 1369 字");
        assertEquals(1L, mech.getLongValue("chaptersBelow1500"));
        assertEquals(0.5, mech.getDoubleValue("avgDialogueRatio"), 0.001);

        ArgumentCaptor<LlmCall> call = ArgumentCaptor.forClass(LlmCall.class);
        verify(llmGateway, times(3)).complete(any(), call.capture());
        assertEquals(ModelScene.QUALITY_REVIEW, call.getValue().getScene(),
                "必须走独立场景，成本与效果才能单独核算");
        assertTrue(call.getValue().getLabel().startsWith("quality-review-第"));
    }

    @Test
    void windowIsClampedToExistingChapters() throws Exception {
        // 续写场景：窗口 1-10，但目录里只有 6-10 章（前段在上一批且未落在本目录）
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(numbers(6, 10));
        when(storyRepository.readChapter(eq(storyDir), anyInt()))
                .thenAnswer(inv -> ChapterContentEntity.builder()
                        .chapterNo(inv.getArgument(1)).content("正文".repeat(80)).build());
        when(llmGateway.complete(any(), any())).thenReturn(REVIEW_JSON);

        service().reviewWindow(storyDir, storyVO(), List.of(), 10);

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(storyRepository).appendQualityTrend(eq(storyDir), line.capture());
        List<Integer> sampled = JSON.parseObject(line.getValue())
                .getJSONArray("sampledChapters").toJavaList(Integer.class);
        assertEquals(List.of(6, 8, 10), sampled, "只在真实存在的章节里抽样");
    }

    // ------------------------------------------------------------- fail-soft

    @Test
    void failsSoftWhenModelThrows() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(numbers(1, 10));
        when(storyRepository.readChapter(eq(storyDir), anyInt()))
                .thenReturn(ChapterContentEntity.builder().content("正文".repeat(80)).build());
        when(llmGateway.complete(any(), any())).thenThrow(new RuntimeException("上游 524"));

        assertDoesNotThrow(() -> service().reviewWindow(storyDir, storyVO(), List.of(), 10),
                "评分失败不得反噬生成主流程");
        verify(storyRepository, never()).appendQualityTrend(any(), anyString());
    }

    @Test
    void failsSoftWhenOutputUnparsable() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(numbers(1, 10));
        when(storyRepository.readChapter(eq(storyDir), anyInt()))
                .thenReturn(ChapterContentEntity.builder().content("正文".repeat(80)).build());
        when(llmGateway.complete(any(), any())).thenReturn("这不是 JSON");

        assertDoesNotThrow(() -> service().reviewWindow(storyDir, storyVO(), List.of(), 10));
        verify(storyRepository, never()).appendQualityTrend(any(), anyString());
    }

    @Test
    void failsSoftWhenTrendWriteThrows() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(numbers(1, 10));
        when(storyRepository.readChapter(eq(storyDir), anyInt()))
                .thenReturn(ChapterContentEntity.builder().content("正文".repeat(80)).build());
        when(llmGateway.complete(any(), any())).thenReturn(REVIEW_JSON);
        org.mockito.Mockito.doThrow(new java.io.IOException("磁盘只读"))
                .when(storyRepository).appendQualityTrend(any(), anyString());

        assertDoesNotThrow(() -> service().reviewWindow(storyDir, storyVO(), List.of(), 10));
    }

    @Test
    void skipsWhenNoChapterInWindow() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(numbers(1, 4));

        service().reviewWindow(storyDir, storyVO(), List.of(), 10);

        verify(llmGateway, never()).complete(any(), any());
        verify(storyRepository, never()).appendQualityTrend(any(), anyString());
    }

    @Test
    void skipsWhenModuleMissing() throws Exception {
        assertDoesNotThrow(() -> service().reviewWindow(storyDir, new StoryVO(), List.of(), 10));

        verify(llmGateway, never()).complete(any(), any());
    }
    // ------------------------------------------------------------------ 工具

    private List<Integer> numbers(int from, int to) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            list.add(i);
        }
        return list;
    }

    /** 10 章摘要：仅第 4 章有效字数 1369（模拟实测过的"不达标章"），对白比统一 0.5 */
    private List<ChapterSummaryEntity> summaries() {
        List<ChapterSummaryEntity> list = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            list.add(ChapterSummaryEntity.builder()
                    .chapterNo(i)
                    .validChars(i == 4 ? 1369 : 2000)
                    .dialogueRatio(0.5)
                    .build());
        }
        return list;
    }

    @Test
    void trendLineIsStrictJson() throws Exception {
        when(storyRepository.readChapterNumbers(storyDir)).thenReturn(numbers(1, 10));
        when(storyRepository.readChapter(eq(storyDir), anyInt()))
                .thenReturn(ChapterContentEntity.builder().content("正文".repeat(80)).build());
        when(llmGateway.complete(any(), any())).thenReturn(REVIEW_JSON);

        service().reviewWindow(storyDir, storyVO(), summaries(), 10);

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(storyRepository).appendQualityTrend(eq(storyDir), line.capture());
        // 趋势文件是给分析脚本/jq 读的，必须是严格合法 JSON（同类缺陷见 job-status.json 的整数键问题）
        assertNotNull(new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.getValue()));
    }
}
