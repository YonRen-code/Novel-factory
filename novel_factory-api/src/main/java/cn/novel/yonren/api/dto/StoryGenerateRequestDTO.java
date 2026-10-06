package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.Map;

@Data
public class StoryGenerateRequestDTO {
    //小说名字 "灵气复苏后的外卖员"
    private String novel_title;
    //小说类型 "都市异能"
    private String theme;
    //小说格调 "逆袭打脸爽文"
    private String style;
    //世界观 "现代都市，少数人觉醒异能"
    private String worldSetting;
    //视角 "第三人称视角"
    private String perspective;
    //目标人群 "男频"
    private String targetAudience;
    //基调
    private String tone;
    //主人公 "林川，23岁，外卖员，性格隐忍但有底线"
    private String protagonist;
    //概述 "主角送外卖时卷入异能者冲突，意外觉醒能力"
    private String outline;
    //章节数量
    private Integer chapterCount;
    //章节概括 "第一章，主角遭遇事件并觉醒能力"
    private String chapterGoal;
    // 续写目录名（可选）：传入时预载该目录记忆摘要，章节号全局接续
    private String resumeStoryDir;
    // 系列/共享世界观 ID（可选）：同系列的 story-bible 写入共享向量集合 novel-world-{worldId}，
    // 写新章时与故事记忆合并检索唤醒；仅允许字母数字开头、1-63 位字母数字/下划线/连字符
    private String worldId;
    // 全书总章数上限（可选）：传入时覆盖 yml 的 constraints.max-chapter-count，
    // 续写偏移达到该值即视为完结、拒绝继续生成；本批超出部分自动截断
    private Integer maxChapterCount;
    // 故事设定明确存在金手指/系统时传 true；false/null 时不启用三章频率规则
    private Boolean hasCheatMechanism;
    private String cheatMechanismName;
    private Integer cheatUsageInterval;

    /**
     * 请求级跳过章节计划审批门（2026-09-26 新增）：true = **本批不挂起**，计划校验后直连正文生成；
     * false/null = 仍按 yml story.plan-approval 的开关与作用范围决定。
     *
     * <p>语义刻意定为"**只能放宽不能收紧**"：它无法让 yml 关闭的门重新生效，
     * 以免请求参数悄悄改掉配置侧的"要审"意图。仅影响本次作业，不落 bible（属运行参数而非故事设定）。
     */
    private Boolean autoApprovePlan;

    /** 本批创作要点（导演通道）：自由文本，注入卷/阶段/计划三个规划 prompt 顶部 */
    private String creativeNotes;
}


//    {
//        "novel_title": "灵气复苏后的外卖员",
//        "theme": "都市异能",
//        "style": "逆袭打脸爽文",
//        "worldSetting": "现代都市，少数人觉醒异能",
//        "perspective": "第三人称",
//        "targetAudience": "男频",
//        "tone": "紧张、带爽感",
//        "protagonist": "林川，23岁，外卖员，性格隐忍但有底线",
//        "outline": "主角送外卖时卷入异能者冲突，意外觉醒能力",
//        "chapterCount": 5,
//        "chapterGoal": "写第一章，主角遭遇事件并觉醒能力"
//        }
