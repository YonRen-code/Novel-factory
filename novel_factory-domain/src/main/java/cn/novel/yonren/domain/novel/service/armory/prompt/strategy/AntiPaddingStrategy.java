package cn.novel.yonren.domain.novel.service.armory.prompt.strategy;

import cn.novel.yonren.domain.novel.service.armory.PromptRuleStrategy;
import cn.novel.yonren.domain.novel.service.armory.prompt.CriticalPromptRule;
import cn.novel.yonren.domain.novel.service.armory.prompt.PromptRuleFileLoader;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptRule;
import cn.novel.yonren.types.enums.PromptScene;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 反注水固定注入：chapter-content 场景每章必带，不走向量检索、不占检索名额——
 * 防注水是全书级硬约束，不能交给"检索命中率"决定。
 * 注入内容为 pacing-control.md 的灌水定义/高发形态/章末自检 + quality-checklist.md 的注水检测节，
 * 按标题锚点抽取小节而非全文注入，控制 prompt 体积；单个锚点缺失时 fail-soft 跳过该小节
 * （全部锚点都缺失属资产丢失，由 {@link CriticalPromptRule} 终止作业而非静默无约束开写）
 */
@Component
@RequiredArgsConstructor
public class AntiPaddingStrategy implements PromptRuleStrategy, CriticalPromptRule {

    @Override
    public String assetDescription() {
        return "references/pacing-control.md 与 references/quality-checklist.md 的锚点小节";
    }

    private final PromptRuleFileLoader fileLoader;

    @Override
    public boolean supports(PromptScene scene, PromptContext ctx) {
        return scene == PromptScene.CHAPTER_CONTENT;
    }

    @Override
    public List<PromptRule> load(PromptScene scene, PromptContext ctx) {
        String pacing = fileLoader.load("references/pacing-control.md");
        String checklist = fileLoader.load("references/quality-checklist.md");

        StringBuilder sb = new StringBuilder();
        appendSection(sb, pacing, "## 灌水的定义", "## 矛盾冲突急救法");
        appendSection(sb, pacing, "## 章末自检（防灌水清单）", "## 开篇节奏防崩盘");
        appendSection(sb, checklist, "### 注水检测（长篇小说必查）", "---");

        if (sb.length() == 0) {
            return List.of();
        }
        return List.of(new PromptRule("anti-padding",
                "写作本章时必须逐条遵守以下防注水纪律：\n\n" + sb,
                PromptRule.InjectPosition.USER_TAIL));
    }

    /** 按标题锚点抽取小节：[startAnchor, endAnchor)，endAnchor 为空则取到文末；锚点缺失静默跳过 */
    private void appendSection(StringBuilder sb, String content, String startAnchor, String endAnchor) {
        if (StringUtils.isBlank(content)) {
            return;
        }
        int start = content.indexOf(startAnchor);
        if (start < 0) {
            return;
        }
        int end = endAnchor == null ? -1 : content.indexOf(endAnchor, start + startAnchor.length());
        String section = (end < 0 ? content.substring(start) : content.substring(start, end)).trim();
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(section);
    }
}
