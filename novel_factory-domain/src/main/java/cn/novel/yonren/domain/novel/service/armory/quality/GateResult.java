package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;

import java.util.List;

/**
 * 单章质量门结论：在"未修复 BLOCKING 清单"之外补充通过质量的分级。
 * 供下游按通过置信度分流——候选选优（低置信通过才触发第二模型族候选）与
 * 文风指纹库（仅首轮全票通过的章节入库）都以 grade 为准。
 *
 * <p><b>三类问题分账（2026-09-16 严重度分层）</b>：
 * <ul>
 *   <li>{@code unresolvedBlocking} —— 修订未启用/异常/轮次耗尽时仍未修复的 BLOCKING，
 *       由上层记质量债并回灌下一章</li>
 *   <li>{@code mechanicalMinor} —— 机械文风门禁降档后的程度性问题（副词密度/眼神套话/
 *       身体套话/章末升华）。<b>不参与 grade 推导</b>：它既不该触发定向修订（阈值型统计指标
 *       在整章重写下不可收敛，实测 110 条修订样本全部两轮耗尽），也不该触发候选选优
 *       （"副词多了点"不值得让第二模型整章重写）。只落质量债供观测统计与规划层回灌治本——
 *       与"字数不足不拒收、改走密度信号回灌"同款处理</li>
 *   <li>{@code minorCount} —— <em>审校 LLM</em> 给出的叙事类 MINOR 残留（说明这章确实写得
 *       不够好，是真正的"低置信通过"信号），参与 grade 推导</li>
 * </ul>
 *
 * @param unresolvedBlocking 未修复的 BLOCKING 问题（修订未启用/异常/轮次耗尽时非空，供上层记质量债）
 * @param grade              通过质量分级
 * @param minorCount         最终一轮审校的叙事类 MINOR 残留条数（来自审校 LLM）
 * @param mechanicalMinor    机械文风门禁产出的 MINOR 问题（降档项，不参与 grade）
 * @param reviseRounds       实际进入的修订轮数（未触发修订为 0）
 * @param semanticMinors     审校的语义 MINOR 清单（人设/审美等）。此前只传条数、清单被丢弃——
 *                           审校每章稳定报出的问题没有任何行动通道（2026-09-29 阅读实测暴露）；
 *                           上层压缩为「上章审校反馈」回灌下一章 prompt
 */
public record GateResult(List<ChapterIssueEntity> unresolvedBlocking, PassGrade grade,
                         int minorCount, List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                         List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded) {

    /** 通过质量分级 */
    public enum PassGrade {
        /** 首轮全票通过：零修订轮次且零 MINOR 残留 */
        CLEAN_PASS,
        /** 首轮通过但有叙事 MINOR 残留（低置信通过） */
        MINOR_RESIDUE,
        /** 经修订闭环后才通过（低置信通过） */
        REVISED_PASS,
        /** 修订耗尽仍有未修复 BLOCKING（质量债） */
        DEBT
    }

    /**
     * 工厂：按未修复 BLOCKING / 修订轮数 / 叙事 MINOR 残留推导分级。
     * 机械文风 MINOR 不传入——它不影响分级。
     */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount, int reviseRounds) {
        return of(unresolvedBlocking, minorCount, List.of(), reviseRounds);
    }

    /**
     * 工厂：带机械文风 MINOR 的重载。
     * <b>grade 只用 minorCount 推导</b>，mechanicalMinor 仅随结果传递供上层记债/统计。
     */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds) {
        return of(unresolvedBlocking, minorCount, mechanicalMinor, reviseRounds, List.of());
    }

    /** 工厂：带语义 MINOR 清单的全参重载（审校反馈回灌的数据源）；未验证标记默认 false */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                                List<ChapterIssueEntity> semanticMinors) {
        return of(unresolvedBlocking, minorCount, mechanicalMinor, reviseRounds, semanticMinors, false);
    }

    /**
     * 工厂：带"修订验证未跑成"标记的全参重载（2026-09-30）。
     *
     * <p>{@code auditVerifyDegraded=true} 表示本轮修订的**验证步骤失败**（网关异常/输出无法解析），
     * 因此 {@code unresolvedBlocking} 的语义是**「未验证」而非「确认未修复」**。
     * 内容侧偏向不变（仍算未通过、不放行未验证的稿），但**调用方不得把它记入质量债**——
     * 质量债会回灌给写手当作"你上一章犯的错"，把基础设施抖动写进去等于给模型下错误指令。
     * 观测侧把它单独计数，与"真债"区分（见 BatchHealthService）。
     */
    public static GateResult of(List<ChapterIssueEntity> unresolvedBlocking, int minorCount,
                                List<ChapterIssueEntity> mechanicalMinor, int reviseRounds,
                                List<ChapterIssueEntity> semanticMinors, boolean auditVerifyDegraded) {
        boolean debt = unresolvedBlocking != null && !unresolvedBlocking.isEmpty();
        PassGrade grade;
        if (debt) {
            grade = PassGrade.DEBT;
        } else if (reviseRounds > 0) {
            grade = PassGrade.REVISED_PASS;
        } else {
            grade = minorCount > 0 ? PassGrade.MINOR_RESIDUE : PassGrade.CLEAN_PASS;
        }
        return new GateResult(debt ? unresolvedBlocking : List.of(), grade, minorCount,
                mechanicalMinor == null ? List.of() : mechanicalMinor, reviseRounds,
                semanticMinors == null ? List.of() : semanticMinors, auditVerifyDegraded);
    }

    /**
     * 低置信通过：**语义分类**（"这章过关得不够干净"），<b>不是候选触发的判定</b>。
     *
     * <p><b>2026-10-02 澄清语义</b>：此前本方法被当作候选选优的触发条件，
     * 于是"DEBT 算不算低置信"这一个问题同时决定了"这章过没过关"与"要不要起候选"两件事——
     * 两者共用一个谓词，是当时 DEBT 被漏掉的根因。
     * 现在触发判定独立在 {@code ChapterCandidateService.triggered()}，由
     * {@code CandidateProperties} 的三个开关控制（实测采纳率：REVISED_PASS 66.7% &gt;
     * MINOR_RESIDUE 42.9% &gt; DEBT 0%，故后两者默认关闭）。
     *
     * <p>本方法只回答"这章的通过置信度是否偏低"，保留 {@code DEBT} 是**语义正确**的
     * （它不是通过）——但"低置信"不蕴含"值得花第二模型族重写一遍"。
     */
    public boolean isLowConfidencePass() {
        return grade == PassGrade.MINOR_RESIDUE || grade == PassGrade.REVISED_PASS
                || grade == PassGrade.DEBT;
    }
}
