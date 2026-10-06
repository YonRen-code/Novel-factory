package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 章节修订决策
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevisionDecisionEntity {

    /** 是否尝试过修订 */
    private boolean attempted;

    /** 修订稿是否被采纳 */
    private boolean accepted;

    /** 采纳/拒绝原因 */
    private String reason;

}
