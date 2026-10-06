package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 风格统计实体：纯代码统计的机械层风格账本（不经过 LLM）。
 * 跨章逐字重复句 + 疲劳词计数，随正文逐章滚动合并，用于向后续章节注入风格警示。
 * 与三账本同思路：确定性数据，可由落盘文件重建
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyleStatEntity {

    /** 已出现过的长句全集（去重，滚动窗口上限由服务控制） */
    private List<String> usedSentences;

    /** 跨章逐字重复句（确认重复，供警示渲染） */
    private List<String> repeatedSentences;

    /** 疲劳词全篇计数（词 → 出现次数） */
    private Map<String, Integer> fatigueWords;

}
