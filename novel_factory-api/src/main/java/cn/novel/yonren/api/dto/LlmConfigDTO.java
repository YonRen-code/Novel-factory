package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;

/**
 * LLM 运行时配置视图（GET /api/config/llm 响应）：
 * 前端设置面板读取当前生效的运行时覆盖 + yml 静态配置合成出的场景模型矩阵；
 * apiKey 不回传原文，只给掩码与是否存在标记。
 * configured=false 表示未设置全局覆盖，实际调用仍走 novel-generation.yml 静态配置
 */
@Data
public class LlmConfigDTO {
    /** 接口地址（非机密，原文返回便于表单回显） */
    private String baseUrl;
    /** 是否存在 apiKey 覆盖（区别于空值） */
    private boolean hasKey;
    /** apiKey 掩码（如 sk-***abcd），未设置时为 null */
    private String apiKeyMasked;
    /** 模型名（覆盖字段，未设置时为 null） */
    private String model;
    /** 最大输出 token（覆盖字段，未设置时为 null） */
    private Long maxTokens;
    /** 是否存在运行时覆盖（false = 全部走静态配置） */
    private boolean configured;

    /** 分场景路由总开关（yml module.unified-model-enabled）：true=全场景统一走 chat-model */
    private boolean unifiedModelEnabled;
    /** 统一 chat-model 静态配置（unified-model-enabled=true 或场景未配置时的生效来源） */
    private ModelView unified;
    /** 场景模型矩阵（按 ModelScene 枚举序，含静态生效值与运行时覆盖） */
    private List<SceneView> scenes;

    /** Embedding 向量分区（只读展示：嵌入模型不走运行时覆盖，改 yml 需重启生效） */
    private EmbeddingView embedding;

    /** 单个模型的只读视图（统一 chat-model 用） */
    @Data
    public static class ModelView {
        /** 模型名 */
        private String model;
        /** 最大输出 token（单次生成上限） */
        private Long maxTokens;
        /** 采样温度（0~2，越高越有创造性、越随机；null=走供应商默认） */
        private Double temperature;
        /** 是否开启思考模式（DashScope 等供应商特有；null=不干预走供应商默认） */
        private Boolean enableThinking;
    }

    /**
     * 单个场景的矩阵行：静态生效值（yml scene-models 条目，未配置时回落统一 chat-model）、
     * 运行时覆盖值（data/llm-config-override.json scenes 条目）、合成后的最终生效值。
     * 优先级（按字段）：场景覆盖 > 全局覆盖 > yml 静态（场景条目缺项回落统一 chat-model）
     */
    @Data
    public static class SceneView {
        /** yml scene-models 的键（ModelScene.configKey） */
        private String key;
        /** 中文显示名 */
        private String label;
        /** yml 是否为该场景配置了独立条目（false=静态回落统一 chat-model） */
        private boolean sceneConfigured;
        /** yml 场景条目是否自带 base-url/api-key（第二模型族，如 chapter-judge 指向异供应商） */
        private boolean independentApi;
        /** 静态生效模型（scene 条目 ?? 统一 chat-model） */
        private String staticModel;
        /** 静态生效的最大输出 token（回落规则同 staticModel） */
        private Long staticMaxTokens;
        /** 静态生效采样温度（回落规则同 staticModel） */
        private Double staticTemperature;
        /** 运行时覆盖（未覆盖字段为 null） */
        private String overrideModel;
        /** 运行时覆盖的最大输出 token（未覆盖为 null） */
        private Long overrideMaxTokens;
        /** 运行时覆盖的采样温度（未覆盖为 null） */
        private Double overrideTemperature;
        /** 该场景是否存在运行时覆盖 */
        private boolean overridden;
    }

    /**
     * Embedding 接入的只读视图：yml module.embedding-api 直连建客户端、不参与运行时覆盖
     * （向量召回挂的是独立供应商与账单，覆盖错一处索引就作废，故只展示不开放热改）
     */
    @Data
    public static class EmbeddingView {
        /** 是否已配置 embedding-api（false=向量召回不可用，生成主链路不受影响） */
        private boolean configured;
        /** 嵌入模型名 */
        private String model;
        /** 接口地址（非机密，原文返回便于核对指向） */
        private String baseUrl;
        /** 是否已配置 apiKey */
        private boolean hasKey;
        /** apiKey 掩码，未设置时为 null */
        private String apiKeyMasked;
        /** 向量维度（null=走供应商默认） */
        private Integer dimensions;
    }
}
