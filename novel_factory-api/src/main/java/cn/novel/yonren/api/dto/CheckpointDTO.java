package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 检查点概览 DTO：透出检查点元数据供版本管理界面渲染。
 * 对应 CheckpointEntity 的可观测子集；filesManifest 仅列数量，避免大清单冗余传输
 */
@Data
public class CheckpointDTO {

    private String checkpointId;

    private int versionNo;

    private String name;

    private String type;

    private int chapterCount;

    private int fileCount;

    private long createdAtMs;

    private boolean current;
}