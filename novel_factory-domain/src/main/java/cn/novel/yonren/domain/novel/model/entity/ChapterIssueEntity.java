package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 章节审校问题项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterIssueEntity {

    /** 审校维度：consistency/character/pacing/continuity/foreshadow/hook/aesthetic */
    private String dimension;

    /** 严重程度：BLOCKING / MINOR */
    private String severity;

    /** 原文引用（证据） */
    private String evidence;

    /** 问题描述 */
    private String description;

    /** 修改建议 */
    private String suggestion;

}
