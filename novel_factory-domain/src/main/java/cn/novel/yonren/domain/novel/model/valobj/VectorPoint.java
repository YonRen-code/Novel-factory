package cn.novel.yonren.domain.novel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 向量库写入点：id 稳定则 upsert 天然幂等（同 id 覆盖旧点）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorPoint {

    /** 稳定业务 id（fileName#节序 的 UUID 化），重复 upsert 覆盖旧点 */
    private String id;

    /** 向量 */
    private float[] vector;

    /** 业务元数据（检索命中后回源加载全文依赖它） */
    private Map<String, String> payload;

}
