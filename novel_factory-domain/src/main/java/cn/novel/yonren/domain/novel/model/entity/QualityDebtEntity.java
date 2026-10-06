package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 质量债实体：审校确认、未经修复的 BLOCKING 问题跨章债务。
 * 单独落盘 memory/quality-debts.json，不动 summaries.json——
 * 老故事续写依赖其纯数组格式，加字段不如单开文件稳。
 * 回灌最近 1~2 章的未修复债给后续章节，避免同类问题复发；
 * 由 QualityDebtService 逐章验证，连续无同维度复发即自动核销
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityDebtEntity {

    // 产生该债务的章号（全局编号）
    private Integer chapterNo;

    // 审校确认且未修复的问题（维度/证据/描述/建议）
    private List<ChapterIssueEntity> issues;

    // 是否已修复（验证式自动核销，或人工标记）
    private boolean resolved;

    // 连续无同维度复发的审校章数：达到 QualityDebtService.CLEAN_STREAK_TO_RESOLVE 即自动核销
    private int cleanStreak;

}
