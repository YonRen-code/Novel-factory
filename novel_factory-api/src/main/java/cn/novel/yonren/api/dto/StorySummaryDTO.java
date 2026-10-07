package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class StorySummaryDTO {

    /** 故事目录名（stories/ 下；工作台/续写/章节等接口的路径参数） */
    private String storyDirName;

    /** 书名（story-bible 首行；bible 缺失时回退目录名） */
    private String novelTitle;

    /** 已落盘章节数 */
    private int chapterCount;

    /** 故事目录内文件的最新修改时刻（epoch 毫秒，前端展示"最近更新"） */
    private long lastModifiedMs;

    /** 是否有运行中的生成作业占用该故事（true=生成中，编辑/回滚/打检查点会被 409 拒绝） */
    private boolean activeJob;
}
