package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.entity.VolumeBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.llm.LlmInvokeService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ForeshadowSettlementService;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import cn.novel.yonren.domain.novel.service.armory.memory.RollingOutlineService;
import cn.novel.yonren.domain.novel.service.armory.memory.StageExitReviewService;
import cn.novel.yonren.domain.novel.service.armory.plan.StoryPacing;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.quality.SuspenseLadderPolicy;
import cn.novel.yonren.types.enums.PromptScene;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段蓝图节点：滚动大纲的方向盘。
 * 批次开头判断本批是否跨过阶段边界（批次末章超出当前蓝图覆盖区间），
 * 是则逐阶段链式生成新版蓝图（每跨一个边界生成一版，直至覆盖批次末章），
 * 按"原始大纲 + 上一版蓝图 + 近章摘要 + 待回收伏笔"四件套生成并链式结转。
 * 蓝图是增强件——生成/解析失败按 fail-soft 处理，剩余章段以无蓝图（或旧蓝图）模式继续，不阻断计划生成。
 * 生成结果写入 DynamicContext，随 PersistChapterPlanNode 统一落盘 rolling-outline.json
 */
@Service
@Slf4j
public class BuildStageBlueprintNode extends AbstractArmorySupport {

    @Resource
    private BuildChapterPlanPromptNode buildChapterPlanPromptNode;

    @Resource
    private RollingOutlineService rollingOutlineService;

    @Resource
    private LlmInvokeService llmInvokeService;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    @Resource
    private StageExitReviewService stageExitReviewService;

    @Resource
    private ForeshadowSettlementService foreshadowSettlementService;

    @Resource
    private IStoryRepository storyRepository;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return buildChapterPlanPromptNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 BuildStageBlueprintNode - 阶段蓝图（滚动大纲）");
        prepareBlueprint(requestParameter, dynamicContext);
        return router(requestParameter, dynamicContext);
    }

    /**
     * 触发判断 + 逐阶段链式生成 + 写入上下文（包级可见供单测绕过树路由）。
     * 判断基准是本批末章：批次跨越多阶段边界时循环生成，直至蓝图链覆盖批次末章
     */
    void prepareBlueprint(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        List<StageBlueprintEntity> blueprints = dynamicContext.getStageBlueprints() != null
                ? dynamicContext.getStageBlueprints() : new ArrayList<>();
        int chapterOffset = dynamicContext.getChapterOffset();
        int nextChapterNo = chapterOffset + 1;
        int batchEnd = chapterOffset + requestParameter.getStoryContextEntity().getChapterCount();

        if (!rollingOutlineService.needsGeneration(blueprints, batchEnd)) {
            StageBlueprintEntity previous = rollingOutlineService.latestOf(blueprints);
            dynamicContext.setStageBlueprint(previous);
            log.info("阶段蓝图复用：第{}阶段（第{}-{}章），下一章 {}",
                    previous.getStageNo(), previous.getStartChapter(), previous.getEndChapter(), nextChapterNo);
            return;
        }

        // 批次跨边界：上一版终点之后逐阶段生成，直至覆盖批次末章
        while (rollingOutlineService.needsGeneration(blueprints, batchEnd)) {
            StageBlueprintEntity previous = rollingOutlineService.latestOf(blueprints);
            int windowStart = ( previous == null ? nextChapterNo : previous.getEndChapter() + 1 );
            // 失败自动重试一次：蓝图决定本批任务/退出条件/排期，
            // 解析失败静默降级的代价是整批在旧蓝图上滑行（排期原地踏步、新阶段任务与出口条件缺位），
            // 一次重试的成本（60s）远低于整批规划缺位——与段推进校验的"重规划一次"同款宽容度。
            StageBlueprintEntity blueprint = null;
            for (int attempt = 1; attempt <= 2 && blueprint == null; attempt++) {
                if (attempt > 1) {
                    log.warn("阶段蓝图生成/解析失败，自动重试一次（本批任务/退出条件/排期都挂在蓝图上，静默降级代价过高）");
                }
                blueprint = generateForWindow(requestParameter, dynamicContext, previous, windowStart, batchEnd);
            }
            if (blueprint == null) {
                // 蓝图是增强件：重试仍失败则停止链式生成，剩余章段回退上一版（或无蓝图），不阻断计划生成；
                // 规划缺位由批末体检的【阶段规划覆盖】条目显式点名（BatchHealthService#addStagePlanningCoverage），
                // 不再只留一条易被淹没的 WARN
                log.warn("阶段蓝图生成/解析失败（含重试），剩余章段以{}继续（fail-soft，不阻断计划生成）",
                        previous == null ? "无蓝图模式" : "上一版蓝图（第" + previous.getStageNo() + "阶段）");
                break;
            }
            if (blueprint.getEndChapter() == null || blueprint.getEndChapter() < windowStart) {
                // 防御：终点未能推进窗口起点时终止链式生成，避免同窗口无限重复
                log.warn("阶段蓝图终点 {} 未越过窗口起点 {}，终止链式生成", blueprint.getEndChapter(), windowStart);
                break;
            }
            blueprints.add(blueprint);
        }

        dynamicContext.setStageBlueprints(blueprints.isEmpty() ? null : blueprints);
        dynamicContext.setStageBlueprint(findCovering(blueprints, nextChapterNo));
    }

    /**
     * 为单个阶段窗口生成蓝图：组装四件套 prompt、调用模型、解析规整。
     * batchEnd 为本次批次末章（chapterOffset + chapterCount），是阶段终点的钳制上界——
     * 钳制目标与蓝图覆盖目标（needsGeneration 的判断基准）保持一致，保证链式生成必然终止
     */
    private StageBlueprintEntity generateForWindow(ArmoryCommandEntity requestParameter,
                                                   DefaultArmoryFactory.DynamicContext dynamicContext,
                                                   StageBlueprintEntity previous,
                                                   int windowStart,
                                                   int batchEnd) {
        // 上一阶段退出条件核验（外部审计，先于蓝图生成——核验结果进入生成 prompt 的结转指令）；
        // 核验失败整体回退模型自评结转（fail-soft）
        reviewPreviousExitConditions(requestParameter, dynamicContext, previous);

        // 上一阶段出口清账补办：阶段已完结但尚无结算记录（旧版本跨过边界/崩溃跳过）时补一次裁决，
        // VOID 出账 + RECOVER 注入本批规划 prompt（批次开头执行，先于蓝图生成 prompt 装配）
        settleMissedBreakerForeshadows(requestParameter, dynamicContext, previous);

        RollingOutlineService.StageWindow window = rollingOutlineService.nextWindow(previous, windowStart);
        // hardTotal（全书硬性完结上限）随 prompt 注入：预算前置收敛 + 到顶强制收官
        Integer hardTotal = dynamicContext.getMaxChapterCount();
        // 导演通道：作者创作要点置于 prompt 最顶部（不可裁剪）
        String prompt = cn.novel.yonren.domain.novel.service.armory.CreativeNotes
                .block(dynamicContext.getCreativeNotes())
                + rollingOutlineService.buildGenerationPrompt(
                requestParameter.getStoryContextEntity(), previous,
                dynamicContext.getChapterSummaries(),
                chapterMemoryService.buildBlueprintForeshadowList(dynamicContext.getChapterSummaries()),
                window, batchEnd, hardTotal,
                // 当前卷作为弧的全局方向锚（无卷退化为 null → 两段式，不注入卷方向锚）
                dynamicContext.getCurrentVolume());

        StoryContextEntity storyContext = requestParameter.getStoryContextEntity();
        PromptContext promptContext = PromptContext.builder()
                .theme(storyContext.getTheme())
                .style(storyContext.getStyle())
                .totalChapters(storyContext.getChapterCount())
                .build();

        Map<String, String> blueprintPrompts = new HashMap<>();
        String raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                promptContext, prompt, blueprintPrompts);
        recordBlueprintPrompts(dynamicContext, blueprintPrompts);

        StageBlueprintEntity blueprint = rollingOutlineService.parse(raw, previous, windowStart, batchEnd, hardTotal);
        if (blueprint == null) {
            return null;
        }

        // 卷归属机械化回填：不信任模型重算卷号/卷标题（模型只可自报卷内 arcNo/arcGoal）
        VolumeBlueprintEntity currentVolume = dynamicContext.getCurrentVolume();
        if (currentVolume != null) {
            blueprint.setVolumeNo(currentVolume.getVolumeNo());
            blueprint.setVolumeTitle(StringUtils.trimToNull(currentVolume.getTitle()));
        }

        // 未达成的退出条件机械化结转：不信任模型自评，结转清单缺失即补
        mergeUnmetExitConditions(previous, blueprint);
        // 结转债务上限：跨段滚动的未完成任务超过硬上限的部分降级为背景清偿
        capCarriedTasks(blueprint);

        // 悬念档位表补采：档位表是"主线推进闸门"的唯一标尺，但在完整蓝图 prompt 里
        // 它只是第 11 条要求——实测模型会在长 prompt 里**静默省略**这两个字段。缺了它，锚块不注入、
        // 校验直接跳过、指标不出：整条推进链路会一声不响地空转。
        // ⇒ 不把可靠性押在一次服从上：缺了就**只问这两个字段**再补一次（短 prompt 服从率高），
        //    仍拿不到才告警——绝不静默放过。
        if (!SuspenseLadderPolicy.usable(blueprint.getSuspenseLadder())) {
            repairSuspenseLadder(requestParameter, dynamicContext, storyContext, promptContext, blueprint);
        }

        // 章级主线推进补采：与档位表同理——逐章数组放在长 prompt 里被省略的概率更高，
        // 缺了就**只问这一项**再补一次。档位表在补采之后才确定，所以本补采放在它之后，
        // 好把已定档位表一并喂进去（章级推进必须与档位相容，否则两张表会互相打架）。
        repairMainLine(requestParameter, dynamicContext, storyContext, promptContext, blueprint);

        // 伏笔兑现排期表补采：同为"缺了就只问这一项"的聚焦补采。
        // 放在最后——它要看阶段区间与 hardTotal，与档位/章级推进无依赖但同属"蓝图缺失字段"家族。
        repairForeshadowSchedule(requestParameter, dynamicContext, storyContext, promptContext, blueprint);

        log.info("阶段蓝图生成：第{}阶段（第{}-{}章），任务 {} 条，结转 {} 条",
                blueprint.getStageNo(), blueprint.getStartChapter(), blueprint.getEndChapter(),
                blueprint.getTasks() == null ? 0 : blueprint.getTasks().size(),
                blueprint.getCarriedTasks() == null ? 0 : blueprint.getCarriedTasks().size());
        // stageEnd 机械校验+回填：12.1 要求模型从【进度对齐·大纲路标】
        // 声明 stageEndYear/Age，但实测会胡写（写出早于故事开局的年份）——
        // 不信任声明，以大纲段预算为准校验回填（零成本、确定性，与卷区间的机械钳制同款思路）
        repairStageEndFromOutline(requestParameter, storyContext, blueprint, previous);

        // 追进度三件套：跳接生成为任务队列第一条、结转任务重定基、
        // 蓝图挂 pacing 要求供段计划 fail-closed 校验——三者缺一，时间跳跃就只活在散文里
        applyPacing(requestParameter, storyContext, dynamicContext, blueprint);
        return blueprint;
    }

    /**
     * 追进度三件套：进度滞后此前只活在散文与体检报告里，规划层沿惯性行驶——整批都在
     * 清偿结转债、把大纲声明的时间跳跃完全无视。三条机械措施：
     * ①把跳接生成为任务队列第一条（任务才是规划的执行单元，散文指令排不进队列）；
     * ②entryConstraints 写死结转重定基：跳接后旧债按新时间线清偿，不得拉回跳跃前；
     * ③蓝图挂 pacing 要求（transient），段计划校验据此 fail-closed（见 ChapterPlanSegmentPlanner）
     */
    private void applyPacing(ArmoryCommandEntity requestParameter, StoryContextEntity storyContext,
                             DefaultArmoryFactory.DynamicContext dynamicContext,
                             StageBlueprintEntity blueprint) {
        if (blueprint == null || storyContext == null || StringUtils.isBlank(storyContext.getChapterGoal())) {
            return;
        }
        Integer anchorYear = StoryPacing.latestAnchorYear(dynamicContext.getChapterSummaries());
        Integer budgetYear = StoryPacing.budgetStartYear(storyContext.getChapterGoal(), blueprint.getStartChapter());
        if (anchorYear == null || budgetYear == null || budgetYear <= anchorYear) {
            return;
        }
        int lag = budgetYear - anchorYear;
        blueprint.setPacingAnchorYear(anchorYear);
        blueprint.setPacingBudgetStartYear(budgetYear);
        blueprint.setPacingLagYears(lag);
        String jumpTask = "【追进度·最高优先级】第 " + blueprint.getStartChapter()
                + " 章开篇执行时间跳跃：「" + lag + " 年后」，故事时间从 " + anchorYear
                + " 年直接进入 " + budgetYear + " 年段；本段所有场景发生在跳跃后的时间线里";
        if (blueprint.getTasks() == null) {
            blueprint.setTasks(new ArrayList<>());
        }
        if (blueprint.getTasks().stream().noneMatch(t -> t != null && t.contains("【追进度"))) {
            blueprint.getTasks().add(0, jumpTask);
        }
        if (blueprint.getEntryConstraints() == null) {
            blueprint.setEntryConstraints(new ArrayList<>());
        }
        String rebase = "【时间重定基】本段包含时间跳跃：所有结转任务一律在跳跃后的时间线（" + budgetYear
                + " 年段）下执行与重述，任务中的「当下」指跳跃后的时间；清偿可以是新场景或一句带过，"
                + "不得把场景拉回跳跃前";
        if (blueprint.getEntryConstraints().stream().noneMatch(c -> c != null && c.contains("【时间重定基】"))) {
            blueprint.getEntryConstraints().add(rebase);
        }
        log.info("追进度注入：锚年 {} → 预算年 {}（滞后 {} 年），跳年任务与重定基约束已写入第 {} 段蓝图",
                anchorYear, budgetYear, lag, blueprint.getStageNo());
    }

    /** 结转债务硬上限：跨段滚动的未完成任务超过硬上限的部分降级为背景清偿，不再逼规划层专门造场景 */
    private static final int CARRIED_HARD_CAP = 8;

    private void capCarriedTasks(StageBlueprintEntity blueprint) {
        List<StageBlueprintEntity.CarriedTaskEntity> carried = blueprint.getCarriedTasks();
        if (carried == null || carried.size() <= CARRIED_HARD_CAP) {
            return;
        }
        int downgraded = 0;
        for (int i = CARRIED_HARD_CAP; i < carried.size(); i++) {
            StageBlueprintEntity.CarriedTaskEntity t = carried.get(i);
            if (t == null || t.getContent() == null || t.getContent().startsWith("【背景清偿")) {
                continue;
            }
            t.setContent("【背景清偿·一句带过】" + t.getContent());
            downgraded++;
        }
        if (downgraded > 0) {
            log.info("结转债务上限：{} 条超出硬上限 {}，降级为背景清偿（正文一句带过即可）",
                    downgraded, CARRIED_HARD_CAP);
        }
    }

    /**
     * stageEnd 机械校验+回填：12.1 要求模型从【进度对齐·大纲路标】声明
     * stageEndYear/stageEndAge，但实测会胡写（写出早于故事开局的年份）。
     * 校验规则：stageEndYear 必须含 4 位年份且 ≥ 大纲该段预算年份、≥ 上一阶段声明（单调递增）；
     * stageEndAge 必须含数字且 ≥ 大纲段预算年龄（若大纲可算）。任一不合格即按大纲段预算回填——
     * 大纲段无数值标记（境界纪年等）时保持声明原样，不编造。
     */
    private void repairStageEndFromOutline(ArmoryCommandEntity requestParameter,
                                           StoryContextEntity storyContext,
                                           StageBlueprintEntity blueprint,
                                           StageBlueprintEntity previous) {
        if (blueprint == null || storyContext == null
                || StringUtils.isBlank(storyContext.getChapterGoal())) {
            return;
        }
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(storyContext.getChapterGoal());
        if (outline.isEmpty()) {
            return;
        }
        int stageEndNo = blueprint.getEndChapter() == null ? 0 : blueprint.getEndChapter();
        OutlineSegmentParser.OutlineSegment seg = outline.segmentFor(stageEndNo);
        if (seg == null && !outline.segments().isEmpty()) {
            seg = outline.segments().get(outline.segments().size() - 1);
        }
        if (seg == null || (seg.yearStart() == null && seg.ageStart() == null)) {
            return; // 大纲段无数值时间标记（境界纪年等）——不编造，保持模型声明
        }
        Integer declaredYear = extractYear(blueprint.getStageEndYear());
        int floorYear = seg.yearStart() == null ? 0 : seg.yearStart();
        Integer prevYear = previous == null || StringUtils.isBlank(previous.getStageEndYear())
                ? null : extractYear(previous.getStageEndYear());
        if (prevYear != null && prevYear > floorYear) {
            floorYear = prevYear; // 相邻阶段单调递增下限
        }
        if (declaredYear == null || declaredYear < floorYear) {
            String backfill = (seg.yearEnd() != null ? seg.yearEnd() : seg.yearStart()) + "年";
            log.info("阶段蓝图 stageEndYear 不合格（声明 {}，下限 {}），已按大纲段预算回填为 {}",
                    declaredYear, floorYear, backfill);
            blueprint.setStageEndYear(backfill);
        }
        if (seg.ageStart() != null) {
            Integer declaredAge = extractAge(blueprint.getStageEndAge());
            int floorAge = seg.ageStart() == null ? 0 : seg.ageStart();
            if (declaredAge == null || declaredAge < floorAge) {
                String backfill = (seg.ageEnd() != null ? seg.ageEnd() : seg.ageStart()) + "岁";
                log.info("阶段蓝图 stageEndAge 不合格（声明 {}，下限 {}），已按大纲段预算回填为 {}",
                        declaredAge, floorAge, backfill);
                blueprint.setStageEndAge(backfill);
            }
        }
    }

    private static Integer extractYear(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})\\s*年").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static Integer extractAge(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})\\s*岁").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * **伏笔兑现排期表补采**（2026-10-02，P2b）：主 prompt 遗漏时补问一次，只问这一项。
     *
     * <p>三步：①聚焦补采拿到本阶段新排期 → ②与上一版结转的活线条目**机械合并去重**
     * （模型看不见上一版全貌，必然重复排）→ ③追加进 {@code dynamicContext.foreshadowSchedules} 并落盘。
     *
     * <p><b>质量校验（"至少 1 条跨阶段"）在这里判定</b>，不在 parse 里——
     * 它是补采质量问题而非格式问题：格式不合格整表退回，质量问题**重问一次**，
     * 仍不满足则**告警放行**（排期质量差不该让整批失败）。
     *
     * <p>fail-soft：拿不到排期表时不阻断规划——此时全链路行为与引入 P2b 前完全一致
     * （种子的 {@code scheduledPayoffChapter} 保持 null，清账过滤与漏收指标都不触发）。
     */
    private void repairForeshadowSchedule(ArmoryCommandEntity requestParameter,
                                          DefaultArmoryFactory.DynamicContext dynamicContext,
                                          StoryContextEntity storyContext, PromptContext promptContext,
                                          StageBlueprintEntity blueprint) {
        if (blueprint == null || blueprint.getStartChapter() == null || blueprint.getEndChapter() == null) {
            return;
        }
        int hardTotal = dynamicContext.getMaxChapterCount() == null
                ? blueprint.getEndChapter() : dynamicContext.getMaxChapterCount();
        List<ForeshadowScheduleEntity> schedules = dynamicContext.getForeshadowSchedules() != null
                ? dynamicContext.getForeshadowSchedules() : new ArrayList<>();
        // 幂等守卫：同一阶段已经有排期表就跳过（重跑同一阶段/检查点恢复后重入时不该重复补采）。
        // ⚠️ 与档位表/章级推进不同，排期表**不是** StageBlueprintEntity 的字段，主蓝图 prompt 产不出它，
        // 因此"补采"就是它的**常规产出路径**（每生成一版蓝图一次调用），而非常规路径的兜底。
        if (schedules.stream().anyMatch(x -> x != null
                && java.util.Objects.equals(x.getStageNo(), blueprint.getStageNo()))) {
            log.info("伏笔兑现排期表：第{}阶段已有排期，跳过补采（幂等）", blueprint.getStageNo());
            return;
        }
        List<ForeshadowScheduleEntity.ScheduleItem> carried =
                ForeshadowScheduleEntity.carriableItems(ForeshadowScheduleEntity.latestOf(schedules));

        List<ForeshadowScheduleEntity.ScheduleItem> fresh =
                askSchedule(requestParameter, dynamicContext, storyContext, promptContext, blueprint, hardTotal, carried);
        if (fresh == null) {
            log.warn("伏笔兑现排期补采未取得可用结果（第{}阶段）——本阶段不产生新排期，"
                    + "已有结转 {} 条继续生效；属已知降级而非静默", blueprint.getStageNo(), carried.size());
            return;
        }
        // ⚠️ 质量校验只在"还有后续章节"时进行：hardTotal <= 阶段末章 ⇒ 全书在本阶段收束，
        // 跨阶段要求**永远无法满足**，照查会让调用方空转重问一件不可能的事。
        boolean crossStagePossible = hardTotal > blueprint.getEndChapter();
        if (crossStagePossible && fresh.stream().noneMatch(i -> i.getPayoffChapter() != null
                && i.getPayoffChapter() > blueprint.getEndChapter())) {
            // 质量校验：全在本阶段内收 ⇒ 又回到"埋下去立刻兑现"，正是 P2 要治的病。重问一次。
            log.warn("伏笔排期补采质量不合格（第{}阶段）：没有任何一条跨阶段（payoffChapter 全部 ≤ {}），重问一次",
                    blueprint.getStageNo(), blueprint.getEndChapter());
            fresh = askSchedule(requestParameter, dynamicContext, storyContext, promptContext, blueprint, hardTotal, carried);
            if (fresh == null || fresh.stream().noneMatch(i -> i.getPayoffChapter() != null
                    && i.getPayoffChapter() > blueprint.getEndChapter())) {
                log.warn("伏笔排期补采二次仍无跨阶段条目（第{}阶段）——放行本次排期，"
                        + "该情形会让「本段内埋本段内收」继续存在，可经体检的伏笔跨度指标观测",
                        blueprint.getStageNo());
                if (fresh == null) {
                    return;
                }
            }
        }
        List<ForeshadowScheduleEntity.ScheduleItem> merged =
                rollingOutlineService.mergeScheduleItems(carried, fresh);
        schedules.add(ForeshadowScheduleEntity.builder()
                .stageNo(blueprint.getStageNo())
                .startChapter(blueprint.getStartChapter())
                .endChapter(blueprint.getEndChapter())
                .items(merged)
                .build());
        dynamicContext.setForeshadowSchedules(schedules);
        Path storyDir = dynamicContext.getStoryDir();
        if (storyDir != null) {
            try {
                storyRepository.writeForeshadowSchedule(storyDir, schedules);
            } catch (Exception e) {
                log.error("伏笔排期表落盘失败，本轮仅内存生效", e);
            }
        }
        long crossStage = merged.stream().filter(i -> i.getPayoffChapter() != null
                && i.getPayoffChapter() > blueprint.getEndChapter()).count();
        log.info("伏笔兑现排期表（第{}阶段）：新增 {} 条 + 结转 {} 条 = {} 条，其中跨阶段 {} 条",
                blueprint.getStageNo(), fresh.size(), carried.size(), merged.size(), crossStage);
    }

    /** 单次排期补采调用（含解析）——供"质量不合格重问一次"复用 */
    private List<ForeshadowScheduleEntity.ScheduleItem> askSchedule(
            ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext,
            StoryContextEntity storyContext, PromptContext promptContext, StageBlueprintEntity blueprint,
            int hardTotal, List<ForeshadowScheduleEntity.ScheduleItem> carried) {
        String repairPrompt = rollingOutlineService.buildForeshadowScheduleRepairPrompt(
                storyContext, blueprint, hardTotal, carried, dynamicContext.getChapterSummaries());
        Map<String, String> repairPrompts = new HashMap<>();
        String raw;
        try {
            raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                    promptContext, repairPrompt, repairPrompts);
        } catch (Exception e) {
            log.warn("伏笔排期补采调用失败：{}", e.getMessage());
            return null;
        }
        recordBlueprintPrompts(dynamicContext, repairPrompts);
        RollingOutlineService.ForeshadowSchedulePatch patch =
                rollingOutlineService.parseForeshadowSchedulePatch(raw, blueprint, hardTotal);
        return patch == null ? null : patch.foreshadowSchedule();
    }

    /**
     * **章级主线推进补采**（2026-10-02）：主 prompt 遗漏 {@code mainLineByChapter} 时补问一次，且只问这一项。
     *
     * <p>与 {@link #repairSuspenseLadder} 同一套理由与形态：长 prompt 里的逐章数组会被静默省略，
     * 短聚焦 prompt 服从率高得多。**缺了两者的后果不同但同样静默**——
     * 缺档位表 ⇒ 纵向校验空转；缺章级推进 ⇒ 横向校验空转、段计划继续多章复用同一句描述。
     *
     * <p>补采仍拿不到时**必须告警**：否则"跑了但没生效"与"没跑"事后无法区分。
     * <b>不阻断规划</b>——本字段缺失只降级"章级推进校验"，不该让整批失败（fail-soft）。
     */
    private void repairMainLine(ArmoryCommandEntity requestParameter,
                                DefaultArmoryFactory.DynamicContext dynamicContext,
                                StoryContextEntity storyContext, PromptContext promptContext,
                                StageBlueprintEntity blueprint) {
        if (blueprint == null || blueprint.getStartChapter() == null) {
            return;
        }
        int end = RollingOutlineService.mainLineWindowEnd(blueprint);
        int expected = Math.max(1, end - blueprint.getStartChapter() + 1);
        // 触发口径取**宽松**（有内容就不补问），与档位表的 usable(ladder) 同款：
        // 补采只负责"把缺的字段问出来"，**覆盖是否完整交给下游校验**
        // （validateMainLineAdvance 会逐章报"未声明"，不会静默放过）。
        // 若这里改判"必须恰好覆盖排期窗"，同一份夹具在不同窗口的用例会反复触发补采，
        // 而补采本身是额外一次 LLM 调用——用严格口径换来的收益不抵成本。
        if (blueprint.getMainLineByChapter() != null && !blueprint.getMainLineByChapter().isEmpty()) {
            return;
        }
        log.warn("阶段蓝图未给出章级主线推进（第{}阶段，应覆盖第{}-{}章共 {} 条），触发聚焦补采",
                blueprint.getStageNo(), blueprint.getStartChapter(), end, expected);
        String repairPrompt = rollingOutlineService.buildMainLineRepairPrompt(storyContext, blueprint,
                dynamicContext.getChapterSummaries());
        Map<String, String> repairPrompts = new HashMap<>();
        String raw;
        try {
            raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                    promptContext, repairPrompt, repairPrompts);
        } catch (Exception e) {
            log.warn("章级主线推进补采调用失败，第{}阶段的章级推进校验将跳过：{}",
                    blueprint.getStageNo(), e.getMessage());
            return;
        }
        recordBlueprintPrompts(dynamicContext, repairPrompts);
        RollingOutlineService.MainLinePatch patch =
                rollingOutlineService.parseMainLinePatch(raw, blueprint);
        if (patch == null || patch.mainLineByChapter() == null || patch.mainLineByChapter().isEmpty()) {
            log.warn("章级主线推进补采仍未取得可用结果（第{}阶段）——本阶段的「章级主线推进」注入与校验"
                    + "将跳过，段计划的多章雷同问题暂不受约束，属已知降级而非静默", blueprint.getStageNo());
            return;
        }
        blueprint.setMainLineByChapter(patch.mainLineByChapter());
        log.info("章级主线推进补采成功（第{}阶段）：第 {}-{} 章共 {} 条",
                blueprint.getStageNo(), blueprint.getStartChapter(), end,
                patch.mainLineByChapter().size());
    }

    /**
     * 上一阶段退出条件核验（外部审计）：仅在上一版蓝图声明了退出条件且尚未核验时执行一次，
     * 结果写回蓝图的 exitResults（随 rolling-outline.json 落盘），供生成 prompt 结转指令使用。
     * 核验失败返回 null → 不写结果，链式生成回退模型自评结转（fail-soft）
     */
    private void reviewPreviousExitConditions(ArmoryCommandEntity requestParameter,
                                              DefaultArmoryFactory.DynamicContext dynamicContext,
                                              StageBlueprintEntity previous) {
        if (previous == null || previous.getExitConditions() == null
                || previous.getExitConditions().isEmpty() || previous.getExitResults() != null) {
            return;
        }
        Map<String, String> reviewPrompts = new HashMap<>();
        // storyVO/module 理论上必有（触发层装配），防御性兜底：缺模型配置时核验跳过走 fail-soft
        StoryVO.Module module = requestParameter.getStoryVO() == null
                ? null : requestParameter.getStoryVO().getModule();
        List<StageBlueprintEntity.ExitConditionResult> results = stageExitReviewService.review(
                module, previous, dynamicContext.getChapterSummaries(), reviewPrompts);
        if (results != null) {
            previous.setExitResults(results);
            log.info("上一阶段退出条件核验完成：第{}阶段，条件 {} 条", previous.getStageNo(), results.size());
        } else {
            log.warn("上一阶段退出条件核验失败，回退模型自评结转（fail-soft）");
        }
        if (!reviewPrompts.isEmpty()) {
            recordReviewPrompts(dynamicContext, reviewPrompts);
        }
    }

    /**
     * 上一阶段出口清账补办：上一阶段已完结（末章号 ≤ 续写偏移）但结算台账无对应记录时，
     * 补一次未填伏笔裁决——VOID 出账（剥离随 DynamicContext 流转，落盘结算文件）、
     * RECOVER 由规划 prompt 按段起点注入限期回收。批次开头已清账/无遗漏/裁决失败均静默跳过（fail-soft）
     */
    private void settleMissedBreakerForeshadows(ArmoryCommandEntity requestParameter,
                                                DefaultArmoryFactory.DynamicContext dynamicContext,
                                                StageBlueprintEntity previous) {
        if (previous == null || previous.getEndChapter() == null
                || previous.getEndChapter() > dynamicContext.getChapterOffset()) {
            return;
        }
        List<ForeshadowSettlementEntity> settlements = dynamicContext.getForeshadowSettlements() != null
                ? dynamicContext.getForeshadowSettlements() : new ArrayList<>();
        boolean alreadySettled = settlements.stream().anyMatch(s -> s != null
                && previous.getEndChapter().equals(s.getStageEndChapter()));
        if (alreadySettled) {
            return;
        }
        Map<String, String> settlementPrompts = new HashMap<>();
        // storyVO/module 理论上必有（触发层装配），防御性兜底：缺模型配置时补办跳过走 fail-soft
        StoryVO.Module module = requestParameter.getStoryVO() == null
                ? null : requestParameter.getStoryVO().getModule();
        List<ForeshadowSettlementEntity.SettlementDecision> decisions = foreshadowSettlementService.settleStageBreakers(
                module, previous, requestParameter.getStoryContextEntity().getChapterGoal(),
                dynamicContext.getChapterSummaries(), settlements, settlementPrompts,
                // 逾期排期线必须并入清账候选（否则"欠账"通道永不触发，见 settleStageBreakers 注释）
                dynamicContext.getForeshadowSchedules());
        if (!settlementPrompts.isEmpty()) {
            // 结算 prompt 以 settlement: 前缀并入 usedPromptMap（与 exit-review:/blueprint: 前缀并存互不覆盖）
            Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                    ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
            settlementPrompts.forEach((key, value) -> used.put("settlement:" + key, value));
            dynamicContext.setUsedPromptMap(used);
        }
        if (decisions == null) {
            log.warn("阶段出口清账补办失败，未填伏笔保持冻结（fail-soft），stage: {}", previous.getStageNo());
            return;
        }
        if (decisions.isEmpty()) {
            log.info("阶段出口清账补办：第{}阶段出口无长期未填伏笔，跳过", previous.getStageNo());
            return;
        }
        dynamicContext.setForeshadowSettlements(settlements);
        Path storyDir = dynamicContext.getStoryDir();
        if (storyDir != null) {
            try {
                storyRepository.writeForeshadowSettlements(storyDir, settlements);
            } catch (Exception e) {
                log.error("阶段出口清账补办结算落盘失败，本轮仅内存生效", e);
            }
        }
        long recoverCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_RECOVER.equals(d.getDecision())).count();
        log.info("阶段出口清账补办完成：第{}阶段，未填 {} 条 = 弃置 {} + 限期回收 {}（回收项将注入本批规划）",
                previous.getStageNo(), decisions.size(), decisions.size() - recoverCount, recoverCount);
    }

    /**
     * 未达成退出条件的机械化结转 + <b>退场规则</b>。
     *
     * <p>原实现只把未达成条件写进 carriedTasks（任务层），而下一阶段核验的是
     * <em>本阶段新生成的 exitConditions</em>（核验层）⇒ 旧条件<strong>永不被重验、永不退场</strong>：
     * 实测它只能一路结转（超出上限的条目被静默丢弃，账目就此失真），
     * 同时模型每阶段重新表述该条件并越写越长（单句 → 多个分句），单调不可达。
     *
     * <p>新规则（每个未达成条件最多重验一次后必然退场，累积量有上界）：
     * <ol>
     *   <li>未达成且<em>非本阶段继承</em> → 把条件<strong>原样注入</strong>本阶段 exitConditions
     *       （在 parse 之后机械追加，模型无从改写措辞），并记 carriedTask「进行中」；
     *       同时记入 {@code inheritedExitConditions} 供下一轮判断"是否已重验过一次"</li>
     *   <li>未达成且<em>本阶段继承而来</em> → 已重验一次仍不达成 ⇒ <strong>强制出账</strong>：
     *       不再注入，记 carriedTask「放弃」并注明机械出账原因，交人工复盘裁定</li>
     * </ol>
     */
    private void mergeUnmetExitConditions(StageBlueprintEntity previous, StageBlueprintEntity blueprint) {
        if (previous == null || previous.getExitResults() == null || blueprint == null) {
            return;
        }
        List<String> inheritedBefore = previous.getInheritedExitConditions() != null
                ? previous.getInheritedExitConditions() : List.of();
        List<StageBlueprintEntity.CarriedTaskEntity> carried = blueprint.getCarriedTasks() != null
                ? new ArrayList<>(blueprint.getCarriedTasks()) : new ArrayList<>();
        List<String> conditions = blueprint.getExitConditions() != null
                ? new ArrayList<>(blueprint.getExitConditions()) : new ArrayList<>();
        List<String> inherited = new ArrayList<>();
        boolean changed = false;

        for (StageBlueprintEntity.ExitConditionResult result : previous.getExitResults()) {
            if (Boolean.TRUE.equals(result.getMet()) || StringUtils.isBlank(result.getCondition())) {
                continue;
            }
            String condition = result.getCondition();
            if (inheritedBefore.stream().anyMatch(c -> StringUtils.equals(c, condition))) {
                // 已重验一次仍不达成 → 强制出账，不再结转
                if (!containsCondition(carried, condition)) {
                    carried.add(new StageBlueprintEntity.CarriedTaskEntity(
                            "【未达成退出条件·出账】" + condition, "放弃",
                            "连续两阶段核验未达成，机械出账（不再结转，避免单调累积）；缺口："
                                    + StringUtils.defaultString(result.getNote(), "未达成")));
                    changed = true;
                }
                continue;
            }
            if (conditions.stream().noneMatch(c -> StringUtils.equals(c, condition))) {
                conditions.add(condition);
                changed = true;
            }
            inherited.add(condition);
            if (!containsCondition(carried, condition)) {
                carried.add(new StageBlueprintEntity.CarriedTaskEntity(
                        "【未达成退出条件】" + condition, "进行中",
                        "机械结转并已注入本阶段退出条件（阶段末重验一次）："
                                + StringUtils.defaultString(result.getNote(), "退出条件未达成")));
                changed = true;
            }
        }
        if (changed) {
            blueprint.setExitConditions(conditions);
            blueprint.setCarriedTasks(carried);
            blueprint.setInheritedExitConditions(inherited);
        }
    }

    /** 结转清单里是否已登记该条件（原文双向包含去重） */
    private static boolean containsCondition(List<StageBlueprintEntity.CarriedTaskEntity> carried, String condition) {
        return carried.stream().anyMatch(task -> task != null && task.getContent() != null
                && (task.getContent().contains(condition) || condition.contains(task.getContent())));
    }

    /**
     * 核验 prompt 并入 usedPromptMap 供复盘（exit-review: 前缀，合并式写入不覆盖 blueprint: 记录）
     */
    private void recordReviewPrompts(DefaultArmoryFactory.DynamicContext dynamicContext, Map<String, String> reviewPrompts) {
        Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
        reviewPrompts.forEach((key, value) -> used.put("exit-review:" + key, value));
        dynamicContext.setUsedPromptMap(used);
    }

    /**
     * 找覆盖指定章的蓝图（链上无覆盖时回退最新一版）；供上下文默认值与测试使用
     */
    private StageBlueprintEntity findCovering(List<StageBlueprintEntity> blueprints, int chapterNo) {
        List<RollingOutlineService.StageSegment> segments = rollingOutlineService.segmentBatch(blueprints, chapterNo, chapterNo);
        return segments.isEmpty() ? null : segments.get(0).blueprint();
    }

    /**
     * 蓝图 prompt 以 blueprint: 前缀并入 usedPromptMap（计划节点复用该 map，不再覆盖），
     * 供生成记录复盘
     */
    /**
     * 蓝图 prompt 并入 usedPromptMap 供复盘（blueprint: 前缀，合并式写入——
     * 链式生成多版/退出条件核验的 prompt 记录互相保留，不互相覆盖）
     */
    /**
     * 悬念档位表**聚焦补采**：主 prompt 遗漏这两项时再问一次，且**只问这两个字段**。
     *
     * <p>设计依据来自实测：同一条要求放在完整蓝图 prompt（十几个要求 + 长 schema）里会被静默省略，
     * 而单独用短 prompt 问则 100% 给出且质量合格（5 档、可观察表述）。
     * 所以可靠性靠"缺了就补问"，不靠"要求写得够醒目"——后者对长 prompt 无效。
     *
     * <p>补采也拿不到时**必须告警**：此时推进闸门与 suspenseHold 指标都会静默失效，
     * 而日志若不留痕，事后无从判断"到底跑了没跑"。
     */
    private void repairSuspenseLadder(ArmoryCommandEntity requestParameter,
                                      DefaultArmoryFactory.DynamicContext dynamicContext,
                                      StoryContextEntity storyContext, PromptContext promptContext,
                                      StageBlueprintEntity blueprint) {
        log.warn("阶段蓝图未给出悬念档位表（第{}阶段），触发聚焦补采", blueprint.getStageNo());
        String repairPrompt = rollingOutlineService.buildSuspenseLadderRepairPrompt(storyContext, blueprint,
                dynamicContext.getChapterSummaries());
        Map<String, String> repairPrompts = new HashMap<>();
        String raw;
        try {
            raw = llmInvokeService.invoke(requestParameter.getStoryVO(), PromptScene.STAGE_BLUEPRINT,
                    promptContext, repairPrompt, repairPrompts);
        } catch (Exception e) {
            log.warn("悬念档位补采调用失败，第{}阶段的主线推进闸门将跳过：{}",
                    blueprint.getStageNo(), e.getMessage());
            return;
        }
        recordBlueprintPrompts(dynamicContext, repairPrompts);
        RollingOutlineService.SuspenseLadderPatch patch = rollingOutlineService.parseSuspenseLadderPatch(raw);
        if (patch == null || !SuspenseLadderPolicy.usable(patch.suspenseLadder())) {
            log.warn("悬念档位补采仍未取得可用档位表（第{}阶段）——本阶段的「主线推进」闸门与 suspenseHold "
                    + "指标将跳过，属已知降级而非静默", blueprint.getStageNo());
            return;
        }
        if (StringUtils.isBlank(blueprint.getCoreSuspense())) {
            blueprint.setCoreSuspense(patch.coreSuspense());
        }
        blueprint.setSuspenseLadder(patch.suspenseLadder());
        log.info("悬念档位补采成功（第{}阶段）：{} 档", blueprint.getStageNo(), patch.suspenseLadder().size());
    }

    private void recordBlueprintPrompts(DefaultArmoryFactory.DynamicContext dynamicContext, Map<String, String> blueprintPrompts) {
        Map<String, String> used = dynamicContext.getUsedPromptMap() != null
                ? new HashMap<>(dynamicContext.getUsedPromptMap()) : new HashMap<>();
        blueprintPrompts.forEach((key, value) -> used.put("blueprint:" + key, value));
        dynamicContext.setUsedPromptMap(used);
    }

}
