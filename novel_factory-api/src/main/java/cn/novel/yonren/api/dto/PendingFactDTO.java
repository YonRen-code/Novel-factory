package cn.novel.yonren.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待人工确认事实 DTO：摘要证据校验未通过的状态事实（疑似编造/漂移），
 * 不入三账本前缀，仅透出供人工裁决；来源章节随条目携带便于核对原文
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingFactDTO {

    /** 事实来源章节号 */
    private int chapterNo;
    /** 实体名称（角色/物品/势力） */
    private String name;
    /** 摘要模型声称的状态 */
    private String status;
    /** 摘要模型给出的证据原文引用（经校验未命中正文，可能为编造） */
    private String evidence;

}
