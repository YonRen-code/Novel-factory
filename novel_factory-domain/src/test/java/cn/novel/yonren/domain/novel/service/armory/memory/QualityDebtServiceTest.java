package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量债结算测试：连续无复发核销、同维度复发续期、已核销债跳过、维度缺失兜底
 */
class QualityDebtServiceTest {

    private final QualityDebtService service = new QualityDebtService();

    @Test
    void settle_resolvesAfterTwoCleanChapters() {
        QualityDebtEntity debt = debt(1, issue("consistency"));

        service.settle(List.of(debt), List.of());
        assertFalse(debt.isResolved());
        assertEquals(1, debt.getCleanStreak());

        service.settle(List.of(debt), List.of());
        assertTrue(debt.isResolved());
        assertEquals(2, debt.getCleanStreak());
    }

    @Test
    void settle_sameDimensionRecurrenceRenews() {
        QualityDebtEntity debt = debt(1, issue("consistency"), issue("hook"));

        // 维度比对大小写归一：CONSISTENCY 仍算复发
        service.settle(List.of(debt), List.of(issue("CONSISTENCY")));
        assertFalse(debt.isResolved());
        assertEquals(0, debt.getCleanStreak());

        service.settle(List.of(debt), List.of(issue("pacing")));
        assertEquals(1, debt.getCleanStreak());

        service.settle(List.of(debt), List.of(issue("consistency"), issue("hook")));
        assertEquals(0, debt.getCleanStreak());
    }

    @Test
    void settle_recurrenceThenTwoCleanChaptersResolves() {
        QualityDebtEntity debt = debt(2, issue("hook"));

        service.settle(List.of(debt), List.of(issue("hook")));
        service.settle(List.of(debt), List.of(issue("hook")));
        assertFalse(debt.isResolved());

        service.settle(List.of(debt), List.of(issue("pacing")));
        assertFalse(debt.isResolved());
        service.settle(List.of(debt), List.of());
        assertTrue(debt.isResolved());
    }

    @Test
    void settle_skipsResolvedDebtsAndMatchesByDimensionOnly() {
        QualityDebtEntity settled = debt(1, issue("hook"));
        settled.setResolved(true);
        QualityDebtEntity alive = debt(2, issue("pacing"));

        // 本章 hook 复发只撞已核销债，不影响未核销债
        service.settle(List.of(settled, alive), List.of(issue("hook")));

        assertTrue(settled.isResolved());
        assertFalse(alive.isResolved());
        assertEquals(1, alive.getCleanStreak());
    }

    @Test
    void settle_dimensionMissingDebtStaysUnresolvedBecauseItCannotBeVerified() {
        QualityDebtEntity debt = debt(1, issue(null));

        service.settle(List.of(debt), List.of(issue("hook")));
        service.settle(List.of(debt), List.of(issue("hook")));

        assertFalse(debt.isResolved());
        assertEquals(0, debt.getCleanStreak());
    }

    @Test
    void settle_toleratesNullInputs() {
        QualityDebtEntity debt = debt(1, issue("consistency"));

        service.settle(null, List.of());
        service.settle(List.of(), List.of());
        service.settle(List.of(debt), null);

        assertEquals(1, debt.getCleanStreak());
        assertFalse(debt.isResolved());
    }

    private QualityDebtEntity debt(int chapterNo, ChapterIssueEntity... issues) {
        return QualityDebtEntity.builder()
                .chapterNo(chapterNo)
                .issues(new ArrayList<>(List.of(issues)))
                .resolved(false)
                .build();
    }

    private ChapterIssueEntity issue(String dimension) {
        return ChapterIssueEntity.builder()
                .dimension(dimension)
                .severity("BLOCKING")
                .build();
    }

}
