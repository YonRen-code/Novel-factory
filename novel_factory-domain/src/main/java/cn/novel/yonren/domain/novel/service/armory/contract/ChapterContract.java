package cn.novel.yonren.domain.novel.service.armory.contract;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 章节契约：正文 prompt 的**最小必需语义**——本章谁想要什么、什么阻碍他、结束时什么改变了。
 *
 * <p><b>为什么要有契约对象</b>：正文 prompt 此前由 {@code ChapterWorker.buildChapterPrompt}
 * 把"设定 + 记忆 + 计划 + 十条禁令"平级拼在一起，模型无法判断什么最重要。拆成四层 render 只是
 * <em>组织方式</em>的整理；契约把其中的**任务层**从"一段文本"提升为**可判定的对象**——
 * 有了它才能回答"这章的计划是否足以支撑写作"，进而选择生成模式或走兜底。
 *
 * <p><b>字段来源与边界</b>：
 * <ul>
 *   <li>{@code dramaticTask / mustHappen / endingImage} 来自章节计划（已有数据）；</li>
 *   <li>{@code povCharacter / knowledgeBoundary / mustNotReveal} 需要**计划层扩展**才能产出，
 *       当前一律为 null —— <b>刻意不在这里机械编造</b>（视角与认知边界靠猜比没有更糟）。
 *       契约的 {@link #isComplete()} <b>不依赖</b>这三个字段，避免"永远不完整"导致模式判定失效。</li>
 * </ul>
 *
 * @param chapterTitle      本章标题
 * @param dramaticTask      本章唯一核心戏剧任务（计划 goal）
 * @param mustHappen        本章必须落地的事件（计划 keyEvents）
 * @param endingImage       结尾画面/悬念（计划 endingHook）——也是"完成即停"的落点
 * @param povCharacter      视角人物（计划层未产出时为 null）
 * @param knowledgeBoundary 认知边界：谁知道什么、谁还不知道什么（计划层未产出时为 null）
 * @param mustNotReveal     本章禁泄信息（计划层未产出时为 null）
 * @param chapterType       章型（决定建议骨架）
 */
public record ChapterContract(
        String chapterTitle,
        String dramaticTask,
        List<String> characters,
        List<String> mustHappen,
        String endingImage,
        String povCharacter,
        String knowledgeBoundary,
        List<String> mustNotReveal,
        ChapterTypeVO chapterType) {

    /** 认知边界回溯章数：取最近若干章的角色账本（越靠后的状态越新） */
    private static final int KNOWLEDGE_LOOKBACK_CHAPTERS = 3;

    /** 认知类状态的特征词：命中即视为"谁知不知道什么" */
    private static final List<String> KNOWLEDGE_MARKERS = List.of(
            "知道", "不知", "怀疑", "确认", "以为", "猜测", "认为", "身份", "察觉", "发现", "误以为");

    /**
     * 从章节计划 + 最近章账本装配契约（零 LLM 成本）。
     *
     * <p>{@code knowledgeBoundary} 由 {@link #extractKnowledgeBoundary} 从**角色账本**里机械提取
     * ——账本本就在记录「尚未确认无月的真实身份」这类条目，只是此前从未被用进正文 prompt。
     * 视角人物与禁泄清单仍无数据来源，保持 null。将来若在计划 prompt 里扩展字段，只需在这里接上。
     */
    public static ChapterContract of(ChapterPlanItemEntity item, List<ChapterSummaryEntity> summaries) {
        if (item == null) {
            return null;
        }
        return new ChapterContract(
                item.getTitle(),
                item.getGoal(),
                item.getCharacters(),
                item.getKeyEvents(),
                item.getEndingHook(),
                null,
                extractKnowledgeBoundary(summaries, item.getChapterNo()),
                null,
                item.getChapterType());
    }

    /**
     * 从最近若干章的**角色账本**里筛出"认知类"状态，按角色取**最后一次**（越靠后越新）。
     *
     * <p>只保留章号**早于本章**的记录——本章自身的认知变化尚未发生，不能拿来当边界。
     *
     * @return 形如「软软——尚未确认无月的真实身份；江燃——不知道软软是许知意」；
     *         无认知类记录时返回 null（正文 prompt 会跳过该块）
     */
    private static String extractKnowledgeBoundary(List<ChapterSummaryEntity> summaries,
                                                   Integer beforeChapterNo) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        // ⚠️ 顺序很关键：**先按章号过滤，再取最近 N 条**。
        // 若先切片后过滤，当 summaries 里含本章之后（或未来批次）的条目时，
        // 最后 N 条会被整段滤掉，边界静默变空——而不是报错。
        List<ChapterSummaryEntity> history = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            if (summary != null && summary.getChapterNo() != null
                    && (beforeChapterNo == null || summary.getChapterNo() < beforeChapterNo)) {
                history.add(summary);
            }
        }
        if (history.isEmpty()) {
            return null;
        }
        int from = Math.max(0, history.size() - KNOWLEDGE_LOOKBACK_CHAPTERS);
        Map<String, String> latest = new LinkedHashMap<>();
        for (int i = from; i < history.size(); i++) {
            ChapterSummaryEntity summary = history.get(i);
            if (summary.getCharacterStates() == null) {
                continue;
            }
            for (ChapterSummaryEntity.StateEntry state : summary.getCharacterStates()) {
                if (state == null || StringUtils.isBlank(state.getName())) {
                    continue;
                }
                String status = StringUtils.defaultString(state.getStatus());
                if (isKnowledgeState(status)) {
                    // 后写的覆盖先写的 —— 同一角色保留最新认知状态
                    latest.put(state.getName(), status);
                }
            }
        }
        if (latest.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        latest.forEach((name, status) -> {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(name).append("——").append(status);
        });
        return sb.toString();
    }

    private static boolean isKnowledgeState(String status) {
        for (String marker : KNOWLEDGE_MARKERS) {
            if (status.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 契约是否完整到可以直接写作。
     *
     * <p>判据只取**真正影响可写性**的三项：核心任务、必须事件、结尾落点。
     * ⚠️ 注意 {@code endingHook} **不在** {@code ValidateChapterPlanNode} 的校验项里
     * （那里只查 title / goal / keyEvents），所以"缺 endingHook"是**真实可发生的**，
     * 也正是 {@link GenerationMode#RECOVERY} 的触发场景之一。
     */
    public boolean isComplete() {
        return StringUtils.isNotBlank(dramaticTask)
                && mustHappen != null && !mustHappen.isEmpty()
                && StringUtils.isNotBlank(endingImage);
    }

    /** 缺失项（供兜底补全与观测归因） */
    public List<String> missingParts() {
        List<String> missing = new ArrayList<>();
        if (StringUtils.isBlank(dramaticTask)) {
            missing.add("核心任务(goal)");
        }
        if (mustHappen == null || mustHappen.isEmpty()) {
            missing.add("必须事件(keyEvents)");
        }
        if (StringUtils.isBlank(endingImage)) {
            missing.add("结尾落点(endingHook)");
        }
        return missing;
    }

    /**
     * 本章建议骨架——按**章型（节奏角色）**给出因果链提示。
     *
     * <p>⚠️ 与评论意见里那份"内容类型骨架"（ACTION / DIALOGUE / REVEAL / INVESTIGATION）是
     * **正交维度**：这里是"这一章在节奏上承担什么角色"，那里是"这一章主要写什么内容"。
     * 内容类型当前**没有任何数据来源**（计划层不产出），所以不在此凭空新增枚举；
     * 真要加，应当新增一个正交字段而不是把这些值塞进 {@link ChapterTypeVO}。
     *
     * @return 骨架提示文本；未知章型返回通用骨架
     */
    public String suggestedSkeleton() {
        ChapterTypeVO type = chapterType == null ? ChapterTypeVO.NORMAL : chapterType;
        return switch (type) {
            case TRANSITION -> "日常动作 → 关系或状态变化 → 下一步决定";
            case CLIMAX -> "压力升级 → 做出选择 → 付出代价 → 暂时结果";
            case FINALE -> "收束既定线索 → 交代人物去向 → 落在收尾画面上";
            case NORMAL -> "目标 → 阻碍 → 转折 → 结果";
        };
    }
}
