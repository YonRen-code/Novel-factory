package cn.novel.yonren.domain.novel.service.armory;

import org.apache.commons.lang3.StringUtils;


public final class CreativeNotes {

    /** 单批创作要点的字符封顶：方向性清单足够，超长说明作者还没想清楚要什么 */
    public static final int MAX_CHARS = 2000;

    private CreativeNotes() {
    }

    /** 格式化为注入块；空白返回空串（调用方直接拼接即可） */
    public static String block(String notes) {
        if (StringUtils.isBlank(notes)) {
            return "";
        }
        return "\n【本批创作要点·作者指令】（最高优先级，与既有约束冲突时以本清单为准）\n"
                + StringUtils.abbreviate(notes.trim(), MAX_CHARS) + "\n";
    }
}
