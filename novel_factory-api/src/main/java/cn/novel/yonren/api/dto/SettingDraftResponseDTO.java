package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;


@Data
public class SettingDraftResponseDTO {

    /** 题材原样回显（题材是输入，不参与生成） */
    private String theme;

    /** 生成的书名建议 */
    private String novelTitle;

    /** 生成的风格建议 */
    private String style;

    /** 生成的世界观设定 */
    private String worldSetting;

    /** 生成的叙述视角 */
    private String perspective;

    /** 生成的目标人群 */
    private String targetAudience;

    /** 生成的基调 */
    private String tone;

    /** 生成的主人公设定 */
    private String protagonist;

    /** 故事概述：覆盖主角初始处境 / 核心冲突 / 主要障碍 / 关键转折 / 结局方向 */
    private String outline;

    /** 生成的全书章节目标大纲（卷-弧-章骨架；人工修改后作为正式输入的 chapterGoal 提交） */
    private String chapterGoal;

    /** 建议总章数（填入 maxChapterCount）。注意该值一旦随首批提交即被 sticky 固化，之后不可改 */
    private Integer totalChapters;

    /** 构思取舍说明（1-2 句），供判断是否需要重新生成 */
    private String rationale;

    /** 本次实际重生成的字段名（请求 targets 为空时即为全部字段） */
    private List<String> regenerated;
}
