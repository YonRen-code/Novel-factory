package cn.novel.yonren.domain.novel.service.armory.quality;


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
