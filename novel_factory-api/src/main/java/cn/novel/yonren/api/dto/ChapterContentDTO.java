package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class ChapterContentDTO {

    /** 章节号（全书全局编号，1 起） */
    private int chapterNo;

    /** 章节标题 */
    private String title;

    /** 章节正文全文（人工编辑界面直接加载展示） */
    private String content;

}
