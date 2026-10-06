package cn.novel.yonren.types.enums;

/**
 * 作业生命周期状态。
 * CANCELLING 是协作取消的中间态：取消已受理，worker 完成当前章后落入 CANCELLED；
 * AWAITING_APPROVAL 是章节计划审批门的挂起态：计划已生成并落盘，作业**已释放线程**等待人工裁决，
 * 裁决/超时后回到 CREATED 复用同一作业继续跑剩余阶段（正文生成 → 链尾持久化）。
 * 终态（FAILED/COMPLETED/CANCELLED）不可再迁移
 */
public enum JobStatus {

    /** 已创建，等待执行（排队中；审批通过后也回到本态重新入队） */
    CREATED,

    /** 执行中 */
    RUNNING,

    /** 取消已受理，当前章完成后停止 */
    CANCELLING,

    /** 章节计划审批门：挂起等待人工裁决（不占 worker 线程），裁决/超时后继续 */
    AWAITING_APPROVAL,

    /** 执行失败 */
    FAILED,

    /** 执行完成 */
    COMPLETED,

    /** 已取消 */
    CANCELLED
}
