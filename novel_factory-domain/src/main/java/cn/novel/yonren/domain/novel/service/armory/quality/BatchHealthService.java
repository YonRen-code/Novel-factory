package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import cn.novel.yonren.domain.novel.model.entity.StageBlueprintEntity;
import cn.novel.yonren.types.enums.ChapterTypeVO;
import lombok.extern.slf4j.Slf4j;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批末体检服务：把阶段一~四建立的机械指标横着看一遍，输出可分级、可行动的
 * {@link BatchHealthReport}（纯函数，零 LLM 成本，可在任意时刻重算）。
 *
 * <p><b>阈值标定说明（重要）</b>：下表中的健康线/劣化线是依据
 * <em>2026-09-16 改造前的 162 章实测基线</em>标定的<strong>初值</strong>——
 * 改造后还没有跑过完整批次，因此这些线是"针对已知病态设定的判别线"，
 * 不是"理想水平"。第一批改造后数据出来必须重新校准（见 {@link #THRESHOLD_NOTE}）。
 * 标定时刻意把劣化线定在"明显比现状好"而不是"理想值"上：
 * 体检报告若第一次跑就全红，它就失去了区分力（与退出条件恒红的教训一致）。
 *
 * <p><b>不做</b>：不读 LLM 用量日志（成本属执行器/预算熔断范畴）、不做任何停机或降级动作
 *（动作属阶段六执行器）。本服务只回答"现在健不健康、该看哪儿"。
 */
@Slf4j
@Service
public class BatchHealthService {

    /** 阈值标定基准说明：写在代码里，避免后人把初值当圣旨 */
    public static final String THRESHOLD_NOTE =
            "阈值为 2026-09-16 改造前 162 章基线的标定初值，第一批改造后数据出齐后必须重新校准";

    /** 体检最小样本：低于此章数各项比率噪声过大，直接返回"数据不足" */
    public static final int MIN_SAMPLE_CHAPTERS = 5;

    /** 劣化指标达到该数量即升级为 CRITICAL（多项同时劣化通常意味着某层改造没生效） */
    public static final int CRITICAL_METRIC_COUNT = 3;

    /** 低密度章判定线：与规划层 density feedback 的参考线一致 */
    private static final int LOW_DENSITY_CHARS = 1500;

    // ---------- 阈值：账本完整度（改造前基线 65.2%） ----------
    private static final double LEDGER_OK = 0.90;
    private static final double LEDGER_DEGRADED = 0.80;
    /** 分档计权时"放宽档"的权重：证据宽松得来，可信度低于逐字命中的严格档 */
    private static final double LOOSE_WEIGHT = 0.5;
    // ---------- 生成模式（2026-09-22）：兜底路径被吃掉的频率 ----------
    // ⚠️ 无历史基线（截至 2026-09-22 两批 12 章全部走 FREE），阈值暂定，先当"观察项"用
    private static final double FALLBACK_MODE_OK = 0.20;
    private static final double FALLBACK_MODE_DEGRADED = 0.50;

    /** 无检索唤醒章数占比（基础设施指标）：0 = 完全没有降级，是正常态；≥0.30 说明向量链路在持续抖动 */
    private static final double RECALL_DEGRADED_OK = 0.0;
    private static final double RECALL_DEGRADED_LINE = 0.30;

    /**
     * 修订验证未跑成占比（基础设施指标）：0 = 正常态。
     * 与「无检索唤醒章数占比」同则——都是"降级不得静默"的落点，且都不进质量债。
     */
    private static final double VERIFY_DEGRADED_OK = 0.0;
    private static final double VERIFY_DEGRADED_LINE = 0.30;

    /**
     * 超长章占比（注水指标）：有效字 > {@link ChapterLengthPolicy#MAXIMUM_REFERENCE_CHARACTERS} 的章占比。
     * 与「低密度章占比」是一对——下沿查"料太少"，上沿查"水太多"。**刻意不豁免过渡章**。
     */
    private static final double OVERSIZED_OK = 0.10;
    private static final double OVERSIZED_DEGRADED = 0.25;
    // ---------- 正文复核改判占比（2026-09-22）：摘要粒度缺口的度量 ----------
    private static final double RECHECK_OK = 0.20;
    private static final double RECHECK_DEGRADED = 0.50;
    // ---------- 主线最长停留（2026-09-22）：与 SuspenseLadderPolicy 的闸门判据同源 ----------
    /** 连续同档上限：与计划闸门一致（≥3 章同档即违例），单位是"章"不是比例 */
    private static final double SUSPENSE_HOLD_OK = 2;
    private static final double SUSPENSE_HOLD_DEGRADED = 3;

    /**
     * 伏笔平均保密跨度（章）——2026-10-01 新增。
     * 实测第 1–15 章平均 2.23 章、77% 在 2 章内兑现，是"读起来浅"的结构性指标。
     * ⚠️ 无历史基线，取值按实测分布定，须经更多批次校准（项目约定：不要凭直觉取整）。
     */
    private static final double FORESHADOW_SPAN_OK = 4.0;
    private static final double FORESHADOW_SPAN_DEGRADED = 3.0;

    /**
     * 在途伏笔滞留中位数（章）——2026-10-02 新增，`foreshadowSpan` 的互补指标。
     * 实测：41 条埋设中 31 条在途、其中 18 条滞留 ≥5 章、最长 14 章。
     * ⚠️ 无法与历史比较（该口径首次引入），取值先按实测分布设定，须经更多批次校准。
     */
    private static final double FORESHADOW_PENDING_OK = 6.0;
    private static final double FORESHADOW_PENDING_DEGRADED = 9.0;
    /** 单条伏笔滞留达到该章数即视为"陈旧"，单独计数供人工分辨长线/遗忘 */
    private static final int FORESHADOW_PENDING_STALE = 5;

    /** 伏笔未标注占比的可容忍上沿：超过即认为寿命/在途两项指标的样本已被掏空 */
    private static final double FORESHADOW_UNLABELED_OK_TOLERANCE = 0.20;

    /** 漏收伏笔数分级（2026-10-02 新增，P2a）：无历史基线，先按"每阶段允许欠 1 条"设定，须校准 */
    private static final double FORESHADOW_MISSED_OK = 1.0;
    private static final double FORESHADOW_MISSED_DEGRADED = 3.0;
    // ---------- 每章新地点率（改造前基线 0.83，10 章窗口内中位 10 个不同地点） ----------
    private static final double NEW_PLACE_OK = 0.30;
    private static final double NEW_PLACE_DEGRADED = 0.60;
    // ---------- 过渡章占比（改造前恒为 0：章型无法声明） ----------
    private static final double TRANSITION_MIN_OK = 0.08;
    private static final double TRANSITION_MIN_DEGRADED = 0.04;
    private static final double TRANSITION_MAX_OK = 0.33;
    private static final double TRANSITION_MAX_DEGRADED = 0.50;
    // ---------- 最长连续过渡章（与规划 prompt 的硬约束一致：不得连续超过 2 章） ----------
    private static final int TRANSITION_RUN_OK = 2;
    private static final int TRANSITION_RUN_DEGRADED = 4;
    // ---------- 低密度章占比（排除过渡章） ----------
    private static final double LOW_DENSITY_OK = 0.15;
    private static final double LOW_DENSITY_DEGRADED = 0.35;
    // ---------- 未核销质量债 ----------
    private static final int DEBT_OK = 3;
    private static final int DEBT_DEGRADED = 8;
    // ---------- 阶段出口条件达成率（改造前基线 22%） ----------
    private static final double EXIT_OK = 0.60;
    private static final double EXIT_DEGRADED = 0.35;
    // ---------- 放宽档入账占比（留痕 spread/anchored + 裁决 adjudicated） ----------
    private static final double LOOSE_OK = 0.10;
    private static final double LOOSE_DEGRADED = 0.25;
    // ---------- 候选链路（触发率历史基线 19%；采纳率历史基线 48%） ----------
    private static final double CANDIDATE_TRIGGER_OK = 0.30;
    private static final double CANDIDATE_TRIGGER_DEGRADED = 0.60;
    private static final double CANDIDATE_ADOPTION_OK = 0.45;
    private static final double CANDIDATE_ADOPTION_DEGRADED = 0.30;

    /**
     * 批末体检（纯函数）。
     *
     * @param summaries  全量章节摘要（按章号排序不敏感，内部会排）
     * @param debts      质量债清单
     * @param blueprints 阶段蓝图链（取 exitResults 统计出口条件达成率）
     */
    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints) {
        return assess(summaries, debts, blueprints, null, null);
    }

    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints,
                                    CandidateSampleService.CandidateStats candidates) {
        return assess(summaries, debts, blueprints, candidates, null);
    }

    /**
     * 体检入口（2026-10-02 增 settlements）。
     *
     * <p><b>为什么要传结算台账</b>：伏笔类指标必须与**账本同口径**。
     * `stripVoidedForeshadows` 只清理 `foreshadowingNew`（账本读它），而
     * `ForeshadowSpanPolicy` 读的是 `foreshadowSeeds`（**从未被清理**）——
     * 若不把 `voidedContents` 传下去排除，弃置掉的长线会被当成"永远在途"，
     * 把滞留中位数单调推高。同时本方法据此产出**静默兑现数**。
     *
     * @param settlements 卷末清账结算台账（可为 null——null 时伏笔类指标退化为"不排除弃置"，
     *                    与引入本参数前行为一致）
     * @param candidates  候选链路统计，可为 null
     */
    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints,
                                    CandidateSampleService.CandidateStats candidates,
                                    List<ForeshadowSettlementEntity> settlements) {
        return assess(summaries, debts, blueprints, candidates, settlements, null);
    }

    public BatchHealthReport assess(List<ChapterSummaryEntity> summaries,
                                    List<QualityDebtEntity> debts,
                                    List<StageBlueprintEntity> blueprints,
                                    CandidateSampleService.CandidateStats candidates,
                                    List<ForeshadowSettlementEntity> settlements,
                                    String chapterGoal) {
        List<ChapterSummaryEntity> chapters = summaries == null ? List.of() : summaries.stream()
                .filter(s -> s != null && s.getChapterNo() != null)
                .sorted(Comparator.comparing(ChapterSummaryEntity::getChapterNo))
                .toList();

        List<BatchHealthReport.Metric> metrics = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        if (chapters.size() < MIN_SAMPLE_CHAPTERS) {
            return new BatchHealthReport(chapters.size(), metrics, BatchHealthReport.Level.OK,
                    List.of("样本不足 " + MIN_SAMPLE_CHAPTERS + " 章，体检结论不可信；" + THRESHOLD_NOTE));
        }

        addLedgerCompleteness(metrics, recommendations, chapters);
        addNewPlaceRate(metrics, recommendations, chapters);
        addTransitionShare(metrics, recommendations, chapters);
        addTransitionRun(metrics, recommendations, chapters);
        addLowDensityShare(metrics, recommendations, chapters);
        addOversizedChapterShare(metrics, recommendations, chapters);
        addUnresolvedDebt(metrics, recommendations, debts);
        addExitConditionRate(metrics, recommendations, blueprints);
        addLooseTierShare(metrics, recommendations, chapters);
        addDialogueMetrics(metrics, recommendations, chapters);
        addCandidateMetrics(metrics, recommendations, chapters.size(), candidates);
        // 2026-09-22 新增：补齐本轮新机制的观测。
        // 此前"生成模式 / 认知边界 / 正文复核"只写代码和日志，体检完全看不到——
        // 后果是改了也说不清效果（"三模式只用 FREE"只能靠 grep 日志得出，而不是看指标）。
        addGenerationModeShare(metrics, recommendations, chapters);
        addKnowledgeBoundaryCoverage(metrics, recommendations, chapters);
        addRecallDegradedShare(metrics, recommendations, chapters);
        addAuditVerifyDegradedShare(metrics, recommendations, chapters);
        addRecheckRecoveredShare(metrics, recommendations, blueprints);
        addSuspenseHold(metrics, recommendations, chapters, blueprints);
        // 2026-10-05 新增：蓝图 fail-soft 降级的显式化——此前只留一条易被淹没的 WARN，
        // 36-40 章实测整批在旧蓝图上滑行（排期 71 条原地踏步）而批末体检完全看不到
        addStagePlanningCoverage(metrics, recommendations, chapters, blueprints);
        // 2026-10-05 新增：进度对齐——大纲（chapterGoal）按章段预算的时间/年龄 vs 时序锚实际值。
        // chapterGoal 缺失或无结构时豁免（fail-soft，与 stagePlanningCoverage 的 legacy 豁免同款）
        addOutlinePacing(metrics, recommendations, chapters, chapterGoal);
        // 伏笔类指标必须与账本同口径：先取清账弃置清单，排除掉"已被判死的条目"
        List<String> voided = ForeshadowSettlementEntity.voidedContents(settlements);
        addForeshadowSpan(metrics, recommendations, chapters, voided);
        addForeshadowPending(metrics, recommendations, chapters, voided);
        addForeshadowMissed(metrics, recommendations, chapters, voided);
        addSettlementAudit(metrics, recommendations, settlements);

        BatchHealthReport.Level overall = overall(metrics);
        return new BatchHealthReport(chapters.size(), metrics, overall, recommendations);
    }

    /**
     * 兜底模式占比 =（SCAFFOLDED + RECOVERY 章数）/ 有模式记录的章数（2026-09-22 新增）。
     *
     * <p>三模式的语义：FREE = 契约完整且节拍可用（节拍只作建议）；SCAFFOLDED = 契约完整但节拍不可用
     * （用通用骨架兜底）；RECOVERY = 契约不完整（骨架 + 强约束）。
     * 后两者是**兜底路径**——占比长期为 0 说明它们从未被真实走到（写了没验），
     * 突然升高则说明计划质量或节拍生成在退化。**这是观察项，暂无历史基线。**
     *
     * <p>⚠️ 只统计**有模式记录**的章：老批次（该字段落盘前生成）视为无记录，不计入分母，
     * 否则会凭空造出一个"兜底 0%"的假指标。
     */
    private void addGenerationModeShare(List<BatchHealthReport.Metric> metrics,
                                        List<String> recommendations,
                                        List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int fallback = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || StringUtils.isBlank(s.getGenerationMode())) {
                continue;
            }
            recorded++;
            String mode = s.getGenerationMode().trim().toUpperCase(java.util.Locale.ROOT);
            if ("SCAFFOLDED".equals(mode) || "RECOVERY".equals(mode)) {
                fallback++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) fallback / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, FALLBACK_MODE_OK, FALLBACK_MODE_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("fallbackModeShare", "兜底模式占比",
                share * 100, "%",
                "≤" + pct(FALLBACK_MODE_OK) + "（SCAFFOLDED+RECOVERY 占比；截至 2026-09-22 无历史基线，观察项）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "兜底 " + fallback + " / 有模式记录 " + recorded + " 章"));
        if (fallback > 0) {
            recommendations.add("兜底模式被触发：" + fallback + " 章走了 SCAFFOLDED/RECOVERY——"
                    + "检查这些章的计划是否缺 endingHook（契约不完整）或节拍生成有没有连续失败");
        }
    }

    /**
     * 认知边界注入率 = 注入了认知边界的章数 / 有记录章数（2026-09-22 新增）。
     *
     * <p>正文 prompt 的【认知边界·不得越界】块来自**角色账本**里的认知类状态
     * （"尚未确认无月的真实身份"这类）。注入率为 0 有两种可能：账本里根本没记录认知状态
     * （上游摘要 prompt 的问题），或提取没生效——**两种都要有人管**，所以给个指标盯着。
     */
    private void addKnowledgeBoundaryCoverage(List<BatchHealthReport.Metric> metrics,
                                              List<String> recommendations,
                                              List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int injected = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getKnowledgeBoundaryInjected() == null) {
                continue;
            }
            recorded++;
            if (Boolean.TRUE.equals(s.getKnowledgeBoundaryInjected())) {
                injected++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) injected / recorded;
        // 该指标是"能力是否在运转"，低就是问题：账本没记认知状态 或 提取失效
        BatchHealthReport.Level level = higherIsBetter(share, 0.50, 0.20);
        metrics.add(new BatchHealthReport.Metric("knowledgeBoundaryCoverage", "认知边界注入率",
                share * 100, "%", "≥50%（来源：角色账本里的认知类状态；为 0 说明账本没记或提取失效）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "注入 " + injected + " / 有记录 " + recorded + " 章"));
        if (injected == 0) {
            recommendations.add("认知边界一次也没注入：先确认摘要是否记录了认知类状态"
                    + "（含「知道/不知/怀疑/确认/身份」等词），再查 ChapterContract 的提取逻辑");
        }
    }

    /**
     * 无检索唤醒章数占比 =（recallDegraded=true 的章数）/ 有该标记的章数（2026-09-29 新增）。
     *
     * <p>背景：跨章记忆检索失败已由"终止作业"改为"降级为空召回 + WARN"，但降级与"确实无命中"
     * 返回的命中表**完全一样**（都是空表）——只在日志里留痕的话，"本批有几章是在无记忆前缀下裸跑的"
     * 就只能靠人翻日志。故落进摘要机械标记，由本指标汇总；指标为 0 是**正常态**。
     *
     * <p>⚠️ **这是基础设施指标，不是正文质量指标**：一旦升高说明 embedding / 向量库 / 鉴权链路在抖动，
     * 该去查链路而不是调阈值。**刻意不进质量债**——质量债会回灌给写手当作"你上章犯的错"，
     * 把基础设施故障塞进去等于给模型下错误指令。
     *
     * <p>⚠️ 只统计**有该标记**的章：老批次（该字段落盘前生成）为 null，不计入分母，
     * 否则会凭空造出一个"0% 降级"的假指标（与兜底模式占比同一处理）。
     */
    private void addRecallDegradedShare(List<BatchHealthReport.Metric> metrics,
                                        List<String> recommendations,
                                        List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int degraded = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getRecallDegraded() == null) {
                continue;
            }
            recorded++;
            if (Boolean.TRUE.equals(s.getRecallDegraded())) {
                degraded++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) degraded / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, RECALL_DEGRADED_OK, RECALL_DEGRADED_LINE);
        metrics.add(new BatchHealthReport.Metric("recallDegradedShare", "无检索唤醒章数占比",
                share * 100, "%",
                "=0（基础设施指标：0 为正常态；>0 说明向量链路抖动，grep RECALL_DEGRADED 归因；观察项）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "无检索唤醒 " + degraded + " / 有记录 " + recorded + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("向量链路抖动：本批有 " + degraded + " 章在**无跨章记忆唤醒**下生成"
                    + "（grep RECALL_DEGRADED 看归因）。生成流程未中断，但这些章的长程一致性只靠"
                    + "近章摘要与账本承载，值得复核；应先查 embedding / 向量库 / 鉴权链路，而不是调阈值。");
        }
    }

    /**
     * 修订验证未跑成占比 =（auditVerifyDegraded=true 的章数）/ 有该标记的章数（2026-09-30 新增）。
     *
     * <p>背景：复审验证化之后，"验证步骤本身失败"（网关异常/输出无法解析）原先被并入"未修复"，
     * 于是一次抖动会被记成质量债，再回灌给写手当作"你上一章犯的错"——那是拿基础设施故障给模型下指令。
     * 现在这类章不落债，但**必须能被看见**：只留日志就回到"从产出上几乎看不出来"的老问题。
     *
     * <p>⚠️ **这是基础设施指标，不是正文质量指标**：0 是正常态；升高说明审计链路在抖动，
     * 该查链路而不是改判据。**刻意不进质量债**（与「无检索唤醒章数占比」同则）。
     *
     * <p>⚠️ 只统计有该标记的章：老批次为 null，不计入分母。
     */
    private void addAuditVerifyDegradedShare(List<BatchHealthReport.Metric> metrics,
                                             List<String> recommendations,
                                             List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int degraded = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getAuditVerifyDegraded() == null) {
                continue;
            }
            recorded++;
            if (Boolean.TRUE.equals(s.getAuditVerifyDegraded())) {
                degraded++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) degraded / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, VERIFY_DEGRADED_OK, VERIFY_DEGRADED_LINE);
        metrics.add(new BatchHealthReport.Metric("auditVerifyDegradedShare", "修订验证未跑成占比",
                share * 100, "%",
                "=0（基础设施指标：0 为正常态；>0 说明审计链路抖动，grep AUDIT_VERIFY_DEGRADED 归因；观察项）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "未验证 " + degraded + " / 有记录 " + recorded + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("修订验证未跑成：本批有 " + degraded + " 章带着**未验证**的 BLOCKING 结案"
                    + "（grep AUDIT_VERIFY_DEGRADED 看归因）。这些章**没有计入质量债**——"
                    + "它们的问题状态是「未知」而非「未修复」，别把债数当作质量下降的信号；"
                    + "应先查审计链路（网关/超时/输出解析）。");
        }
    }

    /**
     * 超长章占比 =（有效字 &gt; 参考上沿的章数）/ 有 validChars 记录的章数（2026-09-29 新增）。     *
     * <p>与「低密度章占比」是一对：下沿查"计划给的料太少"（产出偏薄），上沿查"关键事件没增加、
     * 描写与对白被拉长"（注水）。实测第 20 章有效字 4170 / 5 个关键事件，对照第 19 章
     * 2205 字 / 5 个关键事件——**字数翻倍而事件数不变**，只有这一项看得见（低密度指标全程没报）。
     *
     * <p>⚠️ **刻意不豁免过渡章**（与低密度指标相反）：下沿豁免过渡章是因为它本就该短；
     * 写得比常规章还长的过渡章恰恰是最典型的注水形态（ch20 正是 transition 章）。
     *
     * <p>⚠️ 只统计有 validChars 记录的章：老批次该字段为 null，不计入分母，否则会造出"0% 注水"的假指标。
     */
    private void addOversizedChapterShare(List<BatchHealthReport.Metric> metrics,
                                          List<String> recommendations,
                                          List<ChapterSummaryEntity> chapters) {
        int recorded = 0;
        int oversized = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s == null || s.getValidChars() == null) {
                continue;
            }
            recorded++;
            if (s.getValidChars() > ChapterLengthPolicy.MAXIMUM_REFERENCE_CHARACTERS) {
                oversized++;
            }
        }
        if (recorded == 0) {
            return;
        }
        double share = (double) oversized / recorded;
        BatchHealthReport.Level level = lowerIsBetter(share, OVERSIZED_OK, OVERSIZED_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("oversizedChapterShare", "超长章占比",
                share * 100, "%",
                "≤" + pct(OVERSIZED_OK) + "（注水信号：有效字 >"
                        + ChapterLengthPolicy.MAXIMUM_REFERENCE_CHARACTERS
                        + " 且关键事件数未增；**不豁免过渡章**）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "超长 " + oversized + " / 有记录 " + recorded + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("篇幅注水：本批有 " + oversized + " 章超出参考上沿——先确认"
                    + "story.paragraph-audit.enabled 是否开启（它是唯一能挤掉"
                    + "「只重复已知状态 / 原地循环情绪」段落的组件），再确认规划层的【注水反馈】块已回灌；"
                    + "不要在写手侧加压——拒收长章只会逼出另一种形态的注水。");
        }
    }

    /**
     * 正文复核改判占比 = 改判达成的条件数 /（改判数 + 复核后仍未达成数）（2026-09-22 新增）。
     *
     * <p>二阶段正文复核是补"摘要只说主干、不记动作细节"导致的假阴性——
     * 一阶段用摘要核验判未达成，复核用**正文**再看一遍。
     *
     * <p>⚠️ 这个比例**越低越好**：它衡量的是**摘要粒度缺口有多大**，
     * 而不是"复核救回了多少"（救回本身是好事，但它本不该发生）。
     * 长期偏高应当去改摘要 prompt（要求原样记录关键动作细节），而不是靠复核兜。
     */
    private void addRecheckRecoveredShare(List<BatchHealthReport.Metric> metrics,
                                          List<String> recommendations,
                                          List<StageBlueprintEntity> blueprints) {
        if (blueprints == null || blueprints.isEmpty()) {
            return;
        }
        int recovered = 0;
        int remainedUnmet = 0;
        for (StageBlueprintEntity blueprint : blueprints) {
            if (blueprint == null || blueprint.getExitResults() == null) {
                continue;
            }
            for (StageBlueprintEntity.ExitConditionResult result : blueprint.getExitResults()) {
                if (result == null || result.getNote() == null) {
                    continue;
                }
                if (result.getNote().contains(RECHECK_MARK)) {
                    recovered++;
                } else if (Boolean.FALSE.equals(result.getMet())) {
                    remainedUnmet++;
                }
            }
        }
        int total = recovered + remainedUnmet;
        if (total == 0) {
            return;
        }
        double share = (double) recovered / total;
        BatchHealthReport.Level level = lowerIsBetter(share, RECHECK_OK, RECHECK_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("recheckRecoveredShare", "正文复核改判占比",
                share * 100, "%",
                "≤" + pct(RECHECK_OK) + "（摘要粒度缺口的度量：越低说明摘要越完整、一阶段核验越可信）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "改判 " + recovered + " / 未达成合计 " + total));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("正文复核改判占比偏高：" + recovered + " 条靠正文才救回来——"
                    + "说明摘要丢掉了核验需要的细节，考虑在摘要 prompt 里要求原样记录关键动作/标志性描写");
        }
    }

    /** 正文复核改判的留痕标记（与 StageExitReviewService 写入的 note 保持一致） */
    private static final String RECHECK_MARK = "正文复核通过";

    /**
     * **在途伏笔滞留中位数**（2026-10-02 新增）—— `foreshadowSpan` 的**互补指标**。
     *
     * <p><b>为什么必须有它</b>：`foreshadowSpan` 只统计**已回收**的伏笔，于是规划层按指令"养长线"
     * （故意不立刻兑现）时那些伏笔不进分母，指标反而变差（实测 2.23 → 1.90 章）。
     * **只统计闭环事件的指标会惩罚正确的修复**，本指标看的是另一半：长线有没有真的在养。
     *
     * <p><b>两个必须一起看</b>：滞留久既可能是"有意养的长线"，也可能是"被遗忘的线"。
     * 实测 31 条在途里混着两类（ch15「蓝皮书夹层那张纸，来源未明」vs ch3「鸿运科技」12 章没动）。
     * 所以本指标**只报告、不给绝对判定**——它的作用是让"长线"这件事在体检里可见，
     * 具体是不是该收，要看卷末清账的弃置/限期回收裁决。
     *
     * <p>只统计 `resolvable=true` 的条目（见 {@code SeedEntry.resolvable}）：
     * 人物状态/氛围点缀不承担兑现义务，计入只会把在途数注水。
     */
    private void addForeshadowPending(List<BatchHealthReport.Metric> metrics,
                                      List<String> recommendations,
                                      List<ChapterSummaryEntity> chapters,
                                      List<String> voided) {
        int current = chapters.stream()
                .filter(c -> c != null && c.getChapterNo() != null)
                .mapToInt(ChapterSummaryEntity::getChapterNo)
                .max().orElse(0);
        List<ForeshadowSpanPolicy.Pending> pending = ForeshadowSpanPolicy.pending(chapters, current, voided);
        if (pending.isEmpty()) {
            return;
        }
        double median = ForeshadowSpanPolicy.pendingAgeMedian(chapters, current, voided);
        long stale = pending.stream()
                .filter(p -> p.ageChapters() >= FORESHADOW_PENDING_STALE)
                .count();
        BatchHealthReport.Level level = lowerIsBetter(median, FORESHADOW_PENDING_OK, FORESHADOW_PENDING_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("foreshadowPending", "在途伏笔滞留中位数",
                median, "章",
                "≤" + BatchHealthReport.format(FORESHADOW_PENDING_OK) + " 章（埋了未收的伏笔已滞留多久；"
                        + "滞留 ≥" + FORESHADOW_PENDING_STALE + " 章 " + stale + " 条。"
                        + "⚠️ 与 foreshadowSpan 是互补指标：本项偏高说明长线在养，也可能是线被忘了，"
                        + "须配合卷末清账裁决判断；仅统计声明兑现义务的伏笔）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "在途 " + pending.size() + " 条 / 滞留 ≥" + FORESHADOW_PENDING_STALE
                        + " 章 " + stale + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("在途伏笔滞留偏长：中位数 " + BatchHealthReport.format(median)
                    + " 章、滞留 ≥" + FORESHADOW_PENDING_STALE + " 章的有 " + stale
                    + " 条。先分辨是**有意养的长线**还是**被写忘了的线**——"
                    + "核对卷末清账的弃置/限期回收裁决；若是后者，说明规划层缺少"
                    + "「哪一章收哪条线」的安排（伏笔长度反馈只治「埋太快」，治不了「忘了收」）");
        }
    }

    /**
     * **漏收伏笔数**（2026-10-02 新增，P2a）：已过计划回收章但仍未回收的条数。
     *
     * <p>这是"**有意养的长线**"与"**被写忘了的线**"的分界线——现有指标都区分不了这两者。
     * P2b（排期表）就位前 `scheduledPayoffChapter` 恒为 null ⇒ 本指标恒为 0，
     * **属预期惰性**（不报 0 以避免误导：干脆不产生该指标）。
     */
    private void addForeshadowMissed(List<BatchHealthReport.Metric> metrics,
                                     List<String> recommendations,
                                     List<ChapterSummaryEntity> chapters,
                                     List<String> voided) {
        int current = chapters.stream()
                .filter(c -> c != null && c.getChapterNo() != null)
                .mapToInt(ChapterSummaryEntity::getChapterNo)
                .max().orElse(0);
        long missed = ForeshadowSpanPolicy.missedCount(chapters, current, voided);
        long scheduled = ForeshadowSpanPolicy.pending(chapters, current, voided).stream()
                .filter(p -> p.scheduledPayoffChapter() != null)
                .count();
        if (scheduled == 0) {
            // 无排期数据（P2b 未上线）：完全不产生该指标——报 0 会让人误以为"漏收为零"
            return;
        }
        BatchHealthReport.Level level = lowerIsBetter(missed, FORESHADOW_MISSED_OK, FORESHADOW_MISSED_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("foreshadowMissed", "漏收伏笔数",
                missed, "条",
                "≤" + BatchHealthReport.format(FORESHADOW_MISSED_OK)
                        + " 条（已过计划回收章仍未回收；这是「有意养的长线」与「被写忘了的线」的分界线）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "已排期在途 " + scheduled + " 条 / 其中逾期 " + missed + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("漏收伏笔 " + missed + " 条：这些线当初被明确排进了兑现计划、到期却没收。"
                    + "核对规划层是否收到「本段内必须兑现」注入；若注入后仍未收，说明段计划没有把排期当硬约束");
        }
    }

    /**
     * **清账审计**（2026-10-02 新增，P2a）：静默兑现数 —— 账记为未填、实际已被剧情消化的条数。
     *
     * <p><b>为什么这条比它看起来重要</b>：实测两个阶段 19 条 VOID 里约 9–10 条的 reason 写着
     * 「已在第X章兑现/已闭合」——即**剧情收了但 `foreshadowingResolved` 从没记录**。
     * 它是清账弃置的**最大单一来源**，且**直接压低伏笔跨度口径**
     * （「ch9 买书 → ch24 兑现」本是一条 15 章跨度，因漏记而不计入 spans）⇒
     * **实测平均跨度被系统性低估**。本指标是衡量该缺口的唯一入口，
     * 也是后续强化摘要 prompt 的依据。
     */
    private void addSettlementAudit(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ForeshadowSettlementEntity> settlements) {
        if (settlements == null || settlements.isEmpty()) {
            return;
        }
        long silent = ForeshadowSettlementPolicy.silentPayoffCount(settlements);
        long voidCount = ForeshadowSettlementPolicy.voidCount(settlements);
        if (voidCount == 0) {
            return;
        }
        metrics.add(new BatchHealthReport.Metric("foreshadowSilentPayoff", "清账静默兑现数",
                silent, "条",
                "观测项（无绝对阈值：它度量的是**摘要层漏记回收**的规模，而非清账本身的对错）。"
                        + "数值高说明大量伏笔「剧情已消化、账本却没记回收」",
                BatchHealthReport.Direction.LOWER_IS_BETTER,
                silent > 0 ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.OK,
                "静默兑现 " + silent + " / 弃置 " + voidCount));
        if (silent > 0) {
            recommendations.add("清账静默兑现 " + silent + " 条（占弃置 " + voidCount + " 条的 "
                    + Math.round(silent * 100.0 / Math.max(1, voidCount)) + "%）："
                    + "这些伏笔**剧情其实已经收了**，只是摘要没写进 foreshadowingResolved ⇒ "
                    + "**伏笔跨度口径被系统性低估**（真实跨度更长）。"
                    + "先在摘要侧强化「已兑现则必须登记回收」，再读跨度类指标");
        }
    }

    /**
     * 伏笔平均保密跨度（2026-10-01 新增）：埋设到回收的平均章距。
     *
     * <p>治的是"读起来浅"：实测第 1–15 章 13 条回收伏笔的跨度为 1 章 ×8、2 章 ×2、3 ×1、
     * 6 ×1、8 ×1，平均 2.23 章、77% 在 2 章内兑现——大量"后天登门"式的约定伏笔，
     * 埋下去立刻收，读者来不及惦记。**没有这个指标就看不见这个问题**（对白/账本/候选全都正常）。
     *
     * <p>观测口径与规划层反馈同源（{@link ForeshadowSpanPolicy}）：本方法只报告，
     * 真正治本的是 {@code ChapterPlanPromptService.appendForeshadowSpanFeedback} 把信号回灌给规划者。
     *
     * <p>无回收样本（新书前几章）时**跳过指标**，不报 0——欠采样下比例没有意义。
     */
    private void addForeshadowSpan(List<BatchHealthReport.Metric> metrics,
                                   List<String> recommendations,
                                   List<ChapterSummaryEntity> chapters,
                                   List<String> voided) {
        // 防伪线先于指标本身（2026-10-02）：寿命指标只统计 resolvable=TRUE 的条目，
        // 若模型大面积漏标，样本会被掏空——"指标消失"与"伏笔变健康"在报告上长得一模一样。
        // 所以未标注占比必须**先报**，让人知道该不该相信紧随其后的寿命数字。
        double unlabeled = ForeshadowSpanPolicy.unlabeledRate(chapters);
        if (unlabeled > 0) {
            metrics.add(new BatchHealthReport.Metric("foreshadowUnlabeled", "伏笔未标注占比",
                    unlabeled * 100, "%",
                    "≤" + pct(FORESHADOW_UNLABELED_OK_TOLERANCE) + "（resolvable 字段未填的比例；"
                            + "本项偏高时寿命指标不可信——它只统计已声明兑现义务的伏笔，"
                            + "样本被掏空会让指标静默失效）",
                    BatchHealthReport.Direction.LOWER_IS_BETTER,
                    unlabeled > FORESHADOW_UNLABELED_OK_TOLERANCE
                            ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.OK,
                    "未标注 " + BatchHealthReport.format(unlabeled * 100) + "%"));
            if (unlabeled > FORESHADOW_UNLABELED_OK_TOLERANCE) {
                recommendations.add("伏笔未标注占比 " + BatchHealthReport.format(unlabeled * 100)
                        + "%：`resolvable` 字段没填全，寿命/在途两项指标都只统计已标注条目，"
                        + "样本已被掏空——**先修标注再读那两个指标**（老故事的历史数据必然全为未标注，"
                        + "属已知情况；若新章也高，说明摘要 prompt 未生效）");
            }
        }
        List<ForeshadowSpanPolicy.Span> spans = ForeshadowSpanPolicy.spans(chapters, voided);
        if (spans.size() < ForeshadowSpanPolicy.MIN_SAMPLES_FOR_FEEDBACK) {
            // 样本不足：跳过而非报 0，避免"新书前几章"被误判为 0 跨度
            return;
        }
        double avg = spans.stream().mapToInt(ForeshadowSpanPolicy.Span::chapters).average().orElse(0.0);
        int shortCount = (int) spans.stream().filter(ForeshadowSpanPolicy.Span::shortLived).count();
        double shortRate = shortCount / (double) spans.size();
        BatchHealthReport.Level level =
                higherIsBetter(avg, FORESHADOW_SPAN_OK, FORESHADOW_SPAN_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("foreshadowSpan", "伏笔平均保密跨度",
                avg, "章",
                "≥" + BatchHealthReport.format(FORESHADOW_SPAN_OK) + " 章"
                        + "（埋设到回收的平均章距；本批短命伏笔占比 " + pct(shortRate)
                        + "，判定线 <" + ForeshadowSpanPolicy.SHORT_SPAN_CHAPTERS
                        + " 章；无历史基线，须校准）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "样本 " + spans.size() + " 条回收 / 短命 " + shortCount + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("伏笔寿命过短：平均 " + BatchHealthReport.format(avg)
                    + " 章、短命占比 " + pct(shortRate)
                    + "——伏笔埋下去立刻收，读者来不及惦记，悬念没有重量。"
                    + "核对规划层的【伏笔长度反馈】是否已注入（它是唯一的治本通道），"
                    + "不要再让计划把兑现日写死在埋设章里");
        }
    }


    /**
     * 主线最长停留 = 连续停留在同一悬念档位的最大章数（2026-09-22 新增）。
     *
     * <p>治的是"原地转圈"：实测一整批 6 章主线零推进，每章都是"发现线索 → 自我否定 → 回到原点"，
     * 而数据指标（对白/候选/账本）全都正常——**没有这个指标就看不见结构性问题**。
     *
     * <p>判据与计划闸门 {@code SuspenseLadderPolicy} **同源**（同一份实现）：
     * 只有某个"连续同档段"以**非过渡章**收尾时才算数（过渡章本就是蓄势）。
     *
     * <p>⚠️ 档位表是阶段蓝图针对本书生成的，因此比较只在**同一档位表内**进行
     * （一批可能跨多个阶段、各表不同，跨表比下标无意义）。
     * 数据不足（无档位记录 / 同一表内不足 3 章）时**跳过指标**，不报 0。
     */
    private void addSuspenseHold(List<BatchHealthReport.Metric> metrics,
                                 List<String> recommendations,
                                 List<ChapterSummaryEntity> chapters,
                                 List<StageBlueprintEntity> blueprints) {
        if (chapters == null || chapters.isEmpty() || blueprints == null || blueprints.isEmpty()) {
            return;
        }
        Map<String, List<SuspenseLadderPolicy.Beat>> groups = new LinkedHashMap<>();
        Map<String, List<String>> ladders = new LinkedHashMap<>();
        for (ChapterSummaryEntity summary : chapters) {
            if (summary == null || summary.getChapterNo() == null
                    || StringUtils.isBlank(summary.getSuspenseBeat())) {
                continue;
            }
            List<String> ladder = ladderOf(blueprints, summary.getChapterNo());
            if (!SuspenseLadderPolicy.usable(ladder)) {
                continue;
            }
            String key = String.join("\u0001", ladder);
            ladders.putIfAbsent(key, ladder);
            groups.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new SuspenseLadderPolicy.Beat(summary.getChapterNo(), summary.getSuspenseBeat(),
                            "TRANSITION".equalsIgnoreCase(StringUtils.defaultString(summary.getChapterType()))));
        }
        int total = groups.values().stream().mapToInt(List::size).sum();
        if (total < SuspenseLadderPolicy.HOLD_LIMIT) {
            return;
        }
        SuspenseLadderPolicy.HoldRun worst = new SuspenseLadderPolicy.HoldRun(0, 0, 0);
        for (Map.Entry<String, List<SuspenseLadderPolicy.Beat>> entry : groups.entrySet()) {
            SuspenseLadderPolicy.HoldRun run =
                    SuspenseLadderPolicy.maxHold(entry.getValue(), ladders.get(entry.getKey()));
            if (run.length() > worst.length()) {
                worst = run;
            }
        }
        BatchHealthReport.Level level = lowerIsBetter(worst.length(), SUSPENSE_HOLD_OK, SUSPENSE_HOLD_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("suspenseHold", "主线最长停留",
                worst.length(), "章",
                "≤2 章（连续停留在同一悬念档位的最大章数；≥3 即与计划闸门同源的违规，无历史基线）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                worst.length() <= 1 ? "无停留（主线逐章推进）"
                        : "最长停留 " + worst.length() + " 章（第 " + worst.startChapterNo()
                        + "-" + worst.endChapterNo() + " 章）"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("主线停留过长：连续 " + worst.length() + " 章停在同一悬念档位——"
                    + "「发现线索 → 自我否定 → 回到原点」不算推进，核对规划层 suspenseBeat 是否真的在晋升");
        }
    }

    /** 取覆盖该章的阶段蓝图所带的档位表（可能是不同阶段的不同表） */
    private static List<String> ladderOf(List<StageBlueprintEntity> blueprints, int chapterNo) {
        for (StageBlueprintEntity blueprint : blueprints) {
            if (blueprint == null || blueprint.getStartChapter() == null || blueprint.getEndChapter() == null) {
                continue;
            }
            if (chapterNo >= blueprint.getStartChapter() && chapterNo <= blueprint.getEndChapter()
                    && SuspenseLadderPolicy.usable(blueprint.getSuspenseLadder())) {
                return blueprint.getSuspenseLadder();
            }
        }
        return null;
    }

    /**
     * 账本完整度 —— **分档计权**（2026-09-22 重新定义）。
     *
     * <p><b>改之前的口径</b>：已入账 /（已入账 + 挂起）。问题在于"已入账"里混着**放宽档**
     * （SPREAD / ANCHORED / 裁决档）——它们的证据是宽松匹配得来的，可信度低于逐字命中的严格档，
     * 却和它们**同权计入**。于是指标会虚高：实测出现过 94% 的完整度背后藏着 12% 的放宽档。
     *
     * <p><b>现口径</b>：{@code (严格入账 + 0.5 × 放宽入账) / (严格入账 + 放宽入账 + 挂起)}，
     * 衡量的是**账本的可信度**而不是条目数量。改造前"唯一单调恶化项"的含义不变
     * （挂起越攒越多而账本越来越空，是所有"账本与正文矛盾"的根）。
     */
    private void addLedgerCompleteness(List<BatchHealthReport.Metric> metrics,
                                       List<String> recommendations,
                                       List<ChapterSummaryEntity> chapters) {
        long strict = 0;
        long loose = 0;
        long pending = 0;
        for (ChapterSummaryEntity s : chapters) {
            for (List<ChapterSummaryEntity.StateEntry> entries : List.of(
                    safe(s.getCharacterStates()), safe(s.getItemStates()), safe(s.getFactionStates()))) {
                for (ChapterSummaryEntity.StateEntry entry : entries) {
                    if (entry == null) {
                        continue;
                    }
                    if (isLooseTier(entry.getEvidenceTier())) {
                        loose++;
                    } else {
                        strict++;
                    }
                }
            }
            for (ChapterSummaryEntity.ConsistencyFact fact : safe(s.getConsistencyFacts())) {
                if (fact == null) {
                    continue;
                }
                if (isLooseTier(fact.getEvidenceTier())) {
                    loose++;
                } else {
                    strict++;
                }
            }
            pending += size(s.getPendingFacts()) + size(s.getPendingConsistencyFacts());
        }
        long total = strict + loose + pending;
        double weighted = strict + LOOSE_WEIGHT * loose;
        double rate = total == 0 ? 1.0 : weighted / total;
        BatchHealthReport.Level level = higherIsBetter(rate, LEDGER_OK, LEDGER_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("ledgerCompleteness", "账本完整度",
                rate * 100, "%",
                "≥" + pct(LEDGER_OK) + "（改造前基线 65.2%；分档计权：放宽档按 0.5 计）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "严格 " + strict + " / 放宽 " + loose + " / 挂起 " + pending));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("账本挂起存量偏高：确认挂起裁决通道是否开启（story.ledger-adjudicate.enabled），"
                    + "并核对 EvidenceMatch 各档位计数日志——若挂起集中在 phrase-partial，说明机械档已到极限、需靠裁决层");
        }
    }

    /**
     * 每章新地点率 = 首次出现的地点总数 / 有地点记录的章数。
     * 改造前 0.83（10 章窗口内中位 10 个不同地点 = 每章都换新场景，读者无法建立空间感）。
     */
    private void addNewPlaceRate(List<BatchHealthReport.Metric> metrics,
                                 List<String> recommendations,
                                 List<ChapterSummaryEntity> chapters) {
        // 按**字面**统计地点，不做同义归并（2026-09-29 实测结论）：曾尝试按公共子串把变体名归并，
        // 但在 162 章基线上把「九渊剑冢」建筑群下 33 个子区域（外围冰瀑下 / 最高处葬剑台 /
        // 核心虚空内 / 前往途中…）全部吞并成一个地点——"同一建筑群的不同子区域"与"同一处的
        // 不同叫法"字面不可分，归并会把真实换场抹平、让本指标失真（比虚高更危险）。
        // 命名不一致改为在规划层源头约束（见 PlaceTrajectoryPolicy 的命名要求）。
        Set<String> seen = new HashSet<>();
        int withPlace = 0;
        int newPlaces = 0;
        for (ChapterSummaryEntity s : chapters) {
            String place = PlaceTrajectoryPolicy.placeOf(s);
            if (place == null) {
                continue;
            }
            withPlace++;
            if (seen.add(place)) {
                newPlaces++;
            }
        }
        if (withPlace == 0) {
            return;
        }
        double rate = (double) newPlaces / withPlace;
        BatchHealthReport.Level level = lowerIsBetter(rate, NEW_PLACE_OK, NEW_PLACE_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("newPlaceRate", "每章新地点率",
                rate, "个/章", "≤" + BatchHealthReport.format(NEW_PLACE_OK) + "（改造前基线 0.83）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "新地点 " + newPlaces + " / 有地点记录 " + withPlace + " 章，全部地点 " + seen.size() + " 个"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("地点密度过高：规划层的【地点轨迹】块应已回灌（PlaceTrajectoryPolicy），"
                    + "检查该块是否被注入——若注入后仍高，说明阶段窗口太短、本阶段无复用余地");
        }
    }

    /**
     * 过渡章占比（区间型指标）：太低说明没有节奏留白（改造前恒为 0，因为章型无法声明），
     * 太高说明节奏偏松。区间内的两端都要判，单侧阈值会漏掉"完全不用 transition"这一现状。
     */
    private void addTransitionShare(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ChapterSummaryEntity> chapters) {
        long transition = chapters.stream()
                .filter(s -> ChapterTypeVO.of(s.getChapterType()).isTransition())
                .count();
        double share = (double) transition / chapters.size();
        BatchHealthReport.Level level;
        if (share > TRANSITION_MAX_DEGRADED || share < TRANSITION_MIN_DEGRADED) {
            level = BatchHealthReport.Level.DEGRADED;
        } else if (share > TRANSITION_MAX_OK || share < TRANSITION_MIN_OK) {
            level = BatchHealthReport.Level.WATCH;
        } else {
            level = BatchHealthReport.Level.OK;
        }
        metrics.add(new BatchHealthReport.Metric("transitionShare", "过渡章占比",
                share * 100, "%", pct(TRANSITION_MIN_OK) + "–" + pct(TRANSITION_MAX_OK) + "（改造前恒为 0）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                "过渡章 " + transition + " / " + chapters.size() + " 章"));
        if (share < TRANSITION_MIN_OK) {
            recommendations.add("没有过渡章：章型已新增 transition，若占比仍为 0，"
                    + "说明规划层未采纳新章型——核对规划 prompt 的 chapterType 指引与解析侧是否吞掉了该值");
        } else if (share > TRANSITION_MAX_OK) {
            recommendations.add("过渡章占比偏高：节奏偏松，检查规划层过渡章配额（≤1/3、不得连续 >2 章）是否被遵守");
        }
    }

    /**
     * 最长连续过渡章段：过渡章的另一条硬约束（不得连续 &gt; 2 章）此前只写在规划 prompt 里，
     * 观测层只统计总占比——于是"12 章里连续 3 个过渡章、总占比 25%"这种节奏塌陷完全不可见
     * （占比达标 ≠ 分布合理，单看占比会漏掉连续段的集中塌陷）。
     *
     * <p>阈值与规划层声明的约束对齐：≤2 章为 OK（即未违反约束），3 章为 WATCH（已越线一次），
     * ≥4 章为 DEGRADED（连续多段越线，节奏已塌）。
     *
     * <p>过渡章的第三条约束「每章至少一处关系/信息/资源变化」是语义判断，
     * 机械层无法核验，仍依赖规划 prompt 与审校——此处只做可机械化的部分。
     */
    private void addTransitionRun(List<BatchHealthReport.Metric> metrics,
                                  List<String> recommendations,
                                  List<ChapterSummaryEntity> chapters) {
        int maxRun = 0;
        int run = 0;
        int chapterNo = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (ChapterTypeVO.of(s.getChapterType()).isTransition()) {
                run++;
                if (run > maxRun) {
                    maxRun = run;
                    chapterNo = s.getChapterNo() == null ? 0 : s.getChapterNo();
                }
            } else {
                run = 0;
            }
        }
        BatchHealthReport.Level level = maxRun <= TRANSITION_RUN_OK ? BatchHealthReport.Level.OK
                : (maxRun >= TRANSITION_RUN_DEGRADED ? BatchHealthReport.Level.DEGRADED
                        : BatchHealthReport.Level.WATCH);
        metrics.add(new BatchHealthReport.Metric("maxTransitionRun", "最长连续过渡章",
                maxRun, "章", "≤" + TRANSITION_RUN_OK + " 章（规划约束：不得连续超过 2 章）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                maxRun == 0 ? "本样本无过渡章（见过渡章占比指标）"
                        : "最长一段为第 " + (chapterNo - maxRun + 1) + "-" + chapterNo + " 章"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("过渡章连续段越线：占比达标不代表分布合理，连续 3 章以上过渡会让中段失速。"
                    + "检查规划层的过渡章排布——若同一阶段反复出现，多半是阶段任务供给不足、"
                    + "模型用过渡章拖进度");
        }
    }

    /** 低密度章占比（排除过渡章——过渡章本就是有意留白，计入会逼规划层给留白也塞满事件） */    private void addLowDensityShare(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ChapterSummaryEntity> chapters) {
        int counted = 0;
        int low = 0;
        for (ChapterSummaryEntity s : chapters) {
            if (s.getValidChars() == null || ChapterTypeVO.of(s.getChapterType()).isTransition()) {
                continue;
            }
            counted++;
            if (s.getValidChars() < LOW_DENSITY_CHARS) {
                low++;
            }
        }
        if (counted == 0) {
            return;
        }
        double share = (double) low / counted;
        BatchHealthReport.Level level = lowerIsBetter(share, LOW_DENSITY_OK, LOW_DENSITY_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("lowDensityShare", "低密度章占比",
                share * 100, "%", "≤" + pct(LOW_DENSITY_OK) + "（非过渡章中有效字 <" + LOW_DENSITY_CHARS + " 的比例）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                low + " / " + counted + " 章（已排除过渡章）"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("短章偏多：这是规划层「计划供给不足」，不是正文层问题——"
                    + "核对密度反馈块是否已注入，以及本段计划的关键事件是否给足");
        }
    }

    /** 未核销质量债：债是跨章回灌信号，积压说明同类问题反复复发没被治住 */
    private void addUnresolvedDebt(List<BatchHealthReport.Metric> metrics,
                                   List<String> recommendations,
                                   List<QualityDebtEntity> debts) {
        long unresolved = debts == null ? 0 : debts.stream()
                .filter(d -> d != null && !d.isResolved())
                .count();
        BatchHealthReport.Level level = unresolved <= DEBT_OK ? BatchHealthReport.Level.OK
                : (unresolved > DEBT_DEGRADED ? BatchHealthReport.Level.DEGRADED : BatchHealthReport.Level.WATCH);
        int total = debts == null ? 0 : debts.size();
        metrics.add(new BatchHealthReport.Metric("unresolvedDebt", "未核销质量债",
                unresolved, "条", "≤" + DEBT_OK + " 条", BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "未核销 " + unresolved + " / 累计 " + total + " 笔"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("质量债积压：核对债的维度分布（QualityDebtEntity.issues 的 dimension），"
                    + "确认同维度连续复发是否被 settle 正确核销；若集中在本批新增维度，多半是新闸门刚上线");
        }
    }

    /** 阶段出口条件达成率：改造前仅 22%（含复合谓词整体判定与"永不退场"两重原因） */
    private void addExitConditionRate(List<BatchHealthReport.Metric> metrics,
                                      List<String> recommendations,
                                      List<StageBlueprintEntity> blueprints) {
        if (blueprints == null || blueprints.isEmpty()) {
            return;
        }
        int conditions = 0;
        int met = 0;
        int atoms = 0;
        int metAtoms = 0;
        int writtenOff = 0;
        for (StageBlueprintEntity stage : blueprints) {
            if (stage == null) {
                continue;
            }
            if (stage.getExitResults() != null) {
                for (StageBlueprintEntity.ExitConditionResult r : stage.getExitResults()) {
                    if (r == null) {
                        continue;
                    }
                    conditions++;
                    if (Boolean.TRUE.equals(r.getMet())) {
                        met++;
                    }
                    if (r.getTotalAtoms() != null) {
                        atoms += r.getTotalAtoms();
                        metAtoms += r.getMetAtoms() == null ? 0 : r.getMetAtoms();
                    }
                }
            }
            if (stage.getCarriedTasks() != null) {
                writtenOff += (int) stage.getCarriedTasks().stream()
                        .filter(t -> t != null && t.getContent() != null && t.getContent().contains("出账"))
                        .count();
            }
        }
        if (conditions == 0) {
            return;
        }
        double rate = (double) met / conditions;
        BatchHealthReport.Level level = higherIsBetter(rate, EXIT_OK, EXIT_DEGRADED);
        String atomDetail = atoms > 0
                ? "；原子进度 " + metAtoms + "/" + atoms + "（原子化后新增口径）"
                : "；原子进度暂无数据（老蓝图）";
        metrics.add(new BatchHealthReport.Metric("exitConditionRate", "出口条件达成率",
                rate * 100, "%", "≥" + pct(EXIT_OK) + "（改造前基线 22%）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                met + " / " + conditions + " 条" + atomDetail + "，机械出账 " + writtenOff + " 条"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("出口条件达成率偏低：确认条件已按分句核验（原子化后 {metAtoms}/{totalAtoms} 应可见）——"
                    + "若仍大量整条未达成，检查规划侧是否又把复合谓词写回；机械出账数上升是退场规则在收尾，属正常");
        }
    }

    /**
     * 放宽档入账占比 = 留痕档（spread/anchored）+ 裁决档（adjudicated）条目 / 全部已入账条目。
     * 反编造门放宽是为了救回真事实，但放宽比例过高就等于门形同虚设——这是入账门改造的刹车表。
     *
     * <p><b>口径必须覆盖一致性事实</b>（2026-09-16 修正）：一致性事实同样过 EvidenceMatch、
     * 同样可能有 spread/anchored/adjudicated 档位（{@code ConsistencyFact.evidenceTier}）。
     * 只统计三本状态账会漏报——极端情况下状态账全是 exact、一致性事实全是 adjudicated，
     * 本指标仍显示"很健康"，而账本完整度指标（已把一致性事实计入 kept）却显示很差，
     * 两个口径互相矛盾会让体检失去可信度。
     */
    private void addLooseTierShare(List<BatchHealthReport.Metric> metrics,
                                   List<String> recommendations,
                                   List<ChapterSummaryEntity> chapters) {
        int total = 0;
        int loose = 0;
        for (ChapterSummaryEntity s : chapters) {
            for (List<ChapterSummaryEntity.StateEntry> entries : List.of(
                    safe(s.getCharacterStates()), safe(s.getItemStates()), safe(s.getFactionStates()))) {
                for (ChapterSummaryEntity.StateEntry entry : entries) {
                    if (entry == null) {
                        continue;
                    }
                    total++;
                    if (isLooseTier(entry.getEvidenceTier())) {
                        loose++;
                    }
                }
            }
            for (ChapterSummaryEntity.ConsistencyFact fact : safe(s.getConsistencyFacts())) {
                if (fact == null) {
                    continue;
                }
                total++;
                if (isLooseTier(fact.getEvidenceTier())) {
                    loose++;
                }
            }
        }
        if (total == 0) {
            return;
        }
        double share = (double) loose / total;
        BatchHealthReport.Level level = lowerIsBetter(share, LOOSE_OK, LOOSE_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("looseTierShare", "放宽档入账占比",
                share * 100, "%", "≤" + pct(LOOSE_OK) + "（留痕档 + 裁决档占已入账条目比例，含一致性事实）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                loose + " / " + total + " 条（三账本状态 + 一致性事实）"));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("放宽档占比偏高：反编造门被放宽的比例过大，需复核 EvidenceMatch 的"
                    + "集中度与锚定阈值——放宽过头的代价是编造事实进账本，比缺账更难回滚");
        }
    }

    /** 是否为"放宽入账"档位：留痕档（spread/anchored）或裁决档（adjudicated） */
    private static boolean isLooseTier(String tier) {
        return tier != null && (tier.startsWith("spread") || tier.startsWith("anchored")
                || tier.startsWith("adjudicated"));
    }

    /**
     * 候选链路两项指标（2026-09-16 三章实测后补）：
     * 触发率 = 触发/章数（历史基线 19%，本批 100%）；采纳率 = 采纳/触发（历史 48%，本批 67%）。
     * **判定"白烧"的标准是采纳率而不是触发率**——所以两个都要看：
     * 触发率异常升高说明审校 MINOR 变多，采纳率下滑才说明重写真的在浪费。
     */
    private void addCandidateMetrics(List<BatchHealthReport.Metric> metrics,
                                     List<String> recommendations,
                                     int chapterCount,
                                     cn.novel.yonren.domain.novel.service.armory.candidate.CandidateSampleService.CandidateStats stats) {
        if (stats == null || stats.triggered() <= 0) {
            return;
        }
        double triggerRate = (double) stats.triggered() / Math.max(1, chapterCount);
        BatchHealthReport.Level triggerLevel = lowerIsBetter(triggerRate, CANDIDATE_TRIGGER_OK, CANDIDATE_TRIGGER_DEGRADED);
        metrics.add(new BatchHealthReport.Metric("candidateTriggerRate", "候选选优触发率",
                triggerRate * 100, "%", "≤" + pct(CANDIDATE_TRIGGER_OK) + "（历史基线 19%）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, triggerLevel,
                "触发 " + stats.triggered() + " / " + chapterCount + " 章"));
        if (triggerLevel != BatchHealthReport.Level.OK) {
            recommendations.add("候选选优触发率偏高：审校给出的叙事 MINOR 偏多。"
                    + "注意机械 MINOR（标题/文风/机制）走独立分账、不参与触发——若排除机械因素后仍高，"
                    + "再考虑收紧触发条件（如 minorCount ≥ 2 才触发）");
        }

        if (stats.triggered() > 0) {
            double adoption = (double) stats.adopted() / stats.triggered();
            BatchHealthReport.Level adoptionLevel = higherIsBetter(adoption, CANDIDATE_ADOPTION_OK, CANDIDATE_ADOPTION_DEGRADED);
            metrics.add(new BatchHealthReport.Metric("candidateAdoptionRate", "候选采纳率",
                    adoption * 100, "%", "≥" + pct(CANDIDATE_ADOPTION_OK) + "（历史基线 48%）",
                    BatchHealthReport.Direction.HIGHER_IS_BETTER, adoptionLevel,
                    "采纳 " + stats.adopted() + " / 触发 " + stats.triggered() + " 次"));
            if (adoptionLevel != BatchHealthReport.Level.OK) {
                recommendations.add("候选采纳率偏低：多数重写没有通过盲评，触发条件可能过宽——"
                        + "这是判定候选链路是否在烧钱的**主要**信号，比触发率更值得看");
            }
        }
    }

    /**
     * 对白两维指标：行级占比 + 轮次密度（各章均值，机械统计）。
     *
     * <p><b>为什么要两维</b>（2026-09-23）：占比只回答"引号行多少"，漏掉"占比合格但轮次稀疏"
     * ——都市校园恋爱那本占比 25% 勉强合格，轮次密度 7.4/千字（同批新书 10.0-10.9，全样本中位 9.5），
     * 读者反馈"对话太少、张力不足"。恋爱/智斗的拉扯感来自一来一回的**次数**。
     *
     * <p>阈值按题材切换：对话驱动题材（ROMANCE）更严。题材取自摘要的机械字段 storyGenre
     * （而非方法参数），保证批末日志与 /health 端点口径一致。旧摘要无该字段时退化为基础阈值。
     */
    private void addDialogueMetrics(List<BatchHealthReport.Metric> metrics,
                                    List<String> recommendations,
                                    List<ChapterSummaryEntity> chapters) {
        String genre = chapters.stream()
                .map(ChapterSummaryEntity::getStoryGenre)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        String genreNote = DialogueRatioPolicy.isDialogueDriven(genre) ? "，对话驱动题材从严" : "";

        List<Double> ratios = chapters.stream()
                .map(ChapterSummaryEntity::getDialogueRatio)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!ratios.isEmpty()) {
            double avg = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double min = ratios.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            // 方向：对白占比**越高越健康**（低=滑向主角单机）——这里踩过两次 lowerIsBetter 的坑
            BatchHealthReport.Level level = higherIsBetter(avg,
                    DialogueRatioPolicy.ratioOk(), DialogueRatioPolicy.ratioDegraded());
            metrics.add(new BatchHealthReport.Metric("dialogueRatio", "对白行占比",
                    avg * 100, "%", "≥" + pct(DialogueRatioPolicy.ratioOk())
                    + "（旧书 45.6%，独白坍缩本 11%" + genreNote + "）",
                    BatchHealthReport.Direction.HIGHER_IS_BETTER, level,
                    String.format("均值 %.1f%%，最低一章 %.1f%%（%d 章有数据）", avg * 100, min * 100, ratios.size())));
            if (level != BatchHealthReport.Level.OK) {
                recommendations.add("对白占比过低：正文滑向「主角单机」——全是内心独白与推演，缺少双向互动。"
                        + "修复靠规划层的人物供给规则（规则 8：非主角主动发起的事件、双向互动、代价多样化）"
                        + "与审校 character 维度的工具人检查");
            }
        }

        List<Double> densities = new java.util.ArrayList<>();
        for (ChapterSummaryEntity s : chapters) {
            if (s.getDialogueUtterances() == null || s.getValidChars() == null || s.getValidChars() <= 0) {
                continue;
            }
            densities.add(DialogueRatioPolicy.utteranceDensity(s.getDialogueUtterances(), s.getValidChars()));
        }
        if (densities.isEmpty()) {
            return;
        }
        double avgDensity = densities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double minDensity = densities.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        // 方向同上：轮次密度越高越健康
        BatchHealthReport.Level densityLevel = higherIsBetter(avgDensity,
                DialogueRatioPolicy.densityOk(genre), DialogueRatioPolicy.densityDegraded(genre));
        metrics.add(new BatchHealthReport.Metric("dialogueDensity", "对白轮次密度",
                avgDensity, "次/千字", "≥" + trim(DialogueRatioPolicy.densityOk(genre))
                + "（全样本中位 9.5，同批新书 10.0-10.9" + genreNote + "）",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, densityLevel,
                String.format("均值 %.1f 次/千字，最低一章 %.1f（%d 章有数据）",
                        avgDensity, minDensity, densities.size())));
        if (densityLevel != BatchHealthReport.Level.OK) {
            recommendations.add("对白轮次密度不足：占比可能合格但都是一来一回偏少的**长对白段**。"
                    + "恋爱/智斗题材的拉扯感来自高频短交锋（3-5 字一句、一来一回十几轮）——"
                    + "规划层应把对白写成多次短往返，而不是少数长段落");
        }
    }

    /** 整数阈值去掉小数尾巴（8.0 → 8），小数阈值保留一位 */
    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format("%.1f", v);
    }

    /** 总体分级：取各项最差；劣化项达到 CRITICAL_METRIC_COUNT 项升级为 CRITICAL */
    /**
     * 阶段规划覆盖（2026-10-05 新增）：最新蓝图终点是否覆盖已写末章。
     *
     * <p><b>为什么显式进体检</b>：蓝图生成/解析失败走 fail-soft 后，批次会**静默**在旧蓝图上滑行——
     * 无新任务、无出口条件审计、无排期（36-40 章实测：排期 71 条原地踏步，第 8 阶段规划整体缺位）。
     * 此前只有一条易被淹没的 WARN；进体检后 DEGRADED 会把批末体检顶成 WARN 级输出，
     * 让"本批规划缺位"与字数/密度问题同台可见。
     */
    private void addStagePlanningCoverage(List<BatchHealthReport.Metric> metrics,
                                          List<String> recommendations,
                                          List<ChapterSummaryEntity> chapters,
                                          List<StageBlueprintEntity> blueprints) {
        int writtenEnd = chapters.isEmpty() ? 0 : chapters.get(chapters.size() - 1).getChapterNo();
        Integer coveredEnd = null;
        if (blueprints != null) {
            for (StageBlueprintEntity b : blueprints) {
                if (b != null && b.getEndChapter() != null) {
                    coveredEnd = coveredEnd == null ? b.getEndChapter() : Math.max(coveredEnd, b.getEndChapter());
                }
            }
        }
        if (coveredEnd == null) {
            // 从未启用蓝图的故事（老故事/无蓝图模式）不算降级——本闸针对"蓝图链存在但止点落后"：
            // 启用过蓝图的批次突然失去规划覆盖，才是需要点名的异常
            return;
        }
        boolean covered = coveredEnd >= writtenEnd;
        BatchHealthReport.Level level = covered ? BatchHealthReport.Level.OK : BatchHealthReport.Level.DEGRADED;
        String detail = covered
                ? "最新蓝图止于第 " + coveredEnd + " 章，已覆盖本批末章"
                : "最新蓝图止于第 " + coveredEnd + " 章，本批第 " + (coveredEnd + 1) + "-"
                        + writtenEnd + " 章在旧蓝图下生成（新任务/退出条件/排期未生成）";
        if (!covered) {
            recommendations.add("阶段规划缺位：蓝图生成/解析失败已降级（fail-soft），第 " + (coveredEnd + 1) + "-"
                    + writtenEnd + " 章沿用旧蓝图——本批的任务、出口条件审计与新排期均未发生。"
                    + "建议补跑一次阶段蓝图（或人工核对本段剧情走向是否仍服从旧蓝图）后再续写后续章段。");
        }
        metrics.add(new BatchHealthReport.Metric("stagePlanningCoverage", "阶段规划覆盖",
                coveredEnd, "章",
                "≥ 已写末章（" + writtenEnd + "）——蓝图止点落后即为本批在旧/无蓝图下滑行",
                BatchHealthReport.Direction.HIGHER_IS_BETTER, level, detail));
    }

    /**
     * 进度对齐（2026-10-05 新增）：大纲（chapterGoal）按章段预算的时间标记 vs 时序锚实际值。
     *
     * <p><b>背景</b>：新书 45 章只讲了大纲预算前 10 章的内容（时序锚停在 2002-07/4 岁，
     * 大纲 ch41-50 预算 9 岁/2007 年）——结构上没有任何机制看得到这个漂移，本指标让它
     * 每批可见：滞后段数 ≥1 WATCH、≥2 DEGRADED，并给出"补齐/改大纲"两条出路。
     *
     * <p><b>通用性</b>：chapterGoal 无结构（散文大纲）或解析为空时豁免；段无数值时间标记
     * （如奇幻的境界纪年）时同样豁免——只对可数值比较的年份/年龄报滞后，不臆造。
     */
    private void addOutlinePacing(List<BatchHealthReport.Metric> metrics,
                                  List<String> recommendations,
                                  List<ChapterSummaryEntity> chapters,
                                  String chapterGoal) {
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(chapterGoal);
        if (outline.isEmpty()) {
            return;
        }
        int writtenEnd = chapters.isEmpty() ? 0 : chapters.get(chapters.size() - 1).getChapterNo();
        OutlineSegmentParser.OutlineSegment budget = outline.segmentFor(writtenEnd);
        if (budget == null) {
            // 已写出大纲覆盖范围：超纲是结构性事件，比滞后更严重
            metrics.add(new BatchHealthReport.Metric("outlinePacing", "进度对齐",
                    writtenEnd, "章",
                    "≤ 大纲覆盖末章（" + outline.segments().get(outline.segments().size() - 1).endChapter() + "）",
                    BatchHealthReport.Direction.LOWER_IS_BETTER, BatchHealthReport.Level.DEGRADED,
                    "已写到第 " + writtenEnd + " 章，超出大纲覆盖范围——章节预算与实际进度彻底脱钩，"
                            + "必须重排大纲或收束剧情"));
            recommendations.add("进度超纲：已写到第 " + writtenEnd + " 章而大纲只预算到 "
                    + outline.segments().get(outline.segments().size() - 1).endChapter()
                    + " 章——重排大纲章段预算，或让剧情向大纲末段收束，二选一。");
            return;
        }
        // 实际时间标记：最新摘要的年龄事实 + 最新 timePoint 的年份（与时序锚渲染同源）
        Integer actualAge = null;
        Integer actualYear = null;
        for (int i = chapters.size() - 1; i >= 0; i--) {
            ChapterSummaryEntity s = chapters.get(i);
            if (s == null) {
                continue;
            }
            if (actualAge == null && s.getConsistencyFacts() != null) {
                for (ChapterSummaryEntity.ConsistencyFact f : s.getConsistencyFacts()) {
                    if (f == null || f.getType() == null || !"NUMBER".equalsIgnoreCase(f.getType().trim())) {
                        continue;
                    }
                    String subj = StringUtils.defaultString(f.getSubject());
                    if (!subj.contains("年龄") && !subj.contains("月龄") && !subj.contains("岁")) {
                        continue;
                    }
                    Integer parsed = parseAgeValue(f.getValue());
                    if (parsed != null) {
                        actualAge = parsed;
                        break;
                    }
                }
            }
            if (actualYear == null && s.getTimePoint() != null) {
                java.util.regex.Matcher ym = java.util.regex.Pattern.compile("(\\d{4})年").matcher(s.getTimePoint());
                if (ym.find()) {
                    actualYear = Integer.parseInt(ym.group(1));
                }
            }
            if (actualAge != null && actualYear != null) {
                break;
            }
        }

        // 滞后计算：可数值比较的维度取 max（年龄滞后 / 年份滞后），无量纲维度豁免
        int ageLag = (budget.ageEnd() != null && actualAge != null)
                ? Math.max(0, budget.ageEnd() - actualAge) : -1;
        int yearLag = (budget.yearStart() != null && actualYear != null)
                ? Math.max(0, budget.yearStart() - actualYear) : -1;
        if (ageLag < 0 && yearLag < 0) {
            return; // 该段无数值时间标记（境界纪年等）——不臆造，豁免
        }
        int lag = Math.max(ageLag, yearLag);
        String lagDesc = (ageLag >= 0 ? "年龄预算 " + budget.ageEnd() + " 岁 vs 实际 " + actualAge + " 岁" : "")
                + (ageLag >= 0 && yearLag >= 0 ? "；" : "")
                + (yearLag >= 0 ? "年份预算 " + budget.yearStart() + " vs 实际 " + actualYear : "");
        BatchHealthReport.Level level = lag >= 3 ? BatchHealthReport.Level.DEGRADED
                : lag >= 1 ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.OK;
        metrics.add(new BatchHealthReport.Metric("outlinePacing", "进度对齐",
                lag, "段", "≤1 段（大纲章段预算的时间标记 vs 时序锚实际值；滞后即正文落后于大纲路标）",
                BatchHealthReport.Direction.LOWER_IS_BETTER, level,
                "第 " + writtenEnd + " 章按大纲应处【" + budget.startChapter() + "-" + budget.endChapter()
                        + "章 " + StringUtils.defaultString(budget.timeLabel()) + "】段｜" + lagDesc));
        if (level != BatchHealthReport.Level.OK) {
            recommendations.add("进度滞后约 " + lag + " 年：大纲把第 " + budget.startChapter() + "-"
                    + budget.endChapter() + " 章预算给了【" + StringUtils.defaultString(budget.timeLabel())
                    + "】段，实际剧情仍在更早时段。两条出路：①规划层加速（跳月/压缩日常，观察后续批次滞后是否收敛）；"
                    + "②承认慢节奏，重排大纲章段预算。不应长期维持现状——路标与正文漂移会越滚越大。");
        }
    }


    /** 年龄值解析：兼容阿拉伯数字（"9岁""14"）与中文数字（"四岁""十一岁"）；"未提及"等返回 null */
    private static Integer parseAgeValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        java.util.regex.Matcher am = java.util.regex.Pattern.compile("(\\d{1,3})\\s*岁?").matcher(value);
        if (am.find()) {
            return Integer.parseInt(am.group(1));
        }
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("([一二三四五六七八九十]{1,3})\\s*岁").matcher(value);
        if (cm.find()) {
            return OutlineSegmentParser.chineseNumeral(cm.group(1));
        }
        return null;
    }

    private BatchHealthReport.Level overall(List<BatchHealthReport.Metric> metrics) {
        BatchHealthReport.Level worst = BatchHealthReport.Level.OK;
        long degraded = 0;
        for (BatchHealthReport.Metric metric : metrics) {
            worst = worst.worse(metric.level());
            if (metric.level() == BatchHealthReport.Level.DEGRADED
                    || metric.level() == BatchHealthReport.Level.CRITICAL) {
                degraded++;
            }
        }
        if (degraded >= CRITICAL_METRIC_COUNT) {
            return BatchHealthReport.Level.CRITICAL;
        }
        return worst;
    }

    /** "越大越好"型分级：≥ok 为 OK，≥degradedLine 为 WATCH，否则 DEGRADED */
    private static BatchHealthReport.Level higherIsBetter(double value, double ok, double degradedLine) {
        if (value >= ok) {
            return BatchHealthReport.Level.OK;
        }
        return value >= degradedLine ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.DEGRADED;
    }

    /** "越小越好"型分级：≤ok 为 OK，≤degradedLine 为 WATCH，否则 DEGRADED */
    private static BatchHealthReport.Level lowerIsBetter(double value, double ok, double degradedLine) {
        if (value <= ok) {
            return BatchHealthReport.Level.OK;
        }
        return value <= degradedLine ? BatchHealthReport.Level.WATCH : BatchHealthReport.Level.DEGRADED;
    }

    private static String pct(double ratio) {
        return BatchHealthReport.format(ratio * 100) + "%";
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
