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

    /**
     * 设定集草稿（一键生成：只给题材，产出书名/风格/世界观/主角/故事概述/章节目标/建议总章数）。
     *
     * <p>与生成链无关的<em>独立辅助场景</em>：不进作业队列、不碰既有闸门与记忆，
     * 由 {@code SettingDraftController} 直接同步调用，把结果回填到生成表单供人修改或重生成。
     * 定位是"定方向"（同 {@link #CHAPTER_PLAN}），因此题材资料照常注入；
     * 但它是结构化输出，反 AI 味文风规则不注入（见 AntiAiToneStrategy）。
     */
    SETTING_DRAFT,

}
