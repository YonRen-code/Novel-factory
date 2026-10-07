package cn.novel.yonren.domain.novel.model.aggregate;

import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 章节计划聚合根
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterPlanAggregate {

    /** 本次规划实例的唯一标识（"story-" + 雪花 ID；缺失或非法时由持久化节点兜底分配） */
    private String storyId;

    /** 本批生成的章计划条目（按章号有序，供逐章扩写正文与计划校验） */
    private List<ChapterPlanItemEntity> chapters;

    /** 分支推演时本版规划的风险自评（2-3 条；单稿路径为 null），供评审择优与复盘 */
    private List<String> risks;

}
