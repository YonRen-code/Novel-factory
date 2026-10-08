package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节计划机械规则测试（唯一实现：ChapterPlanChecks）：
 * 编号校正防线（批内编号平移为全局编号）+ 结构校验 + 主线推进校验
 */
class ChapterPlanChecksTest {

    // ---- 编号校正（自 ParseChapterPlanNode 迁移）----

    @Test
    void normalize_shiftsBatchLocalNumbers() {
        ChapterPlanAggregate aggregate = planOf(1, 2, 3);

        ChapterPlanChecks.normalizeChapterNumbers(aggregate, 3);

        assertEquals(List.of(4, 5, 6), aggregate.getChapters().stream().map(ChapterPlanItemEntity::getChapterNo).toList());
    }

    @Test
    void normalize_keepsGlobalNumbers() {
        ChapterPlanAggregate aggregate = planOf(4, 5);

        ChapterPlanChecks.normalizeChapterNumbers(aggregate, 3);

        assertEquals(List.of(4, 5), aggregate.getChapters().stream().map(ChapterPlanItemEntity::getChapterNo).toList());
    }

    @Test
    void normalize_noopWhenFirstRun() {
        ChapterPlanAggregate aggregate = planOf(1, 2);

        ChapterPlanChecks.normalizeChapterNumbers(aggregate, 0);

        assertEquals(List.of(1, 2), aggregate.getChapters().stream().map(ChapterPlanItemEntity::getChapterNo).toList());
    }

    @Test
    void normalize_toleratesNullAggregateAndNullNumbers() {
        ChapterPlanChecks.normalizeChapterNumbers(null, 3);

        // 首章编号可判定（=1）时全量平移，编号缺失的条目原样保留
        ChapterPlanAggregate aggregate = ChapterPlanAggregate.builder()
                .chapters(List.of(item(1), item(null)))
                .build();
        ChapterPlanChecks.normalizeChapterNumbers(aggregate, 3);

        assertEquals(4, aggregate.getChapters().get(0).getChapterNo());
        assertNull(aggregate.getChapters().get(1).getChapterNo());
    }

    /**
     * 回归钉：分段规划路径先按段起点平移一次（offset=段起点-1），
     * 树尾 ParseChapterPlanNode 再以续写偏移跑一次——平移后首章不再是 1，第二次必须 no-op，
     * 否则整批章节号会被双重平移
     */
    @Test
    void normalize_segmentPreShift_thenTreeTailRerunDoesNotDoubleShift() {
        ChapterPlanAggregate aggregate = planOf(1, 2, 3);

        // 段起点 71：段内编号 1/2/3 先平移为 71/72/73
        ChapterPlanChecks.normalizeChapterNumbers(aggregate, 70);
        // 树尾节点以同一续写偏移（chapterOffset=70）再跑一次
        ChapterPlanChecks.normalizeChapterNumbers(aggregate, 70);

        assertEquals(List.of(71, 72, 73), aggregate.getChapters().stream().map(ChapterPlanItemEntity::getChapterNo).toList());
    }

    // ---- 结构校验（自 ValidateChapterPlanNode 迁移）----

    @Test
    void validate_passesWhenStartsAtExpectedGlobalNo() {
        ChapterPlanAggregate aggregate = ChapterPlanAggregate.builder()
                .chapters(List.of(item(4, "目标A"), item(5, "目标B")))
                .build();

        assertDoesNotThrow(() -> ChapterPlanChecks.validateSegmentStructure(aggregate.getChapters(), 2, 4));
    }

    @Test
    void validate_failsWhenBatchLocalNumbersOnResume() {
        // 模型惯性输出批内编号 1,2，校正防线失效时校验必须拦截
        ChapterPlanAggregate aggregate = ChapterPlanAggregate.builder()
                .chapters(List.of(item(1, "目标A"), item(2, "目标B")))
                .build();

        assertThrows(AppException.class,
                () -> ChapterPlanChecks.validateSegmentStructure(aggregate.getChapters(), 2, 4));
    }

    @Test
    void validate_failsWhenCountMismatch() {
        ChapterPlanAggregate aggregate = ChapterPlanAggregate.builder()
                .chapters(List.of(item(4, "目标A")))
                .build();

        AppException ex = assertThrows(AppException.class,
                () -> ChapterPlanChecks.validateSegmentStructure(aggregate.getChapters(), 2, 4));
        assertTrue(ex.getInfo().contains("章数不符"),
                "章数不符应给出可诊断信息，而非模糊的'参数不能为空'：" + ex.getInfo());
    }

    @Test
    void validate_reportsMissingFieldWithChapterNo() {
        // 章数正确但某章缺必填字段：报错必须指明章号与缺失字段，便于区分章数问题与字段问题
        ChapterPlanAggregate aggregate = ChapterPlanAggregate.builder()
                .chapters(List.of(ChapterPlanItemEntity.builder().chapterNo(4).title("标题").goal("目标").build()))
                .build();

        AppException ex = assertThrows(AppException.class,
                () -> ChapterPlanChecks.validateSegmentStructure(aggregate.getChapters(), 1, 4));
        assertTrue(ex.getInfo().contains("第 4 章"), ex.getInfo());
        assertTrue(ex.getInfo().contains("keyEvents"), ex.getInfo());
    }

    @Test
    void validate_failsWhenNullChapters() {
        assertThrows(AppException.class, () -> ChapterPlanChecks.validateSegmentStructure(null, 2, 4));
    }

    // ---- 主线推进校验（委托 SuspenseLadderPolicy；此处验的是"章计划 → 观测点"的映射）----

    @Test
    void validateSuspenseAdvance_mapsPlanItemsAndFlagsStagnation() {
        List<String> ladder = List.of("双方不知", "一方起疑", "双方持证", "摊牌");
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithBeat(1, ladder.get(1), null),
                planWithBeat(2, ladder.get(1), null),
                planWithBeat(3, ladder.get(1), null));

        String issue = ChapterPlanChecks.validateSuspenseAdvance(chapters, ladder);

        assertNotNull(issue);
        assertTrue(issue.contains("主线原地"), "6 章原地这类病症要能被这条通道拦下");
    }

    @Test
    void validateSuspenseAdvance_treatsTransitionChapterAsNonBlocking() {
        List<String> ladder = List.of("双方不知", "一方起疑", "双方持证", "摊牌");
        // 停留段以过渡章收尾 → 不算违规（过渡章本就是蓄势）。
        // ⚠️ 三章须各自写出"本章独有"的推进子项：起相邻章 suspenseBeat
        // **逐字相同**会命中独立去重规则（与停留规则无关），这里刻意让文本相异以隔离被测规则。
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithBeat(1, ladder.get(0) + "——角色A首次听到投资提议", null),
                planWithBeat(2, ladder.get(0) + "——角色B翻看凭证起疑", null),
                planWithBeat(3, ladder.get(0) + "——角色C察觉神色不对", ChapterTypeVO.TRANSITION));

        assertNull(ChapterPlanChecks.validateSuspenseAdvance(chapters, ladder));
    }

    /**
     * 相邻章 suspenseBeat 逐字相同必须被点名——实测多章复用同一句时，
     * 旧判据只比档位下标、报一次后便沉默，且重规划反馈没告诉模型"你写的是同一句话"。
     */
    @Test
    void validateSuspenseAdvance_flagsIdenticalAdjacentBeats() {
        List<String> ladder = List.of("双方不知", "一方起疑", "双方持证", "摊牌");
        String same = ladder.get(0) + "（第1-3章）";
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithBeat(1, same, null),
                planWithBeat(2, same, null),
                planWithBeat(3, same, null));

        String issue = ChapterPlanChecks.validateSuspenseAdvance(chapters, ladder);

        assertNotNull(issue, "逐字相同必须报违规");
        assertTrue(issue.contains("逐字相同"), "违规描述须点明原因：" + issue);
    }

    @Test
    void validateSuspenseAdvance_skipsWithoutLadder() {
        List<ChapterPlanItemEntity> chapters = List.of(planWithBeat(1, "随便", null));

        assertNull(ChapterPlanChecks.validateSuspenseAdvance(chapters, null),
                "无蓝图模式/老故事不得因缺档位表而失败");
        assertNull(ChapterPlanChecks.validateSuspenseAdvance(null, List.of("a", "b")));
    }

    /**
     * 章号缺失时**不得空转**：模型输出的章号可能未规范化（甚至为空）。早期实现按章号做范围过滤，
     * 遇到空章号就把整段章节全丢光 → 校验拿到空列表直接通过 → **闸门看着在跑，实际什么都没查**。
     */
    @Test
    void validateSuspenseAdvance_flagsMissingBeatsEvenWithoutChapterNo() {
        List<String> ladder = List.of("双方不知", "一方起疑", "摊牌");
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithBeat(null, null, null),
                planWithBeat(null, null, null),
                planWithBeat(null, null, null));

        String issue = ChapterPlanChecks.validateSuspenseAdvance(chapters, ladder);

        assertNotNull(issue, "章号缺失不该导致闸门静默通过");
        assertTrue(issue.contains("未声明 suspenseBeat"));
        assertTrue(issue.contains("第 1 章"), "定位要退回列表序号，而不是报'第 0 章'");
    }

    private ChapterPlanAggregate planOf(Integer... chapterNos) {
        return ChapterPlanAggregate.builder()
                .chapters(java.util.Arrays.stream(chapterNos).map(this::item).toList())
                .build();
    }

    private ChapterPlanItemEntity item(Integer chapterNo) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(chapterNo)
                .title("标题")
                .goal("目标")
                .keyEvents(List.of("事件"))
                .build();
    }

    private ChapterPlanItemEntity item(int chapterNo, String goal) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(chapterNo)
                .title("标题")
                .goal(goal)
                .keyEvents(List.of("事件"))
                .build();
    }

    private ChapterPlanItemEntity planWithBeat(Integer chapterNo, String beat, ChapterTypeVO type) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(chapterNo)
                .title("标题")
                .goal("目标")
                .keyEvents(List.of("事件"))
                .chapterType(type)
                .suspenseBeat(beat)
                .build();
    }

    // ==================== 章级主线推进校验 ====================

    /** 造一章：同时给出 suspenseBeat 与 mainLineAdvance */
    private ChapterPlanItemEntity planWithAdvance(int chapterNo, String beat, String advance) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(chapterNo)
                .title("标题")
                .goal("目标")
                .keyEvents(List.of("事件"))
                .suspenseBeat(beat)
                .mainLineAdvance(advance)
                .build();
    }

    /** 造蓝图侧的章级推进计划 */
    private StageBlueprintEntity.MainLineBeat planned(int chapterNo, String advance) {
        return new StageBlueprintEntity.MainLineBeat(chapterNo, advance);
    }

    /**
     * 回归：第 11–15 章真实病症——五章回填同一档位（不是模型偷懒，是档位表 3-6 档覆盖整个阶段）。
     * 旧判据只比档位下标，看不出"两章写的是同一件事"；本校验补的就是这个横向维度。
     */
    @Test
    void validateMainLineAdvance_flagsIdenticalAdjacentChapters() {
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithAdvance(11, "档位2", "父亲首次听到投资提议"),
                planWithAdvance(12, "档位2", "父亲首次听到投资提议"));
        List<StageBlueprintEntity.MainLineBeat> planned = List.of(
                planned(11, "父亲首次听到投资提议"),
                planned(12, "父亲翻看凭证起疑"));

        String issue = ChapterPlanChecks.validateMainLineAdvance(chapters, planned);

        assertNotNull(issue, "相邻章主线推进雷同必须报违规");
        assertTrue(issue.contains("逐字相同"), "违规描述须点明原因：" + issue);
    }

    /** 章计划未回填 mainLineAdvance —— 必须点名，并给出蓝图原文以便模型照抄 */
    @Test
    void validateMainLineAdvance_flagsMissingField() {
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithAdvance(11, "档位2", null),
                planWithAdvance(12, "档位2", "父亲翻看凭证起疑"));
        List<StageBlueprintEntity.MainLineBeat> planned = List.of(
                planned(11, "父亲首次听到投资提议"),
                planned(12, "父亲翻看凭证起疑"));

        String issue = ChapterPlanChecks.validateMainLineAdvance(chapters, planned);

        assertNotNull(issue);
        assertTrue(issue.contains("第 11 章") && issue.contains("未回填"), issue);
        assertTrue(issue.contains("父亲首次听到投资提议"), "应把蓝图原文一并给出，模型才知道照抄什么：" + issue);
    }

    /** 自行改写蓝图内容 → 未落地 */
    @Test
    void validateMainLineAdvance_flagsRewrittenContent() {
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithAdvance(11, "档位2", "本章继续推进主线剧情"),
                planWithAdvance(12, "档位2", "父亲翻看凭证起疑"));
        List<StageBlueprintEntity.MainLineBeat> planned = List.of(
                planned(11, "父亲首次听到投资提议"),
                planned(12, "父亲翻看凭证起疑"));

        String issue = ChapterPlanChecks.validateMainLineAdvance(chapters, planned);

        assertNotNull(issue);
        assertTrue(issue.contains("与蓝图不符"), issue);
    }

    /** 容许模型在蓝图原文后补一句解释（前缀命中）——不宜因这种常见写法就驳回 */
    @Test
    void validateMainLineAdvance_toleratesTrailingExplanation() {
        List<ChapterPlanItemEntity> chapters = List.of(
                planWithAdvance(11, "档位2", "父亲首次听到投资提议（陆建军借喝酒游说）"),
                planWithAdvance(12, "档位2", "父亲翻看凭证起疑"));
        List<StageBlueprintEntity.MainLineBeat> planned = List.of(
                planned(11, "父亲首次听到投资提议"),
                planned(12, "父亲翻看凭证起疑"));

        assertNull(ChapterPlanChecks.validateMainLineAdvance(chapters, planned),
                "蓝图原文之后附加说明属常见写法，不应判违规");
    }

    /** 无蓝图（老数据/补采失败）→ 跳过，不得因缺字段而失败 */
    @Test
    void validateMainLineAdvance_skipsWithoutBlueprint() {
        List<ChapterPlanItemEntity> chapters = List.of(planWithAdvance(1, "档位1", null));

        assertNull(ChapterPlanChecks.validateMainLineAdvance(chapters, null));
        assertNull(ChapterPlanChecks.validateMainLineAdvance(chapters, List.of()));
        assertNull(ChapterPlanChecks.validateMainLineAdvance(null, List.of(planned(1, "x"))));
    }

    /** 反馈文案必须点名具体章与具体内容——否则"重规划"只是原样重生成一遍 */
    @Test
    void mainLineFeedback_namesTheOffendingChapter() {
        String feedback = ChapterPlanChecks.mainLineFeedback("第 11 章未回填 mainLineAdvance");

        assertTrue(feedback.contains("第 11 章"), feedback);
        assertTrue(feedback.contains("逐字取自"), "必须说清正确的做法：" + feedback);
    }

    @Test
    void validateTimeAdvance_flagsMissingChapters() {
        // 时序锚：timeAdvance 是年龄/时间推进的唯一合法通道——
        // 某批全缺时，阶段蓝图声明的 stageEnd 跳接落空，进度对齐持续滞后
        List<ChapterPlanItemEntity> chapters = List.of(
                planItem(61, "推进3天，至2050年10月20日"),
                planItem(62, null));

        String issue = ChapterPlanChecks.validateTimeAdvance(chapters);

        assertTrue(issue != null && issue.contains("第62章"), issue);
    }

    @Test
    void validateTimeAdvance_passesWhenAllDeclared() {
        List<ChapterPlanItemEntity> chapters = List.of(
                planItem(61, "推进3天，至2050年10月20日"),
                planItem(62, "推进5天，至2050年10月25日"));

        assertNull(ChapterPlanChecks.validateTimeAdvance(chapters));
    }

    private static ChapterPlanItemEntity planItem(int chapterNo, String timeAdvance) {
        return ChapterPlanItemEntity.builder()
                .chapterNo(chapterNo).title("第" + chapterNo + "章").goal("目标")
                .timeAdvance(timeAdvance)
                .build();
    }

    // ---- 追进度跳接校验（validateTimeAdvancePacing）----

    /** 无预算年（非跳接段/无大纲）→ 不适用，恒通过 */
    @Test
    void pacing_skipsWithoutBudgetYear() {
        List<ChapterPlanItemEntity> chapters = List.of(planItem(76, "推进一周，时间未跨年"));

        assertNull(ChapterPlanChecks.validateTimeAdvancePacing(chapters, null));
    }

    /** 末章 timeAdvance 未提到预算年 → 驳回（跳接段的核心校验） */
    @Test
    void pacing_rejectsWhenLastAdvanceMissesBudgetYear() {
        List<ChapterPlanItemEntity> chapters = List.of(
                planItem(76, "推进一周"),
                planItem(77, "相对上一章推进三天"));

        String issue = ChapterPlanChecks.validateTimeAdvancePacing(chapters, 2050);

        assertNotNull(issue);
        assertTrue(issue.contains("2050"), issue);
        assertTrue(issue.contains("第77章"), issue);
    }

    /** 预算年或次年命中即通过（段预算常写作两年区间） */
    @Test
    void pacing_acceptsBudgetYearOrNext() {
        List<ChapterPlanItemEntity> hitStart = List.of(
                planItem(76, "「多年后」跳至2050年"),
                planItem(77, "推进到2050年秋"));
        assertNull(ChapterPlanChecks.validateTimeAdvancePacing(hitStart, 2050));

        List<ChapterPlanItemEntity> hitNext = List.of(
                planItem(76, "「多年后」跳至2050年"),
                planItem(77, "推进到2051年春"));
        assertNull(ChapterPlanChecks.validateTimeAdvancePacing(hitNext, 2050));
    }

    /** 反馈文案必须给出可执行的修正动作——写明目标年份与跳接写法 */
    @Test
    void pacingFeedback_namesBudgetYearAndAction() {
        String feedback = ChapterPlanChecks.timeAdvancePacingFeedback(2050);

        assertTrue(feedback.contains("2050"), feedback);
        assertTrue(feedback.contains("时间跳跃"), feedback);
        assertTrue(feedback.contains("一句带过"), feedback);
    }
}
