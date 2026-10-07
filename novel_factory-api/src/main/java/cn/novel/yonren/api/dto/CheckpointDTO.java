package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 检查点概览 DTO：透出检查点元数据供版本管理界面渲染。
 * 对应 CheckpointEntity 的可观测子集；filesManifest 仅列数量，避免大清单冗余传输
 */
@Data
public class CheckpointDTO {

    /** 检查点标识：cp-{yyyyMMddHHmmss}-{0000N}（回滚接口的路径参数） */
    private String checkpointId;

    /** 全局单调版本号（环形保留排序 / 回收最旧依据） */
    private int versionNo;

    /** 快照命名词：手动快照为用户输入，AUTO 为 "auto-"+versionNo */
    private String name;

    /** 来源类型：AUTO（段/批边界自动）/ MANUAL（手动命名） */
    private String type;

    /** 快照时的最大章号（回滚后从 chapterCount+1 续写） */
    private int chapterCount;

    /** 纳入快照的文件数（filesManifest 只传数量，避免大清单冗余传输） */
    private int fileCount;

    /** 创建时刻（epoch 毫秒） */
    private long createdAtMs;

    /** 是否为最近一次回滚落点（restore 成功后的检查点为 true） */
    private boolean current;
}