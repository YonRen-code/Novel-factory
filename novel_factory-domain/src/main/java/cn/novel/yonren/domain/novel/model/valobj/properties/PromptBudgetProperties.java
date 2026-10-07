package cn.novel.yonren.domain.novel.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 正文生成前缀总量预算：块级封顶之上的最后一道总额约束。
 * 前缀由记忆/末态红线/风格门禁/一致性索引/禁泄清单等多个块拼成，单块都有封顶，
 * 但"封顶之和"会随块数增长击穿模型输入窗口——由 PromptBudgetGuard 按优先级统一淘汰。
 */
@Data
@Component
@ConfigurationProperties(prefix = "story.prompt.budget")
public class PromptBudgetProperties {


    /** 写手正文前缀总预算（字符数）：各块封顶之和超此值时，由 PromptBudgetGuard 按块优先级裁剪/丢弃 */
    private int totalPrefixChars = 18000;


    /** 章节计划输入段的总预算（字符数），与正文前缀预算相互独立 */
    private int planPrefixChars = 20000;

}