package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.FinaleAuditEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.service.armory.quality.EvidenceMatch;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终局审查服务：收官门禁触发时，对照四类全书级资产独立审计——不信任末阶段自证。
 * 四维：
 *   FINALE_COMMITMENTS 终局承诺（remainingFinaleBeats/completedFinaleBeats + exitConditions/results）
 *   FORESHADOW         未回收伏笔（开放伏笔逐条：已回收/已弃置/仍悬空）
 *   CHARACTER_FATE     主要角色命运（characterStates 是否各有明确结局）
 *   WORLD_STATE        灾后世界状态（factionStates/itemStates 是否收束）
 * 达成必须给出可机械校验的证据（章号 + 该章摘要内一段连续原文引用），经 {@link EvidenceMatch} 归一化校验，
 * 编造/漂移按未达成处理（宁严勿松）。任一维不通过 → overallPass=false 并产出 reworkTasks（返工缺口）。
 * fail-soft：任何失败或四维不齐返回 null，调用方按 legacy 行为放行，绝不锁死合法完结故事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinaleReviewService {

    public static final String DIM_COMMITMENTS = "FINALE_COMMITMENTS";
    public static final String DIM_FORESHADOW = "FORESHADOW";
    public static final String DIM_CHARACTER_FATE = "CHARACTER_FATE";
    public static final String DIM_WORLD_STATE = "WORLD_STATE";
    private static final List<String> EXPECTED_DIMENSIONS = List.of(
            DIM_COMMITMENTS, DIM_FORESHADOW, DIM_CHARACTER_FATE, DIM_WORLD_STATE);

    private static final BeanOutputConverter<FinaleAuditOutput> CONVERTER = new BeanOutputConverter<>(
            FinaleAuditOutput.class,
            JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    private static final String REVIEW_SYSTEM_PROMPT = """
            你是小说流水线的终局审查员，负责在故事收官前对照全书级资产生成完结质量审计结论。
            你是外部审计，不是创作者：只依据给定的章节记忆判定，不脑补剧情外的可能性。
            判定宁严勿松：存疑一律视为不通过。""";

    /** 证据原文引用上限字数（与 prompt 约束一致，超长截断保住子串有效性） */
    private static final int REVIEW_EVIDENCE_MAX_LENGTH = 80;

    private final LlmGateway llmGateway;

    /**
     * 收官终局审查；四维逐一核验并产出通过的证据 / 不通过的缺口（reworkTasks）。
     * @param finalStage 收官阶段蓝图（仅当满足完结门禁时调用，exitResults 已就绪）
     * @param openForeshadows 收官区间仍未填的伏笔（buildBreakerForeshadows 结果）
     * @param summaries 全书章节摘要
     * @param promptSink 实际使用的 system/user prompt 回写容器（供复盘），可为 null
     * @return 审计实体；非收官/区间无摘要/解析失败/四维不齐/异常时返回 null（fail-soft）
     */
    public FinaleAuditEntity review(StoryVO.Module module,
                                    StageBlueprintEntity finalStage,
                                    List<ForeshadowPriorityService.ScoredForeshadow> openForeshadows,
                                    List<ChapterSummaryEntity> summaries,
                                    Map<String, String> promptSink) {
        if (finalStage == null) {
            return null;
        }
        List<ChapterSummaryEntity> stageSummaries = stageSummaries(finalStage, summaries);
        if (stageSummaries.isEmpty()) {
            log.warn("终局审查跳过：收官阶段区间内无章节摘要，stage: {}", finalStage.getStageNo());
            return null;
        }

        // 可核验文本 = 剧情摘要 + 三账本状态串（模型引用与机械校验使用同一文本源）
        Map<Integer, String> textByChapter = new HashMap<>();
        for (ChapterSummaryEntity summary : stageSummaries) {
            textByChapter.put(summary.getChapterNo(), chapterMemoryText(summary));
        }

        try {
            if (promptSink != null) {
                promptSink.put("system", REVIEW_SYSTEM_PROMPT);
                promptSink.put("user", buildUserPrompt(finalStage, openForeshadows, stageSummaries));
            }
            String raw = llmGateway.complete(module, LlmCall.builder()
                    .systemPrompt(REVIEW_SYSTEM_PROMPT)
                    .userPrompt(buildUserPrompt(finalStage, openForeshadows, stageSummaries))
                    .label("finale-audit")
                    .scene(ModelScene.FINALE_REVIEW)
                    .build());

            FinaleAuditOutput output = JsonParseFallback.parse(raw, this::readOutput);
            if (output == null || output.getDimensions() == null
                    || !containsAllDimensions(output.getDimensions())) {
                log.warn("终局审查输出无法解析或四维不齐，回退 legacy 完结判定");
                return null;
            }
            return alignAndValidate(finalStage, output, textByChapter);
        } catch (Exception e) {
            log.warn("终局审查异常，回退 legacy 完结判定（fail-soft），stage: {}", finalStage.getStageNo(), e);
            return null;
        }
    }

    /** 收官阶段（finalVolume）区间内的摘要，按章号排序 */
    private List<ChapterSummaryEntity> stageSummaries(StageBlueprintEntity stage, List<ChapterSummaryEntity> summaries) {
        if (summaries == null) {
            return List.of();
        }
        int start = stage.getStartChapter() == null ? Integer.MIN_VALUE : stage.getStartChapter();
        int end = stage.getEndChapter() == null ? Integer.MAX_VALUE : stage.getEndChapter();
        return summaries.stream()
                .filter(s -> s.getChapterNo() != null && s.getChapterNo() >= start && s.getChapterNo() <= end)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .toList();
    }

    /**
     * 单章可核验文本：剧情摘要 + 三账本状态串 + **可核验细节**。
     * 实现已收编到 {@link ChapterMemoryText}（此前与 {@code StageExitReviewService} 各持一份逐字相同的副本）。
     */
    private String chapterMemoryText(ChapterSummaryEntity summary) {
        return ChapterMemoryText.of(summary);
    }

    private void appendStates(StringBuilder sb, List<ChapterSummaryEntity.StateEntry> states) {
        if (states == null) {
            return;
        }
        for (ChapterSummaryEntity.StateEntry state : states) {
            if (state != null && StringUtils.isNotBlank(state.getName())) {
                sb.append("（").append(state.getName()).append("：").append(StringUtils.defaultString(state.getStatus())).append("）");
            }
        }
    }

    private String buildUserPrompt(StageBlueprintEntity stage,
                                   List<ForeshadowPriorityService.ScoredForeshadow> openForeshadows,
                                   List<ChapterSummaryEntity> stageSummaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是收官阶段（第 ").append(stage.getStartChapter()).append("-").append(stage.getEndChapter())
                .append(" 章，阶段目标：").append(StringUtils.defaultString(stage.getStageGoal())).append("）。")
                .append("\n请对照给定章节记忆，对下列 4 个维度逐项给出通过与否，维度标识固定为：")
                .append("\n  FINALE_COMMITMENTS 终局承诺 / FORESHADOW 未回收伏笔 / CHARACTER_FATE 主要角色命运 / WORLD_STATE 灾后世界状态")
                .append("\n规则：")
                .append("\n1. 4 个维度必须全部输出，dimension 用上述固定标识，顺序不限；")
                .append("\n2. met=true 必须给出证据：chapterNo 为该事实发生/确立的章节号，")
                .append("evidence 为该章记忆中支持该结论的连续原文引用（≤80 字，逐字摘录，严禁改写拼接）；")
                .append("\n3. 证据必须能在对应章节的记忆文本中找到，找不到视为不通过；")
                .append("\n4. 判定存疑一律 met=false，宁严勿松；")
                .append("\n5. 不通过时在 reworkTasks 中列出具体缺口处置项（可直接作为后续返工续写的任务）；")
                .append("\n6. 全局 overall 字段：四维全部通过才为 true；")
                .append("\n7. 不要输出 Markdown，不要输出解释。")
                .append("\n\n【终局承诺】")
                .append("\n- 已兑现终局节点 completedFinaleBeats：").append(list(stage.getCompletedFinaleBeats()))
                .append("；未兑现 remainingFinaleBeats：").append(list(stage.getRemainingFinaleBeats()))
                .append("\n- 阶段性要求 exitConditions：").append(list(stage.getExitConditions()));
        if (stage.getExitResults() != null) {
            sb.append("\n- 已达成的退出条件核验结果 exitResults：");
            for (StageBlueprintEntity.ExitConditionResult r : stage.getExitResults()) {
                sb.append("\n    · ").append(r == null ? "" : StringUtils.defaultString(r.getCondition()))
                        .append(r != null && Boolean.TRUE.equals(r.getMet()) ? "（达成）" : "（未达成:" + (r == null ? "" : StringUtils.defaultString(r.getNote())) + "）");
            }
        }
        sb.append("\n\n【未回收伏笔】");
        if (openForeshadows == null || openForeshadows.isEmpty()) {
            sb.append("\n（无长期未填伏笔）");
        } else {
            for (ForeshadowPriorityService.ScoredForeshadow s : openForeshadows) {
                if (s != null && s.item() != null) {
                    sb.append("\n- 第").append(s.item().chapterNo()).append("章原文「")
                            .append(StringUtils.defaultString(s.item().content()))
                            .append("」").append(StringUtils.isBlank(s.item().excerpt()) ? "" : "（" + s.item().excerpt() + "）");
                }
            }
        }
        sb.append("\n\n【章节记忆（第 ").append(stage.getStartChapter()).append("-")
                .append(stage.getEndChapter()).append(" 章）】\n");
        for (ChapterSummaryEntity summary : stageSummaries) {
            sb.append("- 第").append(summary.getChapterNo()).append("章《").append(StringUtils.defaultString(summary.getTitle()))
                    .append("》：").append(chapterMemoryText(summary)).append("\n");
        }
        sb.append("\n请严格按照以下 JSON 格式输出：\n").append(CONVERTER.getFormat());
        return sb.toString();
    }

    private String list(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "（无）";
        }
        return String.join(" / ", values);
    }

    private boolean containsAllDimensions(List<FinaleAuditEntity.DimensionResult> dimensions) {
        long distinct = dimensions.stream()
                .filter(d -> d != null && d.getDimension() != null && EXPECTED_DIMENSIONS.contains(d.getDimension()))
                .map(FinaleAuditEntity.DimensionResult::getDimension)
                .distinct().count();
        return distinct == EXPECTED_DIMENSIONS.size();
    }

    /**
     * 按 dimension 标识对齐 + 机械校验证据（章号越界/该章缺失/引用非原文一律判不通过）；
     * 四维全通过 → overallPass=true；否则按不通过维 + 未处置伏笔拼装 reworkTasks。
     */
    private FinaleAuditEntity alignAndValidate(StageBlueprintEntity stage,
                                               FinaleAuditOutput output,
                                               Map<Integer, String> textByChapter) {
        // 校验用文本预归一化一次（引号一律统一为半角双引号 + 去空白），与 StageExitReviewService 同口径；
        // ⚠️ 只用于校验——喂给模型的 prompt 仍由 buildUserPrompt 从 stageSummaries 构建原文。
        Map<Integer, String> normalizedByChapter = new HashMap<>();
        for (Map.Entry<Integer, String> entry : textByChapter.entrySet()) {
            normalizedByChapter.put(entry.getKey(), EvidenceMatch.normalize(entry.getValue()));
        }
        Map<String, FinaleAuditEntity.DimensionResult> byDim = new LinkedHashMap<>();
        for (FinaleAuditEntity.DimensionResult model : output.getDimensions()) {
            byDim.put(model.getDimension(), validateDimension(model, textByChapter, normalizedByChapter));
        }

        boolean allPass = true;
        List<String> reworkTasks = new ArrayList<>();
        for (String dim : EXPECTED_DIMENSIONS) {
            FinaleAuditEntity.DimensionResult r = byDim.get(dim);
            if (r == null || !Boolean.TRUE.equals(r.getMet())) {
                allPass = false;
                reworkTasks.add(reworkTask(dim, r));
            }
        }

        List<String> openForeshadows = output.getOpenForeshadows() == null
                ? List.of() : new ArrayList<>(output.getOpenForeshadows());
        if (!allPass) {
            Boolean fsMet = byDim.get(DIM_FORESHADOW) == null ? null : byDim.get(DIM_FORESHADOW).getMet();
            if (!Boolean.TRUE.equals(fsMet)) {
                for (String fs : openForeshadows) {
                    reworkTasks.add("回收伏笔：" + fs);
                }
            }
        }

        FinaleAuditEntity audit = FinaleAuditEntity.builder()
                .overallPass(allPass)
                .forcedClose(false)
                .dimensions(new ArrayList<>(byDim.values()))
                .openForeshadows(openForeshadows)
                .reworkTasks(reworkTasks)
                .note(buildNote(allPass, byDim))
                .build();
        long met = byDim.values().stream().filter(r -> Boolean.TRUE.equals(r.getMet())).count();
        log.info("终局审查完成：收官阶段第 {} 章，四维通过 {}/4，overallPass={}",
                stage.getEndChapter(), met, allPass);
        return audit;
    }

    private FinaleAuditEntity.DimensionResult validateDimension(FinaleAuditEntity.DimensionResult model,
                                                                Map<Integer, String> textByChapter,
                                                                Map<Integer, String> normalizedByChapter) {
        FinaleAuditEntity.DimensionResult result = FinaleAuditEntity.DimensionResult.builder()
                .dimension(model.getDimension()).build();
        boolean met = Boolean.TRUE.equals(model.getMet());
        Integer chapterNo = model.getChapterNo();
        String evidence = model.getEvidence();
        if (met) {
            if (chapterNo == null || !textByChapter.containsKey(chapterNo)) {
                met = false;
                result.setNote("证据校验失败：章节号越界或该章摘要缺失");
            } else {
                // 截断到 prompt 约束的字数上限，保住"前缀仍是原文子串"的有效性
                evidence = EvidenceMatch.truncate(StringUtils.trimToEmpty(evidence),
                        REVIEW_EVIDENCE_MAX_LENGTH);
                // 统一走 EvidenceMatch（2026-09-18 收编，此前是裸 indexOf）：
                // 归一化后整串包含，或按省略号分段后各段全命中——只放宽表述/标点差异，不放宽事实有无。
                if (evidence.isEmpty()
                        || !EvidenceMatch.contained(evidence, normalizedByChapter.get(chapterNo))) {
                    met = false;
                    evidence = null;
                    result.setNote("证据校验失败：引用非该章记忆原文");
                }
            }
        } else {
            result.setNote(StringUtils.defaultIfBlank(model.getNote(), "该维度未通过"));
        }
        result.setMet(met);
        result.setChapterNo(chapterNo);
        result.setEvidence(met ? evidence : null);
        return result;
    }

    private String reworkTask(String dimension, FinaleAuditEntity.DimensionResult r) {
        String gap = r == null ? "" : StringUtils.defaultString(r.getNote());
        return switch (dimension) {
            case DIM_COMMITMENTS -> "兑现终局承诺缺口：" + gap;
            case DIM_FORESHADOW -> "收束剩余伏笔：" + gap;
            case DIM_CHARACTER_FATE -> "交代主要角色命运：" + gap;
            case DIM_WORLD_STATE -> "收束灾后世界状态：" + gap;
            default -> "收官缺口：" + gap;
        };
    }

    private String buildNote(boolean allPass, Map<String, FinaleAuditEntity.DimensionResult> byDim) {
        if (allPass) {
            return "四维终局审查全部通过";
        }
        StringBuilder sb = new StringBuilder("终局审查未通过：");
        for (Map.Entry<String, FinaleAuditEntity.DimensionResult> e : byDim.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue().getMet())) {
                sb.append(e.getKey()).append("=").append(StringUtils.defaultString(e.getValue().getNote(), "未通过")).append("；");
            }
        }
        return sb.toString();
    }

    private FinaleAuditOutput readOutput(String raw) {
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** 终局审查输出载体 */
    @Data
    public static class FinaleAuditOutput {
        private Boolean overall;
        private List<FinaleAuditEntity.DimensionResult> dimensions;
        private List<String> openForeshadows;
        private List<String> reworkTasks;
        private String note;
    }
}