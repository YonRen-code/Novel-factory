package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关系轨迹回灌：把 RELATION 事实聚合出的"当前关系态"摆到规划那一刻。
 *
 * <p><b>为什么需要</b>（2026-09-16，11 章无人值守实测后）：地点有轨迹回灌后新地点率立刻改善，
 * 而**关系/互动当时零指标零回灌**——同一批实测暴露"配角全是工具人、主角全程单机"。
 * 这是同一条老路：观测不到就不会被修。本策略与 {@link PlaceTrajectoryPolicy} 同构：
 * 从摘要的 RELATION 一致性事实聚合当前态（同 pair 保留最近章），注入规划 prompt。
 *
 * <p>配套的供给约束在规划 prompt 规则 8（每章 ≥1 个非主角主动发起的事件、≥1 次双向互动、
 * 代价多样化）——回灌解决"读者上次看到的关系是什么"，规则解决"这一章必须有人的戏"。
 */
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

    /**
     * 渲染关系轨迹块（注入规划层）；无任何 RELATION 事实时返回 null（冷启动不注入）。
     * 块内含**硬约束**：改写关系态必须由正文事件支撑，禁止凭空跳变。
     *
     * <p>去重（2026-09-28）：此前同一批关系态被渲染两遍——全量列表（首见顺序）之后又列
     * 「最近关系演变」top6，数据完全相同、仅排序不同（约 150~200 字纯重复）。
     * 现改为**按最近更新降序单次渲染**：最近演变天然排在最前，"优先承接"由顺序表达而非再列一遍
     */
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
