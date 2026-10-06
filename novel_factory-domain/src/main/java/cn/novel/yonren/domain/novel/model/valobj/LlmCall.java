package cn.novel.yonren.domain.novel.model.valobj;

import cn.novel.yonren.types.enums.ModelScene;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次 LLM 调用的领域描述：与具体模型供应商无关。
 * maxTokens/temperature 为覆盖项，null 表示使用 yml 中的供应商默认参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmCall {

    /** 系统提示词，可为 null（无系统消息） */
    private String systemPrompt;

    /** 用户提示词 */
    private String userPrompt;

    /** 覆盖：最大输出 token 数 */
    private Integer maxTokens;

    /** 覆盖：采样温度 */
    private Double temperature;

    /** 调用标签（如 "chapter-plan-第3章"），供网关层 usage 记账归因，可为 null */
    private String label;

    /** 模型路由场景：网关层据此选择 scene-models 中的模型/思考模式；null 时走统一 chat-model */
    private ModelScene scene;

}
