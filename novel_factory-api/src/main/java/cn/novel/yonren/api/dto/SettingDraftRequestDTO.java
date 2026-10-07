package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;


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

    private List<String> targets;

    private Map<String, String> previous;
}
