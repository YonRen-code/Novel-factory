package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节审校结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditResultEntity {

    /** 本批审校发现的问题清单（维度/严重度/证据/建议，见 ChapterIssueEntity；BLOCKING 阻塞放行、MINOR 轻微） */
    @Builder.Default
    private List<ChapterIssueEntity> issues = new ArrayList<>();

}
