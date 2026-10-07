package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class ChapterSaveResponseDTO {

    /** 正文是否已落盘（保存失败直接返回 HTTP 错误，正常响应恒为 true） */
    private boolean saved;

    /** 编辑回流是否成功重跑摘要并更新记忆/向量索引（false 时看 warning） */
    private boolean summaryUpdated;

    /** 重跑出的新摘要是否为残缺记忆（三级降级只抢救到 summary，账本状态/伏笔账缺失） */
    private boolean partial;

    /** 回流过程的警告（章节号越界/正文过短/回流失败原因）；null=无异常 */
    private String warning;
}
