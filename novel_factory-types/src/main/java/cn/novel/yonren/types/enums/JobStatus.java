package cn.novel.yonren.types.enums;


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
