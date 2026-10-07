package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RelationTrajectoryPolicy {

    /** 关系台账当前态最多渲染条数：超出按最近更新排序截断（防长跑后块体积随关系数线性膨胀） */
    public static final int MAX_RENDERED = 20;

    private RelationTrajectoryPolicy() {
    }

    /** 单条聚合态：pair → 关系（最近一次） */
    public record RelationState(String pair, String relation, int firstChapter, int lastChapter) {
    }

    /**
     * 从摘要聚合关系当前态：同 pair 保留**最近**一次的 value 与章号，首见章号单独记。
     * 与 {@code ConsistencyIndexService.upsertRelation} 同一口径（台账回答"现在什么关系"）
     */
    public static List<RelationState> currentStates(List<ChapterSummaryEntity> summaries) {
        Map<String, RelationState> byPair = new LinkedHashMap<>();
        if (summaries == null) {
            return List.of();
        }
        for (ChapterSummaryEntity s : summaries) {
            if (s == null || s.getChapterNo() == null) {
                continue;
            }
            for (ChapterSummaryEntity.ConsistencyFact fact : safe(s.getConsistencyFacts())) {
                if (fact == null || !"RELATION".equalsIgnoreCase(StringUtils.trimToEmpty(fact.getType()))
                        || StringUtils.isBlank(fact.getSubject())) {
                    continue;
                }
                int chapterNo = s.getChapterNo();
                RelationState existing = byPair.get(fact.getSubject());
                if (existing == null) {
                    byPair.put(fact.getSubject(), new RelationState(
                            fact.getSubject(), StringUtils.defaultString(fact.getValue()), chapterNo, chapterNo));
                } else if (chapterNo >= existing.lastChapter) {
                    byPair.put(fact.getSubject(), new RelationState(
                            fact.getSubject(), StringUtils.defaultString(fact.getValue()),
                            existing.firstChapter, chapterNo));
                }
            }
        }
        return new ArrayList<>(byPair.values());
    }

    public static String renderTrajectory(List<ChapterSummaryEntity> summaries) {
        List<RelationState> states = currentStates(summaries);
        if (states.isEmpty()) {
            return null;
        }
        List<RelationState> ordered = states.stream()
                .sorted(Comparator.comparingInt(RelationState::lastChapter).reversed()
                        .thenComparingInt(RelationState::firstChapter))
                .toList();

        StringBuilder sb = new StringBuilder("\n\n【关系台账·当前态】以下为已确立的人物关系（按最近更新排序，")
                .append("靠前者尚未沉淀，规划本章时优先考虑）：新章中的互动必须与之连贯——")
                .append("**改写关系态必须由本章正文事件支撑**，禁止凭空跳变：\n");
        int shown = 0;
        for (RelationState state : ordered) {
            if (shown++ >= MAX_RENDERED) {
                break;
            }
            sb.append("- ").append(state.pair()).append("：").append(state.relation());
            if (state.firstChapter() != state.lastChapter()) {
                sb.append("（首见第").append(state.firstChapter()).append("章，最近第")
                        .append(state.lastChapter()).append("章）");
            } else {
                sb.append("（第").append(state.firstChapter()).append("章确立）");
            }
            sb.append('\n');
        }
        if (ordered.size() > MAX_RENDERED) {
            sb.append("- 另有 ").append(ordered.size() - MAX_RENDERED)
                    .append(" 条更早确立的关系已封存，复现时依前情摘要与一致性索引唤醒\n");
        }
        return sb.toString();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
