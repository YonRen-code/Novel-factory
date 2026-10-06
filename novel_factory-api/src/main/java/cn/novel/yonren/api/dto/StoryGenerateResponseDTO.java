package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.Map;

@Data
public class StoryGenerateResponseDTO {
    //章节计划
    private Object chapterPlan;
    //使用模板
    private Map<String,String> usedPromptMap = null;
    //落盘目录名：续写时填入下一次请求的 resumeStoryDir
    private String storyDirName;
}
