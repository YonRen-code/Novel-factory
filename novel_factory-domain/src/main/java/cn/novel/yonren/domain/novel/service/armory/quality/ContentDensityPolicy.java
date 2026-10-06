package cn.novel.yonren.domain.novel.service.armory.quality;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容密度机械检测策略（零 LLM 调用）。
 * 统计正文中的有效对话句数，低于最低要求时渲染审校预警（WARN 语义，不直接 BLOCKING）。
 * 对话句数统计：匹配中文引号（""「」『』）包裹的对话内容，每对引号计为一句。
 */
public final class ContentDensityPolicy {

    private ContentDensityPolicy() { }

    /** 每章最低有效对话句数（基于番茄/起点热门小说调研数据：对话占比30%-40%，2000字约25句） */
    public static final int MIN_DIALOGUE_COUNT = 25;

    /**
     * 匹配对话引号包裹的内容：中文弯引号（U+201C/U+201D，中文小说正文的实际形态）、
     * 直角引号「」『』、以及 ASCII 双引号。
     * 注意：历史上此处只写了 ASCII 双引号（"），而正文用的是弯引号 “ ”，
     * 导致 countDialogue 对全部章节恒定返回 0，产生 185 次假预警 —— 勿再改回 ASCII-only。
     */
    private static final Pattern DIALOGUE_PATTERN = Pattern.compile(
            "[\"\u201C][^\"\u201D]*[\"\u201D]|\u300C[^\u300D]*\u300D|\u300E[^\u300F]*\u300F");

    /**
     * 统计正文中的有效对话句数。
     * 每对中文引号计为一句对话；空内容或纯标点不计入。
     */
    public static int countDialogue(String content) {
        if (content == null || content.isBlank()) return 0;
        Matcher matcher = DIALOGUE_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            String dialogue = matcher.group();
            // 剔除引号后为空或纯标点的不计入
            String inner = dialogue.substring(1, dialogue.length() - 1).trim();
            if (!inner.isEmpty() && !inner.matches("[\\p{Punct}\\s]+")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 渲染内容密度不足的审校预警（WARN 语义，供审校 prompt 注入，不直接 BLOCKING）。
     * 对话数量达标时返回 null。
     */
    public static String renderLowDensityHint(int dialogueCount) {
        if (dialogueCount >= MIN_DIALOGUE_COUNT) return null;
        int deficit = MIN_DIALOGUE_COUNT - dialogueCount;
        return "【内容密度预警】本章有效对话仅 " + dialogueCount + " 句，低于最低要求 "
                + MIN_DIALOGUE_COUNT + " 句（缺口 " + deficit + " 句）。"
                + "审校时重点检查：对话是否推进情节/揭示人物/制造冲突？"
                + "是否存在大段叙述替代角色对话？修订时应通过增加角色间对话交锋来补足，"
                + "严禁通过增加环境描写或心理独白来凑字数。";
    }
}
