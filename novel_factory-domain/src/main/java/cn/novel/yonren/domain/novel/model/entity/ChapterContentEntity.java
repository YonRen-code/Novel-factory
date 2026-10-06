package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 章节正文实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterContentEntity {

    // 章节号
    private Integer chapterNo;
    // 标题
    private String title;
    // 正文
    private String content;

}
