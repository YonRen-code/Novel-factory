package cn.novel.yonren.domain.novel.service.job;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.types.enums.JobStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * 一次异步生成作业的可变记录：registry / DynamicContext / worker 三方共享同一实例。
 * 状态迁移全部 synchronized，终态不可再迁移；cancelRequested 为协作取消的 volatile 信号，
 * 随状态一起序列化进 job-status.json（崩溃后人工排查 CANCELLING 卡住时可见信号是否送达）
 */
@Getter
@Setter
@NoArgsConstructor
public class GenerationJob {

    private String jobId;

    private volatile JobStatus status;

    /** 故事目录名（resume 时提交即知；首发由 worker 首章进度回填） */
    private String storyDirName;

    /** 本批 run 目录名（run-job-<jobId>，树尾持久化时回填） */
    private String runDirName;

    /** 当前阶段（如 CHAPTER_GENERATION），观测用自由文本 */
    private String currentStage;

    private Integer currentChapter;

    private Integer totalChapters;

    /** epoch 毫秒，fastjson2 序列化形态稳定 */
    private Long startedAtMs;

    private Long finishedAtMs;

    private String errorMessage;

    /**
     * 每章墙钟耗时（毫秒），观测用。**键必须是 String**：
     * 本对象由 fastjson2 序列化进 job-status.json，而 fastjson2 对非字符串键默认写成
     * <em>不带引号</em> 的形式（{@code {1:114150}}），产出的是**非法 JSON**——
     * 文件扩展名是 .json 却让任何严格解析器（Jackson / json.load / jq）直接报错。
     * JSON 对象的键本就是字符串，用 Integer 作键在这里没有收益，只会让写出物不合规。
     * 旧文件里的 {@code {1:…}} 仍可被 fastjson2 宽松解析，读取侧向后兼容。
     */
    private Map<String, Long> chapterDurations;

    /** 协作取消信号：worker 每章迭代开头检查，当前章完成后停 */
    private volatile boolean cancelRequested;

    /** 预算熔断信号：累计 token 达硬上限后由 LlmBudgetFuse 置位，worker 当前章完成即停（与协作取消同通道） */
    private volatile boolean budgetExceeded;

    /** 本作业累计 token 用量（网关逐调用累加，观测用，随 job-status.json 落盘） */
    private volatile long tokensUsed;

    /**
     * 批序号（1 起）。外部首发 = 1；自动续批时父作业 +1（见 {@code StoryJobService.scheduleNextBatchIfNeeded}）。
     * 用途：run-plan.max-batches 的上限判定，以及让作业状态直接读出"这是第几批"——
     * 此前只能靠人去数 generation-records 里的批次区间（实测 26 次提交 / 20 个唯一区间）。
     */
    private volatile int batchRound = 1;

    /**
     * 章节计划审批门的挂起信息（非挂起态为 null）。
     * awaitingPlanApprovalAtMs：进入挂起的时刻（epoch 毫秒）；
     * planApprovalDeadlineMs：裁决截止时刻，前端据此倒计时，超时由审批服务按配置放行或中止；
     * approvalRound：本作业已完成的人工裁决轮次（超时自动放行不计入；观测用，也用于区分"人改过"与"没人管"）
     */
    private volatile Long awaitingPlanApprovalAtMs;

    private volatile Long planApprovalDeadlineMs;

    private volatile int approvalRound;

    /**
     * 待裁决的章节计划：与挂起态**一次性交接**（2026-09-28），支撑"状态可见 ⇒ 计划可读"不变式——
     * 此前状态由审批门节点置位、计划由服务层登记，两者非原子，中间的微窗口里状态查询会读到 null
     * （机器繁忙时稳定复现）。transient：作业状态文件只承载标量观测字段，计划本体另有 runDir 落盘
     */
    private transient volatile ChapterPlanAggregate awaitingPlan;

    public GenerationJob(String jobId) {
        this.jobId = jobId;
        this.status = JobStatus.CREATED;
        this.startedAtMs = System.currentTimeMillis();
        this.chapterDurations = new HashMap<>();
    }

    /** 仅 CREATED 可进入 RUNNING ; 终态（含排队期已取消）拒绝，防止排队期取消被覆盖 */
    public synchronized boolean markRunning() {
        if (status != JobStatus.CREATED) {
            return false;
        }
        status = JobStatus.RUNNING;
        return true;
    }

    /**
     * 进入章节计划审批挂起态（仅 RUNNING 可进入）。
     * 置位后 worker 会归还线程，作业在内存注册表中等待裁决，不占用 jobExecutor
     */
    public synchronized boolean markAwaitingApproval(long deadlineMs) {
        return markAwaitingApproval(deadlineMs, null);
    }

    /**
     * 进入审批挂起态并交接待裁决计划（仅 RUNNING 可进入）。
     * **先写计划字段再置状态**：volatile 写序保证"读到 AWAITING_APPROVAL 的读者必然也能读到计划"，
     * 使 {@code pendingChapterPlan} 不再受"服务层登记尚未完成"的时序影响
     */
    public synchronized boolean markAwaitingApproval(long deadlineMs, ChapterPlanAggregate pendingPlan) {
        if (status != JobStatus.RUNNING) {
            return false;
        }
        this.awaitingPlan = pendingPlan;
        status = JobStatus.AWAITING_APPROVAL;
        this.awaitingPlanApprovalAtMs = System.currentTimeMillis();
        this.planApprovalDeadlineMs = deadlineMs;
        return true;
    }

    /**
     * 退出审批等待（仅 AWAITING_APPROVAL 可进入）：回到 CREATED 让同一作业复用，
     * 从而复用同一个 jobId 与同一个 run 目录继续跑剩余阶段。
     * 人工通过 / 人工驳回 / 超时放行 / 超时中止四条出路都经此迁移，
     * 保证状态机不出现第四种终局
     *
     * <p>本方法**只做状态迁移、不做计数**：计数与否取决于"是不是人做的决定"，
     * 由 {@link #recordHumanApproval()} 单独承担，避免超时放行被误记为人工裁决
     */
    public synchronized boolean exitApprovalWait() {
        if (status != JobStatus.AWAITING_APPROVAL) {
            return false;
        }
        status = JobStatus.CREATED;
        this.awaitingPlanApprovalAtMs = null;
        this.planApprovalDeadlineMs = null;
        this.awaitingPlan = null;
        return true;
    }

    /**
     * 记一次人工裁决（仅人工 approve 路径调用）。
     * 用途：区分"人看过并放行/改过"与"没人管、被超时自动放行"——
     * 前者说明计划质量被人工认可，后者说明审批门可能纯属流程负担，观测层据此判断门是否值得开
     */
    public synchronized void recordHumanApproval() {
        this.approvalRound++;
    }

    /** 是否处于等待人工裁决（审批门挂起中） */
    public boolean isAwaitingApproval() {
        return status == JobStatus.AWAITING_APPROVAL;
    }

    public synchronized void updateProgress(String stage, int currentChapter, int totalChapters, String storyDirName) {
        this.currentStage = stage;
        this.currentChapter = currentChapter;
        this.totalChapters = totalChapters;
        if (storyDirName != null) {
            this.storyDirName = storyDirName;
        }
    }

    public synchronized void recordChapterDuration(int chapterNo, long durationMs) {
        // 键转字符串：保证 job-status.json 是合法 JSON（见字段注释）
        chapterDurations.put(String.valueOf(chapterNo), durationMs);
    }

    /** 置位取消信号；终态返回 false（取消不再受理） */
    public synchronized boolean requestCancel() {
        if (status == JobStatus.FAILED || status == JobStatus.COMPLETED || status == JobStatus.CANCELLED) {
            return false;
        }
        cancelRequested = true;
        return true;
    }

    /** 置位预算熔断信号；终态返回 false */
    public synchronized boolean requestBudgetExceeded() {
        if (status == JobStatus.FAILED || status == JobStatus.COMPLETED || status == JobStatus.CANCELLED) {
            return false;
        }
        budgetExceeded = true;
        return true;
    }

    /** 仅 RUNNING 可进入 CANCELLING */
    public synchronized boolean markCancelling() {
        if (status != JobStatus.RUNNING) {
            return false;
        }
        status = JobStatus.CANCELLING;
        return true;
    }

    public synchronized void markCancelled() {
        finishedAtMs = System.currentTimeMillis();
        status = JobStatus.CANCELLED;
    }

    public synchronized void markCompleted(String storyDirName) {
        if (storyDirName != null) {
            this.storyDirName = storyDirName;
        }
        finishedAtMs = System.currentTimeMillis();
        status = JobStatus.COMPLETED;
    }

    public synchronized void markFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        finishedAtMs = System.currentTimeMillis();
        status = JobStatus.FAILED;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public boolean isBudgetExceeded() {
        return budgetExceeded;
    }
}
