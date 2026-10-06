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
 * 去 AI 味规则：按场景注入 {@code rules/anti-ai-tone.md} 的**对应子集**。
 *
 * <p><b>2026-09-16 收窄（此前无差别注入所有场景）</b>：该文件 7637 字符，内容是正文文风判据
 * （身体反应套话、环境描写、内心独白、章末升华、谜语人台词…）。此前 {@code supports()} 恒真，
 * 于是它同时进入了阶段蓝图与卷蓝图——而那两处输出结构化 JSON，正文文风规则对它们
 * <em>没有作用</em>，只是稀释业务上下文、拉低章节任务与蓝图约束的权重，还更容易干扰结构化输出。
 *
 * <p><b>按场景取用</b>（依据各小节实测字符量与文件自身的定位声明）：
 * <ul>
 *   <li><b>正文 / 修订 / 审校 → 全文</b>。前两者是 writer（要规避），后者是 editor
 *       （文件首段声明"writer 与 editor 共用"，第七节明确标为"editor 逐项对照"）</li>
 *   <li><b>章节计划 → 仅第一节（结构 AI 味）</b>。计划的 goal/keyEvents 措辞直接决定
 *       后续正文是否模板化（三段式排比、句式雷同、一二三分点），这一节对规划成立；
 *       描写/对话/节奏等条款只对正文成立。刻意<b>不</b>连带第七节——第七节条目交叉引用
 *       第二/三/五节（"详见第二节…"），只取其中一节会造成悬空引用</li>
 *   <li><b>阶段蓝图 / 卷蓝图 → 不注入</b>。结构化输出，正文文风规则无适用面</li>
 * </ul>
 *
 * <p>取用一律走 {@link PromptRuleFileLoader#loadSections} 从**同一份文件**抽节，
 * 不另写"简版"副本——副本必然与正本漂移，而漂移的文风规则会静默改变生成质量。
 */
@Component
@RequiredArgsConstructor
public class AntiAiToneStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "rules/anti-ai-tone.md 的按场景小节（计划场景需 ## 一、，正文/修订需 ## 一~六、八）";
    }

    private static final String FILE = "rules/anti-ai-tone.md";

    /** 结构 AI 味小节（计划场景唯一需要的一节） */
    private static final String STRUCTURE_SECTION = "## 一、";

    /**
     * writer 场景（正文/修订）的节集：全文**去掉第七节**。
     * 第七节自标「editor 逐项对照」，且开头声明"与上文详述条目重合的，以上文为准"——
     * 对 writer 它是第二/三/五节的复读，846 字符买不到新信息；editor 审阅时才有逐项对照的价值
     */
    private static final String[] WRITER_SECTIONS = {
            "## 一、", "## 二、", "## 三、", "## 四、", "## 五、", "## 六、", "## 八、"};

    /** 结构子集的作用域提示：说明为何这里只有一小节，避免模型误以为规则缺失 */
    private static final String PLAN_SCOPE_NOTE =
            "（以上是去 AI 味判据中**适用于计划文本**的结构条款：计划的 goal/keyEvents 措辞不得模板化。"
                    + "完整的正文文风判据在写正文与修订时注入，此处不必考虑描写与对话层面的要求。）";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return sectionsOf(scene) != null;
    }

    /** 场景 → 需要注入的小节标题前缀；返回 null 表示该场景不注入本规则 */
    private static String[] sectionsOf(PromptScene scene) {
        if (scene == null) {
            return null;
        }
        return switch (scene) {
            // writer 拿除第七节外的全部；editor（审校）拿全文做逐项对照
            case CHAPTER_CONTENT, CHAPTER_REVISE -> WRITER_SECTIONS;
            case CHAPTER_AUDIT -> new String[]{"## "};
            case CHAPTER_PLAN -> new String[]{STRUCTURE_SECTION};
            // 设定集草稿同为结构化输出且不是"写文案"：反 AI 味的文风判据对产出 JSON 字段无适用面，
            // 只会挤占设定约束（同蓝图场景的收窄理由）
            case STAGE_BLUEPRINT, VOLUME_BLUEPRINT, SETTING_DRAFT -> null;
        };
    }

    /** 审校场景拿整份文件（含第七节速查），其余场景按节抽取 */
    private static boolean isFullText(PromptScene scene) {
        return scene == PromptScene.CHAPTER_AUDIT;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        String[] sections = sectionsOf(scene);
        if (sections == null) {
            return List.of();
        }
        String content;
        if (isFullText(scene)) {
            content = fileLoader.load(FILE);
        } else {
            content = fileLoader.loadSections(FILE, sections);
            if (content != null && scene == PromptScene.CHAPTER_PLAN) {
                content = content + "\n" + PLAN_SCOPE_NOTE;
            }
        }
        if (content == null) {
            return List.of();
        }
        return List.of(new PromptRule("anti-ai-tone", content, PromptRule.InjectPosition.SYSTEM));
    }

}
