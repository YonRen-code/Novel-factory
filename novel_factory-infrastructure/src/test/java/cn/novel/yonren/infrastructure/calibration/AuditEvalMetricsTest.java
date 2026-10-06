package cn.novel.yonren.infrastructure.calibration;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计噪声度量引擎测试（纯函数）：指标语义的回归钉。
 * 指标判读口径见 AuditEvalMetrics 类注释。
 */
class AuditEvalMetricsTest {

    private static AuditEvalMetrics.IssueDigest digest(String dimension, String severity) {
        return new AuditEvalMetrics.IssueDigest(dimension, severity);
    }

    @Test
    void identicalRuns_areFullyStable() {
        List<List<AuditEvalMetrics.IssueDigest>> runs = List.of(
                List.of(digest("pacing", "MINOR"), digest("hook", "MINOR")),
                List.of(digest("pacing", "MINOR"), digest("hook", "MINOR")),
                List.of(digest("pacing", "MINOR"), digest("hook", "MINOR")));

        AuditEvalMetrics.SampleNoise noise = AuditEvalMetrics.evaluate(1, runs);

        assertEquals(1.0, noise.setJaccardMean(), 1e-9);
        assertEquals(0.0, noise.issueCountCv(), 1e-9);
        assertTrue(noise.blockingStable(), "三次都无 BLOCKING 是可复现结论");
        assertEquals(0.0, noise.blockingAppearanceRate(), 1e-9);
    }

    @Test
    void allBlockingRuns_areStableToo() {
        List<List<AuditEvalMetrics.IssueDigest>> runs = List.of(
                List.of(digest("consistency", "BLOCKING")),
                List.of(digest("consistency", "BLOCKING")));

        AuditEvalMetrics.SampleNoise noise = AuditEvalMetrics.evaluate(2, runs);

        assertTrue(noise.blockingStable());
        assertEquals(1.0, noise.blockingAppearanceRate(), 1e-9);
    }

    @Test
    void blockingFlip_isDetected() {
        // 三次里只有一次判出 BLOCKING：出现率 1/3，非稳定——最贵的抖动
        List<List<AuditEvalMetrics.IssueDigest>> runs = List.of(
                List.of(digest("consistency", "BLOCKING")),
                List.of(),
                List.of(digest("hook", "MINOR")));

        AuditEvalMetrics.SampleNoise noise = AuditEvalMetrics.evaluate(3, runs);

        assertFalse(noise.blockingStable(), "BLOCKING 时有时无必须被判为翻车");
        assertEquals(1.0 / 3.0, noise.blockingAppearanceRate(), 1e-9);
        assertTrue(noise.setJaccardMean() < 0.5, "三次结果集合几乎无交集");
    }

    @Test
    void multisetJaccard_countsDuplicates() {
        // (pacing,MINOR)×2 与 (pacing,MINOR)×1 + (hook,MINOR)×1：
        // 交集 min(2,1)+min(0,1)=1，并集 max(2,1)+max(0,1)=3 → 1/3
        double jaccard = AuditEvalMetrics.multisetJaccard(
                List.of(digest("pacing", "MINOR"), digest("pacing", "MINOR")),
                List.of(digest("pacing", "MINOR"), digest("hook", "MINOR")));

        assertEquals(1.0 / 3.0, jaccard, 1e-9);
    }

    @Test
    void dimensionCv_ranksChattiestDimension() {
        // hook 两轮 [1,0]（时有时无 → CV=1.0，最抖）；pacing 两轮 [1,2]（每次都在但数量抖 → CV=1/3）
        List<List<AuditEvalMetrics.IssueDigest>> runs = List.of(
                List.of(digest("hook", "MINOR"), digest("pacing", "MINOR")),
                List.of(digest("pacing", "MINOR"), digest("pacing", "MINOR")));

        AuditEvalMetrics.SampleNoise noise = AuditEvalMetrics.evaluate(4, runs);

        AuditEvalMetrics.DimensionNoise pacing = noise.dimensions().stream()
                .filter(d -> d.dimension().equals("pacing")).findFirst().orElseThrow();
        AuditEvalMetrics.DimensionNoise hook = noise.dimensions().stream()
                .filter(d -> d.dimension().equals("hook")).findFirst().orElseThrow();
        assertEquals(1.0, hook.cv(), 1e-9, "时有时无的维度 CV=1（半个均值的标准差）");
        assertEquals(1.5, pacing.meanCount(), 1e-9);
        assertTrue(pacing.cv() < hook.cv(), "每次都在但数量抖动的维度，CV 必须低于时有时无的维度");
    }

    @Test
    void allEmptyRuns_areStableNotMissing() {
        List<List<AuditEvalMetrics.IssueDigest>> runs = List.of(List.of(), List.of(), List.of());

        AuditEvalMetrics.SampleNoise noise = AuditEvalMetrics.evaluate(5, runs);

        assertEquals(0.0, noise.issueCountCv(), 1e-9, "全程 0 条是稳定结论，CV 不应是 NaN");
        assertEquals(1.0, noise.setJaccardMean(), 1e-9);
        assertTrue(noise.dimensions().isEmpty());
    }

    @Test
    void digest_toleratesNullFields() {
        ChapterIssueEntity bare = new ChapterIssueEntity();
        AuditEvalMetrics.IssueDigest digest = AuditEvalMetrics.digest(bare);

        assertEquals("?", digest.dimension());
        assertEquals("?", digest.severity());
    }
}
