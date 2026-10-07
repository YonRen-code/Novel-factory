package cn.novel.yonren.types.enums;

/**
 * 提示词规则场景：不同节点按场景触发不同规则
 */
public enum PromptScene {

    /** 写大纲（章节计划） */
    CHAPTER_PLAN,

    /** 写正文（逐章生成） */
    CHAPTER_CONTENT,

    /** 章节审校 */
    CHAPTER_AUDIT,

    /** 章节修订 */
    CHAPTER_REVISE,

    /** 阶段蓝图（滚动大纲，每跨阶段边界生成一版） */
    STAGE_BLUEPRINT,

    /** 卷蓝图（全书长期锚，仅在卷边界生成一版、落盘后不再滚动微调） */
    VOLUME_BLUEPRINT,

    SETTING_DRAFT,

}
