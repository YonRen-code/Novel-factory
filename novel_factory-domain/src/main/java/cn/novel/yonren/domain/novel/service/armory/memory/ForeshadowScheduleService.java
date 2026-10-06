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

/**
 * 伏笔排期**打标服务**（2026-10-02 新增，P2b）：在埋设章把排期表落到种子上。
 *
 * <p><b>为什么必须"打标一次、下游全走精确读取"</b>：排期表存的是蓝图写的**意图**，
 * 而 seed 是摘要模型写的**描述**——两份不同来源的文本。若让每个消费端（清账过滤、
 * 段计划注入、漏收指标）各自去模糊匹配，会得到不一致的结论，且每次都要重算。
 * 所以只在**埋设章那一次**做匹配，把结果写进 {@code SeedEntry.scheduledPayoffChapter}，
 * 之后所有消费端只读字段、不做匹配。
 *
 * <p><b>为什么在这个时刻匹配最稳</b>：排期项写明了 {@code plantChapter}，
 * 而 seed 是**那一章**记录的——章号是硬约束，比事后跨章拿意图去猜描述稳得多。
 * 窗口取 {@link #PLANT_MATCH_WINDOW} 章：模型实测会偏移 1-2 章（它连
 * {@code mainLineAdvance} 都要靠 JSON 示例才肯回填），按"章号精确相等"匹配会让整表腐烂成 MISSED。
 *
 * <p><b>状态推进</b>：{@code PLANNED →(埋下)→ PLANTED →(回收)→ PAID}；
 * 到期未埋 → {@code MISSED}（"模型没按排期埋"，与"埋了没收"分开记）。
 */
@Slf4j
@Service
public class ForeshadowScheduleService {

    /**
     * 埋设匹配窗口（章）。
     *
     * <p>取 2 的依据：模型对排期章的偏移实测在 1-2 章内；窗口再大就会让"排期项 A 撞上
     * 排期项 B 的 seed"这类错配变多，反而污染打标。
     */
    public static final int PLANT_MATCH_WINDOW = 2;

    /**
     * intent ↔ seed 描述的最小公共子串。
     *
     * <p>**刻意与指标口径（{@code ForeshadowSpanPolicy} 的 6 字）分档**（教训：指标与管道不能共用一把尺）：
     * 这里匹配的是两份不同来源的文本——intent 是蓝图模型写的，seed 是摘要模型写的，
     * 同一件事实测会被改写掉 1-2 个字：老书两批实测中「老银镯子」(LCS=4)、「儿子炒股亏」(5)、
     * 「派出所备案」(5) 全部倒在 6 字门槛下被误判 MISSED，其中两条还是**分毫不差按排期章埋下**的。
     * 打标自身有窗口 ±2、「一条排期项只认领一个 seed」与相似度取最优三重兜底，4 字误配风险可控。
     */
    private static final int MATCH_MIN_CHARS = 4;

    /**
     * 本章写完后打标（**机械，零 LLM 成本**）。
     *
     * <p>直接就地修改 {@code summary} 与 {@code schedules}，调用方负责随后落盘
     * （与 {@code stripVoidedForeshadows} 同款契约：函数内不落盘）。
     *
     * @param summary   本章摘要（含新埋 seeds 与本章回收清单）
     * @param schedules 排期表链（就地推进 status；null/空则整体跳过）
     * @return 本次打标统计（供日志与测试断言）
     */
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
        // 跨块 intent 去重46-50 章实测）：同一意图会在"历史块 + 结转块"各存一份
        //（老书实测：一条"工厂管理层注意运气"双条目双双 MISSED）——展平时按归一 intent 保留**最后一个**
        //（块序=阶段序，最后一个是最新状态），避免同线双计污染打标与注入
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

    /**
     * 本章回收清单里若命中某条已排期（PLANTED）的线，则判 PAID。
     *
     * <p>匹配对象是**排期项的 intent**——因为种子描述与 intent 已在埋设时对齐过一次，
     * 而回收清单是摘要模型逐字沿用账本描述的，故这里仍按同一套口径比对。
     */
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

    /**
     * 到期未埋 → MISSED：{@code PLANNED} 且已过 {@code plantChapter + 窗口}。
     *
     * <p>这条度量的是"**模型没按排期埋**"，与"埋了没收"（由漏收指标度量）分开记——
     * 两者的处置完全不同：前者是排期被无视，后者是兑现被拖延。
     */
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
