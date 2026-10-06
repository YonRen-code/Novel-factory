package cn.novel.yonren.domain.novel.adapter.repository;

import cn.novel.yonren.domain.novel.model.valobj.ScoredVectorPoint;
import cn.novel.yonren.domain.novel.model.valobj.VectorPoint;

import java.util.List;

/**
 * 向量存储端口：领域层只认"建集合/写入/相似检索"三个动作，屏蔽 Qdrant 等具体实现。
 * 集合维度与距离度量在首次创建时定死（与 embedding 模型输出维度严格一致）。
 */
public interface VectorStore {

    /** 确保集合存在（已存在则不动） */
    void ensureCollection(String collection, int vectorSize);

    /** 批量写入；同 id 覆盖（幂等摄入的基础） */
    void upsert(String collection, List<VectorPoint> points);

    /** 相似检索：返回按分数降序的前 limit 个点 */
    List<ScoredVectorPoint> search(String collection, float[] queryVector, int limit);

    /** 清空整集合（删除全部点）；本集合后续由下次 ensureCollection+upsert 自动重建 */
    void clear(String collection);

}
