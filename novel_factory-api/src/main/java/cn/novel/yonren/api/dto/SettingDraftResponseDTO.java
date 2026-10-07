package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;


@Data
public class SettingDraftResponseDTO {

    /** 题材原样回显（题材是输入，不参与生成） */
    private String theme;

    private String novelTitle;

    private String style;

    private String worldSetting;

    private String perspective;

    private String targetAudience;

    private String tone;

    private String protagonist;

    /** 故事概述：覆盖主角初始处境 / 核心冲突 / 主要障碍 / 关键转折 / 结局方向 */
    private String outline;

    private String chapterGoal;

    /** 建议总章数（填入 maxChapterCount）。注意该值一旦随首批提交即被 sticky 固化，之后不可改 */
    private Integer totalChapters;

    /** 构思取舍说明（1-2 句），供判断是否需要重新生成 */
    private String rationale;

    /** 本次实际重生成的字段名（请求 targets 为空时即为全部字段） */
    private List<String> regenerated;
}
