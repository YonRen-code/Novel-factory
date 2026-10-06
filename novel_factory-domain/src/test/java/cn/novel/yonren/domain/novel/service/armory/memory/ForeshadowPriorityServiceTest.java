package cn.novel.yonren.domain.novel.service.armory.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伏笔动态权重打分测试：公式、重要度收敛、阈值边界、排序稳定性
 */
class ForeshadowPriorityServiceTest {

    private final ForeshadowPriorityService service = new ForeshadowPriorityService();

    @Test
    void score_formulaBasePlusFerment() {
        // importance 5 → 基础 50；滞留 6 章 → +30
        assertEquals(80, ForeshadowPriorityService.scoreOf(5, 1, 7));
        // 无评分默认 3 档（30），当章新埋滞留 0
        assertEquals(30, ForeshadowPriorityService.scoreOf(null, 7, 7));
    }

    @Test
    void score_clampsImportanceToValidRange() {
        int top = ForeshadowPriorityService.scoreOf(5, 1, 7);
        int low = ForeshadowPriorityService.scoreOf(1, 1, 7);
        assertEquals(top, ForeshadowPriorityService.scoreOf(9, 1, 7));
        assertEquals(low, ForeshadowPriorityService.scoreOf(0, 1, 7));
        assertEquals(low, ForeshadowPriorityService.scoreOf(-3, 1, 7));
    }

    @Test
    void tier_thresholdsMatchEscalationDesign() {
        assertEquals(ForeshadowPriorityService.Tier.BACKGROUND, ForeshadowPriorityService.tierOf(59, 3));
        assertEquals(ForeshadowPriorityService.Tier.SOFT, ForeshadowPriorityService.tierOf(60, 3));
        assertEquals(ForeshadowPriorityService.Tier.SOFT, ForeshadowPriorityService.tierOf(79, null));
        assertEquals(ForeshadowPriorityService.Tier.HARD, ForeshadowPriorityService.tierOf(80, 3));
        assertEquals(ForeshadowPriorityService.Tier.HARD, ForeshadowPriorityService.tierOf(99, 3));
        assertEquals(ForeshadowPriorityService.Tier.BREAKER, ForeshadowPriorityService.tierOf(100, 3));
        assertEquals(ForeshadowPriorityService.Tier.BREAKER, ForeshadowPriorityService.tierOf(100, null));
    }

    @Test
    void tier_spineImportanceNeverBreaks() {
        // 主线核心（5 分）超过熔断线也封顶在硬级，永远可见
        assertEquals(ForeshadowPriorityService.Tier.HARD, ForeshadowPriorityService.tierOf(100, 5));
        assertEquals(ForeshadowPriorityService.Tier.HARD, ForeshadowPriorityService.tierOf(150, 5));
    }

    @Test
    void score_sortsByScoreDescThenPlantOrder() {
        List<ChapterMemoryService.PendingForeshadow> pending = List.of(
                new ChapterMemoryService.PendingForeshadow(1, "甲", null, 1),
                new ChapterMemoryService.PendingForeshadow(3, "乙", null, 5),
                new ChapterMemoryService.PendingForeshadow(2, "丙", null, null));

        List<ForeshadowPriorityService.ScoredForeshadow> scored = service.score(pending, 3);

        // 乙 50 > 丙 35 > 甲 20
        assertEquals("乙", scored.get(0).item().content());
        assertEquals("丙", scored.get(1).item().content());
        assertEquals("甲", scored.get(2).item().content());
        assertEquals(ForeshadowPriorityService.Tier.BACKGROUND, scored.get(0).tier());
    }

    @Test
    void score_nullPendingReturnsEmpty() {
        assertTrue(service.score(null, 5).isEmpty());
    }

}
