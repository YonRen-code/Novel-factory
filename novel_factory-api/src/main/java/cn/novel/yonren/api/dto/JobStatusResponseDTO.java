package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 作业状态响应：submit 仅填 jobId/status，status 查询与 cancel 返回全量快照
 */
@Data
public class JobStatusResponseDTO {

    private String jobId;

    private String status;

    /** 故事目录名（resume 时提交即知；首发由 worker 首章进度回填） */
    private String storyDirName;

    /** 本批 run 目录名（run-job-<jobId>） */
    private String runDirName;

    private String currentStage;

    private Integer currentChapter;

    private Integer totalChapters;

    /** epoch 毫秒 */
    private Long startedAtMs;

    private Long finishedAtMs;

    private String errorMessage;

    /**
     * 每章墙钟耗时（毫秒）。键为章号字符串——与 {@code GenerationJob.chapterDurations} 类型一致
     * （源头用 String 键是为了让落盘的 job-status.json 合法，细节见该字段注释）。
     * 对 HTTP 响应无影响：JSON 对象键本就是字符串，Jackson 两种类型的输出完全一致。
     */
    private Map<String, Long> chapterDurations;

    /**
     * 待裁决的章节计划（仅 status=AWAITING_APPROVAL 时非空）。
     * 结构与 {@code ChapterPlanApprovalRequestDTO} 一致，可原样取回、改完再 POST 回 approve 端点。
     * 声明为 Object 以保持 api 模块不依赖 domain（与 StoryGenerateResponseDTO.chapterPlan 同一取舍）
     */
    private Object pendingChapterPlan;

    /** 进入审批挂起的时刻（epoch 毫秒）；非挂起态为 null */
    private Long awaitingPlanApprovalAtMs;

    /** 裁决截止时刻（epoch 毫秒）；前端据此倒计时。超时后由后端按配置放行或中止 */
    private Long planApprovalDeadlineMs;

    /** 本作业已完成的人工裁决轮次（超时放行不计入，用于区分"人改过"与"没人管"的观测） */
    private Integer approvalRound;

}
