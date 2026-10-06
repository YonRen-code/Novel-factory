package cn.novel.yonren.domain.novel.adapter.llm;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;

import java.util.List;

/**
 * Embedding 网关端口：文本 → 向量。
 * 本项目聊天与 embedding 供应商拆分，故独立于 LlmGateway 成端口。
 * 实现位于 infrastructure（Spring AI 适配）
 */
public interface EmbeddingGateway {

    /**
     * 批量向量化（供应商支持一次请求多个文本，调用方自行控制批大小）
     *
     * @param module 请求级配置（含 embedding-api 接入块）
     * @param texts  待向量化文本，非空
     * @return 与 texts 同序的向量列表
     */
    List<float[]> embed(StoryVO.Module module, List<String> texts);

}
