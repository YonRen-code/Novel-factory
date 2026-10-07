package cn.novel.yonren.domain.novel.model.aggregate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 故事生成结果聚合根
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryGenerateResultAggregate {

    /** 本批产出的章节计划聚合根（含 storyId 与章计划条目） */
    private ChapterPlanAggregate chapterPlanAggregate;

    /** 本次生成各环节实际使用的 prompt 汇总（键含场景前缀，如 blueprint:system / plan:user / rule:*），供返回展示与复盘 */
    private Map<String, String> usedPromptMap;

    // 本次落盘的故事目录名（docs/workspace/stories 下），续写请求将其填入 resumeStoryDir
    private String storyDirName;

}
