package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 设定集草稿请求（一键生成 / 单字段重生成）。
 *
 * <p>与 {@code StoryGenerateRequestDTO} 不同，这里**不要求填齐 11 个字段**——恰恰相反，
 * 只给题材就能出结果。字段命名用 camelCase（与本端点自身的响应、以及 domain 的 schema 一致），
 * 不沿用生成请求的 snake_case。
 */
@Data
public class SettingDraftRequestDTO {

    /** 题材（必填）：唯一硬输入，例：都市异能 */
    private String theme;

    /** 用户已定的风格（可空）：非空时作为约束传给模型，而不是照抄 */
    private String style;

    /** 用户已定的目标人群（可空） */
    private String targetAudience;

    /** 用户已定的基调（可空） */
    private String tone;

    /** 用户已定的叙述视角（可空） */
    private String perspective;

    /** 额外要求（可空）：例如「想要双男主，不要系统流」，模型必须遵守 */
    private String extraHints;

    /**
     * 本次要重生成的字段名；留空/不传 = 全部重生成。
     * 其余字段会由后端机械回填为 {@link #previous} 中的原值（模型改不动）。
     * 可选值见 {@code SettingDraftService} 的字段表：
     * novelTitle / style / worldSetting / perspective / targetAudience / tone /
     * protagonist / outline / chapterGoal / totalChapters
     */
    private List<String> targets;

    /**
     * 当前已有内容（键同 targets）。双重作用：
     * ① 非 target 字段的锁定取值来源；② 目标字段的"上一版"，用于要求模型本次换一个方向。
     * 传空/不传 = 首次生成。
     */
    private Map<String, String> previous;
}
