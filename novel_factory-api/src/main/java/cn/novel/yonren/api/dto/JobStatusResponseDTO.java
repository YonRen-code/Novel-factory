package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 作业状态响应：submit 仅填 jobId/status，status 查询与 cancel 返回全量快照
 */
@Data
public class JobStatusResponseDTO {

    /** 作业 ID（轮询/取消/计划裁决等接口的定位键） */
    private String jobId;

    /** 作业状态：CREATED 排队 / RUNNING 执行中 / CANCELLING 取消已受理 / AWAITING_APPROVAL 计划审批挂起 / FAILED 失败 / COMPLETED 完成 / CANCELLED 已取消 */
    private String status;

    /** 故事目录名（resume 时提交即知；首发由 worker 首章进度回填） */
    private String storyDirName;

    /** 本批 run 目录名（run-job-<jobId>） */
    private String runDirName;

    /** 当前阶段（如 CHAPTER_PLAN / CHAPTER_GENERATION，观测用自由文本） */
    private String currentStage;

    /** 当前正在处理的章号（全书全局章号；规划期为本批首章）；尚未推进时为 null */
    private Integer currentChapter;

    /** 本批末章号（= 本批完成后的全书章数；终局返工会动态追加） */
    private Integer totalChapters;

    /** epoch 毫秒 */
    private Long startedAtMs;

    /** 进入终态（完成/失败/取消）的时刻（epoch 毫秒）；未结束为 null */
    private Long finishedAtMs;

    /** 失败原因（仅 FAILED 非空） */
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

    /**
     * 最近一次批末体检报告（每批 5 章结束更新；null=本作业尚无体检）。
     * 结构为 {@code BatchHealthReport} 的原样序列化：overall / metrics[]（key/label/value/unit/
     * healthyLine/direction/level/detail）/ recommendations。声明为 Object 以保持 api 模块不依赖 domain
     */
    private Object healthReport;

}
