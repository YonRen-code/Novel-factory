package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class ChapterMetaDTO {

    /** 章节号（全书全局编号，1 起） */
    private int chapterNo;

    /** 章节标题 */
    private String title;

    /** 正文字符数（章节列表展示用，不携带正文本身） */
    private int contentLength;

}
