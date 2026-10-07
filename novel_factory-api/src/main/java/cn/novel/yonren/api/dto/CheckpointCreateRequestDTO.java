package cn.novel.yonren.api.dto;

import lombok.Data;

/**
 * 手动快照请求：name 必填（回滚定位的命名词）
 */
@Data
public class CheckpointCreateRequestDTO {

    private String name;

}