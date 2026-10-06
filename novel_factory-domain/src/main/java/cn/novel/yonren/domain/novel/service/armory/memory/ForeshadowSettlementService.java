package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowSpanPolicy;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 卷末清账服务：阶段末章完成后，对本阶段出口长期未填的伏笔逐条 LLM 裁决——
 * RECOVER（能自然融入后续剧情，结转下一阶段前段限期回收）或 VOID（剧情已走远，弃置出账不再追踪）。
 * 未填伏笔的语义是"剧情走远的闲笔"，裁决默认弃置：想不出自然连接点一律 VOID，严禁为清账强行回收。
 * 清账是增强件：解析失败/条数不齐/异常整体返回 null，调用方保持未填冻结（fail-soft），不阻断生成
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForeshadowSettlementService {

    private static final BeanOutputConverter<SettlementOutput> CONVERTER = new BeanOutputConverter<>(
            SettlementOutput.class,
            JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    private static final String SETTLE_SYSTEM_PROMPT = """
            你是小说流水线的卷末清账审计员，负责对阶段出口仍冻结的未填伏笔（埋下后长期未被剧情触达的旧坑）逐条裁决。
            你是审计不是创作者：只依据给定的章节记忆判定，不脑补剧情外的可能性。
            未填伏笔的语义是"剧情已经走远的闲笔"——裁决默认弃置（VOID）；只有当能明确说出该伏笔与后续剧情的自然连接点时，才判限期回收（RECOVER）。
            存疑一律 VOID。严禁为了清账而强行回收——强行回收会导致章节注水。

            ⚠️ 两种**不是闲笔**的特殊情况，必须区别对待：

            一、**已兑现但漏记账**：若该伏笔其实已在某一章被剧情消化（文中已给出答案、承诺已履行、
            冲突已了结），只是回收清单没记上——仍判 VOID（账要平，它确实不必再追踪），
            但 reason **必须以「【已兑现】」开头**，并写明是在第几章兑现的。
            这类条目是**记账缺口的证据**，系统会据此统计摘要层漏记回收的规模，请勿省略该标记。

            二、**按计划在途**：若条目带有「已过计划回收章」标注，说明它当初被明确排进了兑现计划，
            只是到期未收——它**是欠账，不是闲笔**。这类条目**优先判 RECOVER**，reason 写明如何补收；
            确实无法补收的才判 VOID，且 reason 必须写明"排期落空"以便复盘。""";

    /** 单章剧情记忆在 prompt 中的截断字数（清账只判断剧情走向，不需全文） */
    private static final int STAGE_MEMORY_CHAPTER_CHARS = 300;

    private final LlmGateway llmGateway;

    private final ChapterMemoryService chapterMemoryService;

    /**
     * 阶段出口清账（高层入口）：长期未填伏笔逐条裁决并就地生效——VOID 从伏笔账出账（strip 摘要，
     * 由调用方随检查点落盘），全部裁决登记进结算台账（调用方负责落盘结算文件）。
     * 以 stage.getEndChapter() 为裁决基准章号；批次开头补办与阶段末章在跑清账共用此入口
     *
     * @return 裁决清单；无长期未填伏笔返回空列表；裁决失败返回 null（fail-soft，账本不变）
     */
    public List<ForeshadowSettlementEntity.SettlementDecision> settleStageBreakers(StoryVO.Module module,
                                                                                   StageBlueprintEntity stage,
                                                                                   String chapterGoal,
                                                                                   List<ChapterSummaryEntity> summaries,
                                                                                   List<ForeshadowSettlementEntity> settlementsInOut,
                                                                                   Map<String, String> promptSink) {
        return settleStageBreakers(module, stage, chapterGoal, summaries, settlementsInOut, promptSink, null);
    }

    /**
     * 同上，但把**逾期排期线**并入候选（2026-10-02，P2b 的 A1 修正）。
     *
     * <p><b>为什么必须显式取并集</b>：候选原为 {@code Tier.BREAKER} 过滤，而档位公式是
     * {@code importance×10 + 滞留章数×发酵系数 ≥ 100}——一条**刚埋不久但已到回收期**的排期线
     * （滞留小）**根本到不了 BREAKER 档**，于是它压根进不了清账、P2b 标注的"欠账"通道**永不触发**。
     * 排期线的过期与否是**计划事实**，不该由滞留时长决定。
     *
     * @param schedules 伏笔兑现排期表（可为 null——此时行为与引入本参数前完全一致）
     */
    public List<ForeshadowSettlementEntity.SettlementDecision> settleStageBreakers(StoryVO.Module module,
                                                                                   StageBlueprintEntity stage,
                                                                                   String chapterGoal,
                                                                                   List<ChapterSummaryEntity> summaries,
                                                                                   List<ForeshadowSettlementEntity> settlementsInOut,
                                                                                   Map<String, String> promptSink,
                                                                                   List<ForeshadowScheduleEntity> schedules) {
        int stageEndNo = stage.getEndChapter() == null ? 0 : stage.getEndChapter();
        List<ForeshadowPriorityService.ScoredForeshadow> breakers =
                chapterMemoryService.buildBreakerForeshadows(summaries, stageEndNo);
        Set<String> overdueIntents = overdueIntents(schedules, stageEndNo);
        List<ForeshadowPriorityService.ScoredForeshadow> candidates =
                withOverdueScheduled(breakers, overdueIntents, stageEndNo);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ForeshadowSettlementEntity.SettlementDecision> decisions =
                settle(module, stage, chapterGoal, candidates, summaries, promptSink, overdueIntents);
        if (decisions == null) {
            return null;
        }
        // VOID 出账：登记原文精确剔除，随调用方的检查点/预载对齐落盘
        List<String> voided = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_VOID.equals(d.getDecision()))
                .map(ForeshadowSettlementEntity.SettlementDecision::getContent)
                .filter(StringUtils::isNotBlank)
                .toList();
        chapterMemoryService.stripVoidedForeshadows(summaries, voided);
        settlementsInOut.add(ForeshadowSettlementEntity.builder()
                .stageNo(stage.getStageNo())
                .stageEndChapter(stageEndNo)
                .decisions(decisions)
                .build());
        return decisions;
    }

    /**
     * 逐条裁决长期未填的伏笔；输出与未填清单一一位置对齐。
     *
     * @param promptSink 实际使用的 system/user prompt 回写容器（供复盘），可为 null
     * @return 裁决结果；无条件缺失/解析失败/条数不齐/异常时返回 null（fail-soft，未填保持冻结）
     */
    /** 兼容重载：不标注任何"欠账"条目（调用方没有排期表时用） */
    public List<ForeshadowSettlementEntity.SettlementDecision> settle(StoryVO.Module module,
                                                                      StageBlueprintEntity stage,
                                                                      String chapterGoal,
                                                                      List<ForeshadowPriorityService.ScoredForeshadow> breakerItems,
                                                                      List<ChapterSummaryEntity> summaries,
                                                                      Map<String, String> promptSink) {
        return settle(module, stage, chapterGoal, breakerItems, summaries, promptSink, Set.of());
    }

    /**
     * 逐条裁决长期未填的伏笔；输出与未填清单一一位置对齐。
     *
     * @param overdueIntents 逾期排期线的 intent 归一集合——命中者在 prompt 里标注
     *                       「已过计划回收章——欠账，不是闲笔」，裁决取向与普通闲笔不同
     */
    public List<ForeshadowSettlementEntity.SettlementDecision> settle(StoryVO.Module module,
                                                                      StageBlueprintEntity stage,
                                                                      String chapterGoal,
                                                                      List<ForeshadowPriorityService.ScoredForeshadow> breakerItems,
                                                                      List<ChapterSummaryEntity> summaries,
                                                                      Map<String, String> promptSink,
                                                                      Set<String> overdueIntents) {
        if (module == null || stage == null || breakerItems == null || breakerItems.isEmpty()) {
            return null;
        }
        try {
            if (promptSink != null) {
                promptSink.put("system", SETTLE_SYSTEM_PROMPT);
                promptSink.put("user", buildUserPrompt(stage, chapterGoal, breakerItems, summaries, overdueIntents));
            }
            // maxTokens/temperature 不再硬编码，由 scene-models 的 foreshadow-settlement 场景配置驱动
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .systemPrompt(SETTLE_SYSTEM_PROMPT)
                    .userPrompt(buildUserPrompt(stage, chapterGoal, breakerItems, summaries, overdueIntents))
                    .label("foreshadow-settlement-第" + stage.getStageNo() + "阶段")
                    .scene(ModelScene.FORESHADOW_SETTLEMENT)
                    .build());

            SettlementOutput output = JsonParseFallback.parse(raw, this::readOutput);
            if (output == null || output.getResults() == null || output.getResults().size() != breakerItems.size()) {
                log.warn("卷末清账输出无法解析或条数不齐（期望 {}），未填伏笔保持冻结", breakerItems.size());
                return null;
            }
            return alignAndNormalize(breakerItems, output.getResults());
        } catch (Exception e) {
            log.warn("卷末清账异常，未填伏笔保持冻结（fail-soft），stage: {}", stage.getStageNo(), e);
            return null;
        }
    }

    private String buildUserPrompt(StageBlueprintEntity stage, String chapterGoal,
                                   List<ForeshadowPriorityService.ScoredForeshadow> breakerItems,
                                   List<ChapterSummaryEntity> summaries,
                                   Set<String> overdueIntents) {
        int stageEnd = stage.getEndChapter() == null ? 0 : stage.getEndChapter();
        StringBuilder sb = new StringBuilder();
        sb.append("对以下长期未填的伏笔逐条裁决。results 数组与未填清单一一位置对齐，条数必须一致。");
        sb.append("\n\n【上一阶段】第").append(stage.getStageNo()).append("阶段（第")
                .append(stage.getStartChapter()).append("-").append(stageEnd).append("章）");
        if (StringUtils.isNotBlank(stage.getStageGoal())) {
            sb.append("，阶段目标：").append(stage.getStageGoal());
        }
        if (StringUtils.isNotBlank(chapterGoal)) {
            sb.append("\n\n【全书后续方向（原始章节目标）】").append(chapterGoal);
        }

        sb.append("\n\n【长期未填伏笔清单】");
        for (int i = 0; i < breakerItems.size(); i++) {
            ChapterMemoryService.PendingForeshadow item = breakerItems.get(i).item();
            sb.append("\n").append(i + 1).append(". （埋设第").append(item.chapterNo()).append("章")
                    .append("，滞留").append(Math.max(0, stageEnd - item.chapterNo())).append("章")
                    .append("，重要度").append(item.importance() == null ? "未评" : item.importance()).append("）");
            // 逾期排期线明确标注：它不是"走远的闲笔"，而是**欠账**——裁决取向完全不同
            if (overdueIntents != null
                    && overdueIntents.contains(ForeshadowSpanPolicy.normalize(item.content()))) {
                sb.append("【⚠️ 已过计划回收章——欠账，不是闲笔】");
            }
            sb.append(item.content());
            if (StringUtils.isNotBlank(item.excerpt())) {
                sb.append("（埋设处原文：").append(item.excerpt()).append("）");
            }
        }

        sb.append("\n\n【上一阶段章节记忆】\n");
        for (ChapterSummaryEntity summary : stageSummaries(stage, summaries)) {
            String text = StringUtils.defaultString(summary.getSummary());
            if (text.length() > STAGE_MEMORY_CHAPTER_CHARS) {
                text = text.substring(0, STAGE_MEMORY_CHAPTER_CHARS);
            }
            sb.append("- 第").append(summary.getChapterNo()).append("章《")
                    .append(StringUtils.defaultString(summary.getTitle())).append("》：").append(text).append("\n");
        }

        sb.append("\n裁决要求：")
                .append("\n1. decision 只允许 RECOVER（能说出与后续剧情的自然连接点，结转下一阶段前段限期回收）")
                .append("或 VOID（剧情已走远，弃置不再追踪）；")
                .append("\n2. 存疑一律 VOID；判 RECOVER 时 reason 必须写明自然连接点")
                .append("（后续哪个阶段目标/哪条人物线能自然接住它）；")
                .append("\n3. reason 一句话，必填；")
                .append("\n4. 不要输出 Markdown，不要输出解释。")
                .append("\n\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        return sb.toString();
    }

    /** 逾期排期线的 intent 归一集合（PLANTED 且 payoffChapter 已过阶段末章） */
    private static Set<String> overdueIntents(List<ForeshadowScheduleEntity> schedules, int stageEndNo) {
        if (schedules == null || schedules.isEmpty() || stageEndNo <= 0) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (ForeshadowScheduleEntity schedule : schedules) {
            for (ForeshadowScheduleEntity.ScheduleItem item : ForeshadowScheduleEntity.itemsOf(schedule)) {
                if (item != null
                        && ForeshadowScheduleEntity.STATUS_PLANTED.equals(item.getStatus())
                        && item.getPayoffChapter() != null && item.getPayoffChapter() <= stageEndNo
                        && StringUtils.isNotBlank(item.getIntent())) {
                    set.add(ForeshadowSpanPolicy.normalize(item.getIntent()));
                }
            }
        }
        return set;
    }

    /**
     * 候选并集：BREAKER 档 ∪ 逾期排期线。
     *
     * <p>逾期排期线即使没到 BREAKER 档也必须送审——它的"该收未收"是**计划事实**，
     * 不该由滞留时长决定要不要审。已在 BREAKER 里出现过的（按归一文本比对）不重复加。
     */
    private static List<ForeshadowPriorityService.ScoredForeshadow> withOverdueScheduled(
            List<ForeshadowPriorityService.ScoredForeshadow> breakers, Set<String> overdueIntents,
            int stageEndNo) {
        List<ForeshadowPriorityService.ScoredForeshadow> all = new ArrayList<>(breakers);
        if (overdueIntents.isEmpty()) {
            return all;
        }
        Set<String> covered = breakers.stream()
                .map(b -> ForeshadowSpanPolicy.normalize(b.item().content()))
                .collect(Collectors.toSet());
        for (String intent : overdueIntents) {
            if (covered.contains(intent)) {
                continue;
            }
            all.add(new ForeshadowPriorityService.ScoredForeshadow(
                    new ChapterMemoryService.PendingForeshadow(0, intent, null, null),
                    Integer.MAX_VALUE, ForeshadowPriorityService.Tier.BREAKER));
        }
        return all;
    }

    /** 阶段区间内的剧情摘要，按章号排序 */
    private List<ChapterSummaryEntity> stageSummaries(StageBlueprintEntity stage, List<ChapterSummaryEntity> summaries) {
        if (summaries == null) {
            return List.of();
        }
        return summaries.stream()
                .filter(s -> s.getChapterNo() != null
                        && s.getChapterNo() >= stage.getStartChapter() && s.getChapterNo() <= stage.getEndChapter())
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .toList();
    }

    /**
     * 对齐与归一：按位置映射回账本条目原文（不信任模型回抄的 content）；决策值非法按保守语义归为
     * 弃置（宁弃置不强收），理由缺失补默认值
     */
    private List<ForeshadowSettlementEntity.SettlementDecision> alignAndNormalize(
            List<ForeshadowPriorityService.ScoredForeshadow> breakerItems, List<DecisionOutput> modelResults) {
        List<ForeshadowSettlementEntity.SettlementDecision> decisions = new ArrayList<>();
        for (int i = 0; i < breakerItems.size(); i++) {
            DecisionOutput model = modelResults.get(i);
            String rawDecision = model == null ? "" : StringUtils.trimToEmpty(model.getDecision());
            boolean recover = ForeshadowSettlementEntity.DECISION_RECOVER.equalsIgnoreCase(rawDecision);
            String reason = model == null ? "" : StringUtils.trimToEmpty(model.getReason());
            if (!recover && !ForeshadowSettlementEntity.DECISION_VOID.equalsIgnoreCase(rawDecision)) {
                reason = "非法决策值归为弃置：" + rawDecision;
            }
            if (StringUtils.isBlank(reason)) {
                reason = recover ? "未给出连接点" : "剧情已走远（未给出理由）";
            }
            ChapterMemoryService.PendingForeshadow item = breakerItems.get(i).item();
            decisions.add(ForeshadowSettlementEntity.SettlementDecision.builder()
                    .content(item.content())
                    .chapterNo(item.chapterNo())
                    .decision(recover ? ForeshadowSettlementEntity.DECISION_RECOVER : ForeshadowSettlementEntity.DECISION_VOID)
                    .reason(reason)
                    .build());
        }
        long recoverCount = decisions.stream()
                .filter(d -> ForeshadowSettlementEntity.DECISION_RECOVER.equals(d.getDecision())).count();
        log.info("卷末清账裁决完成：{} 条未填 = 弃置 {} + 限期回收 {}",
                decisions.size(), decisions.size() - recoverCount, recoverCount);
        return decisions;
    }

    private SettlementOutput readOutput(String raw) {
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            // 抛非受检异常交给 JsonParseFallback 按"解析失败"语义降级（JsonRepair 后重解）
            throw new IllegalArgumentException(e);
        }
    }

    /** 裁决输出载体：results 与未填清单一一位置对齐 */
    @Data
    public static class SettlementOutput {
        private List<DecisionOutput> results;
    }

    @Data
    public static class DecisionOutput {
        private Integer index;
        private String decision;
        private String reason;
    }
}
