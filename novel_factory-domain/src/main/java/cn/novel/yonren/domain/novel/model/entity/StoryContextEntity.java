package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryContextEntity {

    //小说名字
    private String novel_title;
    //小说类型
    private String theme;
    //小说格调
    private String style;
    //世界观
    private String worldSetting;
    //视角
    private String perspective;
    //目标人群
    private String targetAudience;
    //基调
    private String tone;
    //主人公
    private String protagonist;
    //概述
    private String outline;
    //章节数量
    private Integer chapterCount;
    //章节概括
    private String chapterGoal;
    // 系列/共享世界观 ID（可选，五期）：同系列故事共享世界观向量集合
    private String worldId;

}
