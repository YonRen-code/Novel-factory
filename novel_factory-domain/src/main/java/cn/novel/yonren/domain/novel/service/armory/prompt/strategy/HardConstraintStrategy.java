package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.CriticalPromptRule;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 写作硬约束规则：篇幅/对话/节奏配额/状态一致性/人物口径的逐条硬要求。
 *
 * <p>存在的理由（2026-09-17）：实测 17 章给出的一批问题——章节越写越薄（均值 2291→1900）、
 * 有效对话坍缩（最低 4 句）、跨章状态跳变（第 4 章取回灵石无过渡）、
 * 「杀伐果断」被写成冷酷无情——共同根因是**输入侧没有可执行的约束**，
 * 模型只能按自己的默认审美走。这些约束是**正文级**的，因此必须注入到能看见正文的场景。
 *
 * <p>⚠️ 为什么放在 rules/ 而不是让用户填进 {@code style}：`storyContext`（各设定字段拼成的那段）
 * <b>只进蓝图/计划 prompt，进不了正文 prompt</b>（{@code GenerateChapterContentNode} 里没有它）。
 * 把"每章≥25句对话"这类约束写进 style，只能经计划间接传递，链路过长在正文阶段极易丢失。
 * 规则文件按场景注入，是本项目里放"写作硬约束"的既有位置。
 *
 * <p>注入位置取 USER_TAIL：它会被拼在 user prompt 末尾并冠以
 * 「以下为写作规则，创作时必须遵守」——离任务最近、指令感最强，适合硬约束。
 */
@Component
@RequiredArgsConstructor
public class HardConstraintStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "rules/hard-constraints.md（计划场景另需 rules/plan-targets.md）";
    }

    /** 放 rules/ 目录避开 references 索引扫描，防止与动态资料选择重复注入 */
    private static final String FILE = "rules/hard-constraints.md";

    /**
     * 篇幅与对话的**数量目标**（2026-09-22 约束归层）：**只注入规划场景**。
     *
     * <p>正文层直接下达数量指标会诱导"为满足数量而写"（多写动作、多塞对白、硬凑推进点）——
     * 实测那套指标既没有阻止偏薄章节，反而给了凑字数的方向。数量目标归规划层并由其落成每章安排；
     * 正文层改用 {@code hard-constraints.md} 里的「内容密度纪律」（定性要求）。
     */
    private static final String PLAN_TARGETS_FILE = "rules/plan-targets.md";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        // 三类"要落笔"的场景：计划（把约束落成每章安排）、正文（逐条执行）、修订（不得改坏）
        return scene == PromptScene.CHAPTER_PLAN
                || scene == PromptScene.CHAPTER_CONTENT
                || scene == PromptScene.CHAPTER_REVISE;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        List<PromptRule> rules = new java.util.ArrayList<>();
        String content = fileLoader.load(FILE);
        if (content != null) {
            rules.add(new PromptRule("hard-constraints", content, PromptRule.InjectPosition.USER_TAIL));
        }
        if (scene == PromptScene.CHAPTER_PLAN) {
            String targets = fileLoader.load(PLAN_TARGETS_FILE);
            if (targets != null) {
                rules.add(new PromptRule("plan-targets", targets, PromptRule.InjectPosition.USER_TAIL));
            }
        }
        return rules;
    }

}
