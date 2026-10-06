package cn.novel.yonren.domain.novel.adapter.llm;

import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;

/**
 * LLM 网关端口：领域层唯一的模型访问出口，屏蔽具体供应商与客户端细节。
 * 实现位于 infrastructure（Spring AI 适配）
 */
public interface LlmGateway {

    /**
     * 执行一次补全并返回文本
     *
     * @param module 请求级供应商/模型配置（baseUrl/apiKey/model/maxTokens）
     * @param call   调用描述（消息与参数覆盖项）
     */
    String complete(StoryVO.Module module, LlmCall call);

}
