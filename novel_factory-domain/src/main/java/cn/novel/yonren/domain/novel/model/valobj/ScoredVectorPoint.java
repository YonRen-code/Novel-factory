package cn.novel.yonren.domain.novel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量库检索命中：score 语义由距离度量决定（本项目 Cosine，越大越相近）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScoredVectorPoint {

    /** 命中点的业务 id（与写入侧 VectorPoint.id 一致） */
    private String id;

    /** 相似度得分（Cosine，越大越相近），用于门槛过滤与降序排序 */
    private double score;

    /** 业务元数据（正文片段、来源文件名等），命中后按它取内容与归类 */
    private Map<String, String> payload;

}
