package cn.novel.yonren.api.dto;

import lombok.Data;

/**
 * 手动快照请求：name 必填（回滚定位的命名词）
 */
@Data
public class CheckpointCreateRequestDTO {

    /** 手动快照的命名词（回滚定位用；空白会被 400 拒绝） */
    private String name;

}