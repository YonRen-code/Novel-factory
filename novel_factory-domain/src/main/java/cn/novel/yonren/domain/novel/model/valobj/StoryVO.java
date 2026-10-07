package cn.novel.yonren.domain.novel.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class StoryVO {
    /** 全局默认值：写作语言与目标总章数，随 story-bible 落盘供续写读取 */
    private Defaults defaults;
    /** 模型接入与路由：API 端点、统一/分场景模型、降级链 */
    private Module module;
    /** 生成硬约束：总章数上限及其强制开关 */
    private Constraints constraints;
    /** 故事级一致性开关；由 story-bible/请求明确声明，未声明时不启用条件规则。 */
    private StoryFeatures features;

    @Data
    public static class StoryFeatures {
        /** 是否存在金手指、系统、外挂或其他核心辅助机制。null 表示未声明。 */
        private Boolean hasCheatMechanism;
        /** 已确认的金手指/系统名称，便于审计和 Prompt 渲染。 */
        private String cheatMechanismName;
        /** 金手指使用/有意义提及的最大间隔，默认 3 章。 */
        private Integer cheatUsageInterval = 3;
    }

    @Data
    public static class Defaults {
        /** 写作语言（如 "中文"），落入 story-bible 供各环节与续写对齐 */
        private String language;
        /** 目标总章数，落入 story-bible 供续写读取 */
        private Long totalCount;
    }

    @Data
    public static class Module {
        /** 对话模型供应商接入：根地址、密钥与补全路径（module 级默认端点） */
        private AiApi aiApi;
        /** 统一模型配置：unifiedModelEnabled=true 时全场景使用；分场景缺配时也回退到它 */
        private ChatModel chatModel;
        /** 向量化专用接入（参考资料/记忆语料的 embedding），与对话模型相互独立 */
        private EmbeddingApi embeddingApi;
        // 总开关：true=所有场景统一用 chatModel；false=按 scene-models 分场景路由（未配置的场景回退 chatModel）
        private Boolean unifiedModelEnabled;
        // 分场景模型：key 为 ModelScene.configKey（如 "chapter-plan"），value 为独立模型/思考模式配置
        private Map<String, ChatModel> sceneModels;
        /**
         * 场景级模型降级链（2026-09-16 阶段六）：key 为 ModelScene.configKey，value 为备选模型名列表
         * （按序尝试，仅模型名不同，其余参数沿用该场景主模型）。
         *
         * <p>只在"模型层"失败时启用（额度耗尽 / 模型不存在 / 参数被拒，见
         * {@code LlmErrorClassifier}）——这些情况下重试同一模型永远失败，换模型才有效；
         * 超时/网络类瞬时故障走传输层重试，不消耗降级链。
         *
         * <p>保留键 {@code default}：任何场景的链尾部都会并入它，供"全局兜底"用。
         */
        private Map<String, List<String>> modelFallbacks;

        @Data
        public static class AiApi {
            /** OpenAI 兼容服务根地址（补全路径另由 completionsPath 决定） */
            private String baseUrl;
            /** 供应商 API 密钥（敏感凭据） */
            private String apiKey;
            // OpenAI 兼容补全路径（如智谱为 /chat/completions）；留空走 Spring AI 默认 /v1/chat/completions
            private String completionsPath;
            // Command Code Provider 零数据保留：true 时发送 x-cmd-zdr: 1；无 ZDR 上游时服务端明确失败，不静默降级
            private Boolean zeroDataRetention;
        }

        @Data
        public static class EmbeddingApi {
            /** 向量化服务根地址（OpenAI 兼容），独立于对话 ai-api 的端点 */
            private String baseUrl;
            /** 向量化服务的 API 密钥（敏感凭据） */
            private String apiKey;
            // OpenAI 兼容 embeddings 路径（如智谱为 /embeddings）；留空走 Spring AI 默认 /v1/embeddings
            private String embeddingsPath;
            /** embedding 模型名（换模型即换向量空间，存量向量需重新嵌入） */
            private String model;
            /**
             * 单次请求的**输入条数**上限（2026-09-22 新增）：不同供应商限制不同，
             * 实测 {@code text-embedding-v3} 上限为 10，一次多发直接 400
             * （{@code batch size is invalid, it should not be larger than 10}）并终止作业。
             * 留空走 {@code SpringAiEmbeddingGateway.DEFAULT_EMBED_BATCH_SIZE}。
             */
            private Integer batchSize;
            /**
             * 输出向量维度（2026-09-25 新增）：显式钉死，不依赖供应商默认值——
             * DashScope text-embedding-v4 实测默认 1024 维且支持该参数（2048/768 亦可），
             * 但默认值若被供应商调整，Qdrant 集合维度会静默不匹配。留空则不传（走供应商默认）。
             * 注意：**维度相同不代表向量空间兼容**，换模型仍需重嵌。
             */
            private Integer dimensions;
        }

        @Data
        public static class ChatModel {
            /** 模型名（供应商模型 id，如 qwen3.8-max） */
            private String model;
            // 最大输出 token 数（单次生成上限）
            private Long maxTokens;
            // 采样温度：越高越有创造性、越随机，越低越稳定、确定（0~2）。
            // null=不干预，走供应商默认（DashScope 默认约 1.0，对写作偏随机，建议显式配置）
            private Double temperature;
            // 是否开启思考模式（DashScope 等供应商特有）；null=不干预走供应商默认，false=注入 enable_thinking=false 关闭思考
            private Boolean enableThinking;
            // 场景级 API 覆盖（第二模型族）：非空时优先于 module 级 ai-api；留空则沿用 module 级 base-url/api-key。
            // 仅 scene-models 条目可配（chapter-judge 指向异供应商如 DeepSeek 时使用）
            private String baseUrl;
            /** 场景级密钥覆盖，与 baseUrl 配套；留空沿用 module 级 ai-api 的密钥 */
            private String apiKey;
            // 场景级补全路径覆盖：智谱 GLM 等供应商路径为 /chat/completions（base-url 已含 /v4），
            // 留空沿用 module 级 completionsPath
            private String completionsPath;
        }
    }

    @Data
    public static class Constraints {
        /** 总开关：true 时强制全书总章数上限（maxChapterCount），达到后不再生成 */
        private Boolean enforceChapterLimit;
        /** 全书总章数上限（达到后视为完结，续写将被拒绝） */
        private Integer maxChapterCount;
    }

}
