package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 终局审查实体：收官门禁（isStoryComplete）触发前后，独立审计对照四类全书级资产核验
 * 完结质量——终局承诺、未回收伏笔、主要角色命运、灾后世界状态。
 * 判定严谨：任一维不通过即拒绝宣告完结并写回末卷返工；返工有界（reworkCount 重试预算
 * + sticky cap 顶帽头寸双护栏），预算/头寸耗尽被迫收官时 forcedClose=true 但 overallPass
 * 保留 false（不伪称通过），随末阶段蓝图 rolling-outline.json 落盘供人工复盘。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinaleAuditEntity {

    /** 四维全部通过才为 true；forcedClose 时保留 false，绝不以强制结束冒充质量通过 */
    private boolean overallPass;

    /** 返工预算/preview 头寸耗尽被迫收官（非质量通过） */
    private boolean forcedClose;

    /** 已返工轮数（持久化护栏，跨批/重启有效） */
    private int reworkCount;

    /** 四维逐项结果：FINALE_COMMITMENTS / FORESHADOW / CHARACTER_FATE / WORLD_STATE */
    private List<DimensionResult> dimensions;

    /** 收官后仍未回收/未处置的伏笔清单（第 2 维产物，供报告与返工注入） */
    private List<String> openForeshadows;

    /** 本次缺口清单 → 下轮返工蓝图注入为 carriedTasks 的处置项 */
    private List<String> reworkTasks;

    /** 整体说明 / 强制收官原因 */
    private String note;

    /**
     * 单维审计结果：达成须给出可机械校验的证据（章号 + 该章摘要内的连续原文引用），
     * 证据经 indexOf 子串校验，编造/漂移按未达成处理（宁严勿松）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionResult {
        /** 维度标识：FINALE_COMMITMENTS / FORESHADOW / CHARACTER_FATE / WORLD_STATE */
        private String dimension;
        /** 是否通过（证据校验失败置 false） */
        private Boolean met;
        /** 达成证据所在章节号 */
        private Integer chapterNo;
        /** 达成证据原文引用（该章摘要内的连续子串） */
        private String evidence;
        /** 未达成缺口说明 / 证据校验失败注记 */
        private String note;
    }
}