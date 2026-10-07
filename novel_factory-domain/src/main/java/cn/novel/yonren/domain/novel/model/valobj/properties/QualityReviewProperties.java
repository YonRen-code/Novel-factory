package cn.novel.yonren.domain.novel.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "story.quality-review")
public class QualityReviewProperties {

    /** 总开关：关闭后不评分、不写趋势文件（零 LLM 成本） */
    private boolean enabled = true;

    /** 评分窗口长度（章）：每完成这么多章评一次 */
    private int intervalChapters = 10;

    /**
     * 单次窗口内的抽样章数：在窗口内均匀取值，避免只评最近几章而漏掉窗口前段。
     * 达到或超过窗口长度则逐章全评（小窗口下没必要抽样）。
     */
    private int sampleSize = 3;

    /** 单章送入评分的正文字符上限（超长章按头部截断，避免把 prompt 撑爆） */
    private int maxChapterChars = 6000;

    /** 量表版本号：任何影响分数的改动都必须递增它 */
    private String rubricVersion = "v1";
}
