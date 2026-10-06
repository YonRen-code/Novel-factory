package cn.novel.yonren.domain.novel.service.armory.contract;

/**
 * 正文生成模式：由**契约完整度 + 节拍可用性**决定，而不是靠一套规则硬扛所有章节。
 *
 * <p><b>三档的含义</b>（注意与评论意见的差异：那边把"给节拍"当成降级档，
 * 而本项目**每次都会生成节拍**，所以现状≈SCAFFOLDED，{@link #FREE} 才是新增能力）：
 * <ul>
 *   <li>{@link #FREE}——契约完整且有真实节拍：节拍只作**建议顺序**，模型可自行安排场景与对白比例；</li>
 *   <li>{@link #SCAFFOLDED}——契约完整但节拍缺失/越界：用**通用骨架**兜底，保住因果链但不逐拍锁死；</li>
 *   <li>{@link #RECOVERY}——契约本身不完整（缺核心任务/必须事件/结尾落点）：
 *       给骨架 + **强约束**，禁止添加计划外的主要人物、地点与冲突，先把这一章写成立。</li>
 * </ul>
 *
 * <p>⚠️ 三档都必须**可观测**：选定结果要落进日志，否则"什么时候降级了"无法归因
 * （项目里已经吃过"改了但看不见"的亏）。判定本身是纯函数、零 LLM 成本。
 */
public enum GenerationMode {

    /** 契约完整 + 节拍可用：节拍作建议，表达自由。 */
    FREE("自由写作"),

    /** 契约完整但节拍不可用：用通用骨架兜底。 */
    SCAFFOLDED("骨架兜底"),

    /** 契约不完整：骨架 + 强约束，先写成立。 */
    RECOVERY("强约束恢复");

    private final String label;

    GenerationMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 选择生成模式。
     *
     * @param contract       本章契约（可为 null → RECOVERY）
     * @param beatsAvailable 是否拿到可用节拍表
     */
    public static GenerationMode decide(ChapterContract contract, boolean beatsAvailable) {
        if (contract == null || !contract.isComplete()) {
            return RECOVERY;
        }
        return beatsAvailable ? FREE : SCAFFOLDED;
    }

    /** 是否要求"逐拍强制扩写"（FREE 下节拍退化为建议顺序） */
    public boolean enforcesBeats() {
        return this == SCAFFOLDED || this == RECOVERY;
    }

    /** 是否附加"禁止计划外人物/地点/冲突"的强约束 */
    public boolean forbidsOutOfPlanElements() {
        return this == RECOVERY;
    }
}
