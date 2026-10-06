package cn.novel.yonren.domain.novel.service.armory.quality;

/**
 * 章节正文长度策略。有效字符仅统计中文、字母和数字，排除空白及标点。
 *
 * <p><b>为什么补了参考上沿（2026-09-29）</b>：策略原先只有下沿，于是"注水"这一类问题
 * <b>完全没有检测面</b>——实测第 20 章有效字 4170（邻章 1607～2205，约 2.2 倍）而关键事件数不变
 * （5 个，与 2205 字的第 19 章相同），信息密度腰斩，门禁与体检一个都没报。
 *
 * <p>上沿取 {@code assets/rules/plan-targets.md} 声明的目标区间上界（1400–2600）。
 * ⚠️ 与下沿一样<b>只告警不阻塞</b>：长度是创作取舍，机械层负责把现状摆出来并回灌规划层，
 * 不负责拒收章节（拒收只会逼模型注水——这条在下沿的设计里已经论证过）。
 */
public final class ChapterLengthPolicy {
    public static final int MINIMUM_EFFECTIVE_CHARACTERS = 1500;

    /**
     * 参考区间上沿：超出即为"注水"信号。
     * 注意它<b>不是</b>下沿的镜像：下沿查的是"计划给的料太少"，上沿查的是"关键事件没增加、
     * 描写与对白被拉长"。两者要分别看，不能合成一个"长度是否合格"的布尔量。
     */
    public static final int MAXIMUM_REFERENCE_CHARACTERS = 2600;

    private ChapterLengthPolicy() { }
    public static int effectiveCharacterCount(String content) {
        if (content == null) return 0;
        int count = 0;
        for (int i = 0; i < content.length();) {
            int cp = content.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) count++;
            i += Character.charCount(cp);
        }
        return count;
    }
    public static boolean meetsMinimum(String content) {
        return effectiveCharacterCount(content) >= MINIMUM_EFFECTIVE_CHARACTERS;
    }
    /** 是否超出参考上沿（注水信号）；空内容返回 false（无从判定，宁可漏报） */
    public static boolean exceedsReference(String content) {
        return effectiveCharacterCount(content) > MAXIMUM_REFERENCE_CHARACTERS;
    }
}
