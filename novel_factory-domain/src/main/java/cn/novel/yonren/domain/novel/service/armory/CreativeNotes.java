package cn.novel.yonren.domain.novel.service.armory;

import org.apache.commons.lang3.StringUtils;

/**
 * 本批创作要点（导演通道，2026-09-30）：请求里的自由文本字段，把作者的创意方向
 * 变成流水线的输入——此前作者的品味只能事后以"读后感"存在，没有任何入口变成指令
 * （2026-09-29 阅读实测的教训：规划层自作主张发明"记忆迷雾"废掉金手指，作者读到才发现）。
 *
 * <p>注入位置：卷蓝图 / 阶段蓝图 / 章节计划三个规划 prompt 的<b>最顶部</b>——
 * 创意方向是作者的直接指令，不进预算守门（不可被裁剪），冲突时以本清单为准。
 * 只注入规划层：正文写手按计划执行，方向已在计划里落定；每章重复注入既稀释注意力也无必要。
 */
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
