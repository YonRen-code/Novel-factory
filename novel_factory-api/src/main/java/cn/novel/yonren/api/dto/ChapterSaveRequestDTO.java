package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class ChapterSaveRequestDTO {

    /** 人工编辑后的整章正文（保存后触发摘要回流，保持账本/向量索引与正文一致） */
    private String content;

}
