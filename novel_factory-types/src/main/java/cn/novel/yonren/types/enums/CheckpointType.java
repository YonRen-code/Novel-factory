package cn.novel.yonren.types.enums;

/**
 * 检查点来源类型：AUTO 由流水线在段/批边界自动打点；MANUAL 由用户在改写前手动命名快照
 */
public enum CheckpointType {
    /** 自动：段/批边界或取消点，name 可为空 */
    AUTO,
    /** 手动：用户命名快照，name 必填 */
    MANUAL
}