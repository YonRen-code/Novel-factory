package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptRule;
import cn.novel.yonren.types.enums.GenreTypeVO;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对照片例库固定注入：chapter-content 场景（不走向量检索，不占检索名额）。
 * 反AI味规则告诉模型"不要什么"，对照片例告诉它"要什么"——LLM 对示例的模仿远强于对禁令的服从。
 * 文件放在 rules/ 目录而非 references/：固定注入资产不进向量索引，避免稀释检索池。
 *
 * <p><b>与题材范例择一</b>（2026-09-16）：题材包的 {@code exemplars.md} 是**本题材**的正文范例，
 * 与本库功能重叠（都是"给可模仿的示例"）。已命中具体题材时题材范例更贴，本库跳过；
 * 未命中题材（DEFAULT，没有范例可注入）时本库兜底——保证写正文时**始终有一份示例**，
 * 只是来源按题材可用性切换，避免两份示例同时挤占正文注意力。
 */
@Component
@RequiredArgsConstructor
public class ContrastExamplesStrategy implements PromptRuleStrategy {

    private static final String FILE = "rules/contrast-examples.md";

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return scene == PromptScene.CHAPTER_CONTENT;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        // 题材范例择一（见类注释）：GenreMaterialStrategy 会为非 DEFAULT 题材注入 exemplars.md，
        // 此时本库让位；DEFAULT（无题材范例）时才由本库兜底，保证正文始终有一份可模仿的示例
        if (ctx != null && GenreTypeVO.match(ctx.getTheme(), ctx.getStyle()) != GenreTypeVO.DEFAULT) {
            return List.of();
        }
        String content = fileLoader.load(FILE);
        if (content == null) {
            return List.of();
        }
        return List.of(new PromptRule("contrast-examples", content, PromptRule.InjectPosition.USER_TAIL));
    }
}
