package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 设定集草稿响应：一套可直接回填生成表单的字段。
 *
 * <p>字段名与前端表单键的对应关系（前端负责映射）：
 * {@code novelTitle→novel_title}、{@code totalChapters→maxChapterCount}，其余同名。
 *
 * <p>另有两点用法约定：值为 {@code null} 表示该字段本次没有产出，前端应**保持输入框原样**、
 * 不要清空；{@code regenerated} 标明本次真正重生成的字段，供前端决定"回填全部"还是"只回填某一个"。
 */
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
