package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 检查点实体：故事目录在章边界的可变资产快照元数据，支撑版本回滚。
 * 快照镜像存于 {storyDir}/memory/checkpoints/{checkpointId}/snapshot/，
 * meta.json 落盘本文实体；回滚时按 manifest 还原文件 + 裁剪超出章节的正文。
 * sticky cap（story-meta.json）不入 manifest，跨回滚存活、不抬不减。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointEntity {

    /** 检查点标识：cp-{yyyyMMddHHmmss}-{0000N} */
    private String checkpointId;

    /** 全局单调版本号（环形保留排序 / 回收最旧依据） */
    private int versionNo;

    /** 手动命名；AUTO 可为 "auto-"+versionNo */
    private String name;

    /** 来源类型：AUTO(段/批边界) / MANUAL(手动命名) */
    private cn.novel.yonren.types.enums.CheckpointType type;

    /** 快照时的最大章号（续写 offset；回滚后从 chapterCount+1 续写） */
    private int chapterCount;

    /** 纳入快照的相对 storyDir 路径清单，如 "chapters/chapter-0001.txt"、"memory/summaries.json" */
    private List<String> filesManifest;

    /** 创建时间戳（ms） */
    private long createdAtMs;

    /** 最近一次 restore 落点标记（观测性，非一致性依赖） */
    private boolean isCurrent;
}