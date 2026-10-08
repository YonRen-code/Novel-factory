package cn.novel.yonren.domain.novel.service.armory.plan;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.service.armory.memory.OutlineSegmentParser;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 故事时间定位：时序锚实际值（最新摘要）与大纲段预算年的统一解析口径。
 * 解析规则与 BatchHealthService#addOutlinePacing 同源（摘要 timePoint 的 4 位年份）——
 * 规划层（跳接任务注入与 fail-closed 校验）与观测层（进度对齐指标）必须看同一把尺子，
 * 两边各算各的就会出现"体检说滞后、规划说无需跳接"的口径分裂
 */
public final class StoryPacing {

    private static final Pattern YEAR = Pattern.compile("(\\d{4})年");

    private StoryPacing() {
    }

    /** 当前时序锚年份：从最新章向前找第一个带年份的 timePoint；全书未写或摘要均无时间记录时返回 null */
    public static Integer latestAnchorYear(List<ChapterSummaryEntity> chapters) {
        if (chapters == null) {
            return null;
        }
        for (int i = chapters.size() - 1; i >= 0; i--) {
            ChapterSummaryEntity s = chapters.get(i);
            if (s == null || StringUtils.isBlank(s.getTimePoint())) {
                continue;
            }
            Matcher m = YEAR.matcher(s.getTimePoint());
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return null;
    }

    /** 大纲对 chapterNo 所在段预算的起始年份；大纲缺该段、超纲或段无数值年份（境界纪年等）时返回 null（豁免跳接） */
    public static Integer budgetStartYear(String chapterGoal, int chapterNo) {
        if (StringUtils.isBlank(chapterGoal)) {
            return null;
        }
        OutlineSegmentParser.Outline outline = OutlineSegmentParser.parse(chapterGoal);
        if (outline.isEmpty()) {
            return null;
        }
        OutlineSegmentParser.OutlineSegment budget = outline.segmentFor(chapterNo);
        return budget == null ? null : budget.yearStart();
    }
}
