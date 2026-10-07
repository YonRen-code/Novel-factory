package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowScheduleEntity;
import cn.novel.yonren.domain.novel.service.armory.quality.ForeshadowSpanPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ForeshadowScheduleService {


    public static final int PLANT_MATCH_WINDOW = 2;


    private static final int MATCH_MIN_CHARS = 4;

    public StampResult stamp(ChapterSummaryEntity summary, List<ForeshadowScheduleEntity> schedules) {
        if (summary == null || summary.getChapterNo() == null) {
            return StampResult.EMPTY;
        }
        int chapterNo = summary.getChapterNo();
        List<ForeshadowScheduleEntity.ScheduleItem> items = openItems(schedules);
        if (items.isEmpty()) {
            return StampResult.EMPTY;
        }

        int planted = matchPlant(summary, items, chapterNo);
        int paid = matchPayoff(summary, items, chapterNo);
        int missed = expireUnplanted(items, chapterNo);
        return new StampResult(planted, paid, missed, items.size());
    }

    /** 全部排期项（跨版本展平）；null 安全 */
    private static List<ForeshadowScheduleEntity.ScheduleItem> openItems(List<ForeshadowScheduleEntity> schedules) {
        if (schedules == null || schedules.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashMap<String, ForeshadowScheduleEntity.ScheduleItem> dedup = new java.util.LinkedHashMap<>();
        schedules.stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(s -> ForeshadowScheduleEntity.itemsOf(s).stream())
                .filter(java.util.Objects::nonNull)
                .forEach(item -> {
                    String key = ForeshadowSpanPolicy.normalize(item.getIntent());
                    if (StringUtils.isBlank(key)) {
                        // 无意图条目不参与去重，逐条保留（保持旧行为）
                        dedup.put(java.util.UUID.randomUUID().toString(), item);
                        return;
                    }
                    dedup.put(key, item);
                });
        return List.copyOf(dedup.values());
    }

    /**
     * 把本章新埋的 seed 与**窗口内待埋**的排期项配对。
     *
     * <p>匹配口径与仓库其它处一致：精确相等优先，退最长公共子串 ≥ {@value #MATCH_MIN_CHARS}。
     * 一条排期项只认领一个 seed（先到先得），避免"一条意图吸走多个种子"。
     */
    private int matchPlant(ChapterSummaryEntity summary, List<ForeshadowScheduleEntity.ScheduleItem> items,
                           int chapterNo) {
        if (summary.getForeshadowSeeds() == null || summary.getForeshadowSeeds().isEmpty()) {
            return 0;
        }
        int planted = 0;
        for (ChapterSummaryEntity.SeedEntry seed : summary.getForeshadowSeeds()) {
            if (seed == null || StringUtils.isBlank(seed.getContent()) || seed.getScheduledPayoffChapter() != null) {
                continue;
            }
            ForeshadowScheduleEntity.ScheduleItem hit =
                    bestPlantCandidate(seed.getContent(), items, chapterNo);
            if (hit == null) {
                continue;
            }
            seed.setScheduledPayoffChapter(hit.getPayoffChapter());
            hit.setStatus(ForeshadowScheduleEntity.STATUS_PLANTED);
            hit.setActualPlantChapter(chapterNo);
            planted++;
        }
        return planted;
    }

    /** 候选 = 状态 PLANNED 且 plantChapter 落在 [本章-窗口, 本章+窗口] 内，取相似度最高者 */
    private ForeshadowScheduleEntity.ScheduleItem bestPlantCandidate(
            String seedContent, List<ForeshadowScheduleEntity.ScheduleItem> items, int chapterNo) {
        String target = ForeshadowSpanPolicy.normalize(seedContent);
        if (target.isEmpty()) {
            return null;
        }
        ForeshadowScheduleEntity.ScheduleItem best = null;
        int bestScore = 0;
        for (ForeshadowScheduleEntity.ScheduleItem item : items) {
            if (!ForeshadowScheduleEntity.STATUS_PLANNED.equals(item.getStatus())
                    || item.getPlantChapter() == null
                    || item.getPayoffChapter() == null) {
                continue;
            }
            if (Math.abs(item.getPlantChapter() - chapterNo) > PLANT_MATCH_WINDOW) {
                continue;
            }
            String intent = ForeshadowSpanPolicy.normalize(item.getIntent());
            if (intent.isEmpty()) {
                continue;
            }
            int score = intent.equals(target) ? Integer.MAX_VALUE
                    : ForeshadowSpanPolicy.longestCommonSubstring(intent, target);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore >= MATCH_MIN_CHARS ? best : null;
    }

    private int matchPayoff(ChapterSummaryEntity summary, List<ForeshadowScheduleEntity.ScheduleItem> items,
                            int chapterNo) {
        if (summary.getForeshadowingResolved() == null || summary.getForeshadowingResolved().isEmpty()) {
            return 0;
        }
        int paid = 0;
        for (String resolved : summary.getForeshadowingResolved()) {
            if (StringUtils.isBlank(resolved)) {
                continue;
            }
            String target = ForeshadowSpanPolicy.normalize(resolved);
            for (ForeshadowScheduleEntity.ScheduleItem item : items) {
                if (!ForeshadowScheduleEntity.STATUS_PLANTED.equals(item.getStatus())) {
                    continue;
                }
                String intent = ForeshadowSpanPolicy.normalize(item.getIntent());
                if (intent.isEmpty()) {
                    continue;
                }
                boolean hit = intent.equals(target)
                        || ForeshadowSpanPolicy.longestCommonSubstring(intent, target) >= MATCH_MIN_CHARS;
                if (hit) {
                    item.setStatus(ForeshadowScheduleEntity.STATUS_PAID);
                    item.setActualPayoffChapter(chapterNo);
                    paid++;
                    break;
                }
            }
        }
        return paid;
    }

    private int expireUnplanted(List<ForeshadowScheduleEntity.ScheduleItem> items, int chapterNo) {
        int missed = 0;
        for (ForeshadowScheduleEntity.ScheduleItem item : items) {
            if (ForeshadowScheduleEntity.STATUS_PLANNED.equals(item.getStatus())
                    && item.getPlantChapter() != null
                    && chapterNo > item.getPlantChapter() + PLANT_MATCH_WINDOW) {
                item.setStatus(ForeshadowScheduleEntity.STATUS_MISSED);
                missed++;
            }
        }
        if (missed > 0) {
            log.info("伏笔排期：第{}章结算，{} 条排期项到期未埋，标记 MISSED", chapterNo, missed);
        }
        return missed;
    }

    /**
     * 一次打标的统计（供日志与测试断言）。
     *
     * @param planted 本次新打标（PLANNED→PLANTED）的条数
     * @param paid    本次判为已兑现（PLANTED→PAID）的条数
     * @param missed  本次到期未埋（PLANNED→MISSED）的条数
     * @param total   排期表内条目总数
     */
    public record StampResult(int planted, int paid, int missed, int total) {
        public static final StampResult EMPTY = new StampResult(0, 0, 0, 0);

        /** 本次是否发生了任何状态变化 */
        public boolean changed() {
            return planted > 0 || paid > 0 || missed > 0;
        }
    }
}
