package cn.novel.yonren.types.enums;

/**
 * 模型路由场景：与 PromptScene（管规则注入）解耦，专用于按场景选择模型/思考模式。
 * configKey 为 yml 中 scene-models 的键（kebab-case），与 usage 记账 label 前缀一致。
 */
public enum ModelScene {

    /** 阶段蓝图（滚动大纲，每跨阶段边界生成一版） */
    STAGE_BLUEPRINT("stage-blueprint", "阶段蓝图"),

    /** 章节计划（写大纲） */
    CHAPTER_PLAN("chapter-plan", "章节计划"),

    /** 章节场景节拍（每章正文前把计划拆成 3-5 拍；每章一次的高频小输出任务，独立场景以便与批次计划分开配模型） */
    CHAPTER_BEATS("chapter-beats", "章节节拍"),

    /** 正文（逐章生成） */
    CHAPTER_CONTENT("chapter-content", "章节正文"),

    /** 章节审校 */
    CHAPTER_AUDIT("audit", "章节审校"),

    /**
     * 段落密度审校（2026-10-04 从 audit 场景拆出）：机械补丁任务——输入本章正文、
     * 输出至多 3 条改写补丁，输出仅数百字，但每章一次。
     *
     * <p>拆出理由：此前复用 audit 场景模型（qwen3.7-max-preview，强制思考、
     * {@code enable-thinking:false} 被供应商拒绝），思维链 token 占单次调用的 ~2/3
     * （实测 4.8k 字输入 / 700 字输出实付 7-9.5k token），5 章 ≈ 3 万 token 纯税。
     * 拆出后配非思考免费档，机械任务零质量风险。
     */
    PARAGRAPH_AUDIT("paragraph-audit", "段落密度审校"),

    /** 章节修订 */
    CHAPTER_REVISE("revise", "章节修订"),

    /** 章节摘要（结构化记忆压缩） */
    CHAPTER_SUMMARY("summary", "章节摘要"),

    /**
     * 账本挂起事实裁决（L1 兜底通道）：回读当章正文，判定机械分档放行后仍挂起的状态/一致性事实
     * 是否真的成立，并要求给出可逐字核对的引文；引文须再经 EvidenceMatch 才算入账。
     * 每章至多一次（无挂起项时不调用），低频小输出，配免费档即可
     */
    LEDGER_ADJUDICATE("ledger-adjudicate", "账本裁决"),

    /** 卷末伏笔清账 */
    FORESHADOW_SETTLEMENT("foreshadow-settlement", "伏笔清账"),

    /** 阶段出口条件核验 */
    STAGE_EXIT_REVIEW("exit-review", "阶段出口核验"),

    /** 终局审查（收官门禁：四维完结质量审计） */
    FINALE_REVIEW("finale-review", "终局审查"),

    /** 章节评审（第二模型族：低置信通过时的胜负裁决；条目内可覆盖 base-url/api-key 指向异供应商） */
    CHAPTER_JUDGE("chapter-judge", "章节评审"),

    /** 候选重写（第二模型族：低置信通过时的整章重写；与 chapter-judge 拆分——两稿候选同族、评审异族才无自偏好） */
    CHAPTER_REWRITE("chapter-rewrite", "候选重写"),

    /** 动态资料选择器 */
    REFERENCE_SELECT("ref-select", "动态资料选择"),

    /**
     * 全书质量评分（2026-09-27 新增）：每 N 章按**带版本号的固定 rubric** 给近期正文章节打分，
     * 连同机械指标落成趋势线（memory/quality-trend.jsonl）。
     *
     * <p>存在的理由：此前只有逐章机械门禁与批级体检，都是"这一章有没有坏"，没有"这本书在变好还是变差"。
     * 缺少全书级度量时，任何 prompt/模型调整都无法归因，只能靠感觉与换模型来赌。
     * 低频（每 10 章一次，每次抽若干章）、输出量小，故单列场景以便成本与效果独立核算。
     */
    QUALITY_REVIEW("quality-review", "全书质量评分");

    private final String configKey;
    /** 中文显示名（前端场景矩阵用） */
    private final String label;

    ModelScene(String configKey, String label) {
        this.configKey = configKey;
        this.label = label;
    }

    /** yml 中 scene-models 的键（与 usage 记账 label 前缀一致） */
    public String getConfigKey() {
        return configKey;
    }

    /** 中文显示名 */
    public String getLabel() {
        return label;
    }
}
