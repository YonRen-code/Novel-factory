package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 续写准备信息：把某个已有故事恢复到"可以接着上次的末尾往下写"的状态。
 *
 * <p>存在的理由：续写要走和首发完全相同的入参校验（{@code ValidateUserInputNode} 对 11 个字段全非空），
 * 而这些设定在服务端唯一被持久化下来的地方就是 {@code story-bible.txt}（story-meta 只存总章数）。
 * 有了本端点，前端只要让用户挑一个已有故事，就能把整套设定取回来，而不必让人肉记住并重填。
 *
 * <p>{@link #setting} <b>刻意复用提交体 {@link StoryGenerateRequestDTO} 的形状</b>——它就是要回填到生成表单
 * 并原样提交的东西。复用同一类型而不是另建一个平行 DTO，是为了不让"表单字段"与"续写字段"两份定义各自漂移。
 */
@Data
public class StoryResumeDTO {

    private String storyDirName;

    /** 书名（展示用；权威取值在 {@link #setting} 里） */
    private String novelTitle;

    /**
     * 已填好 {@code resumeStoryDir / maxChapterCount / worldId} 的整套设定，可直接回填表单并作为提交体。
     * {@code settingAvailable=false} 时其中的设定字段可能为 null。
     */
    private StoryGenerateRequestDTO setting;

    /** 已落盘章节数 */
    private Integer existingChapterCount;

    /** 末章章号；尚无章节时为 null */
    private Integer latestChapterNo;

    /** 下一章将从这里开始；尚无章节时为 1 */
    private Integer nextChapterNo;

    /** bible 是否解析出了设定。false = 该目录不是有效故事目录，或 bible 缺失/为空 */
    private boolean settingAvailable;

    /**
     * 未能从 bible 取到的必需字段（中文名）。非空时前端应提示用户补齐——
     * 否则用户点"继续生成"只会撞上入参校验失败，却不知道缺的是哪一个（该校验是一条 or 链，不定位具体字段）
     */
    private List<String> missingFields = new ArrayList<>();
}
