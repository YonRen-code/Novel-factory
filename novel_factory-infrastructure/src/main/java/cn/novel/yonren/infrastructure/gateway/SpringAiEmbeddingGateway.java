package cn.novel.yonren.infrastructure.gateway;

import cn.novel.yonren.domain.novel.adapter.llm.EmbeddingGateway;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 网关的 Spring AI 适配实现：与聊天网关（SpringAiLlmGateway）同构——
 * 按 baseUrl|apiKey|model 缓存单例模型客户端，供应商细节收敛于此。
 * 失败语义（配合消费方快速失败终止作业）：限流/5xx/网络 IO 等瞬时失败做有界重试（默认 3 次，1s 起线性退避），
 * 耗尽后原样上抛；4xx 确定性拒绝（欠费/鉴权/参数）不浪费重试直接上抛。
 * 不做 usage 记账（embedding 量级小且计费按 token 极低），异常向上抛由编排层终止。
 */
@Component
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingGateway.class);

    /** 瞬时失败重试退避基数：第 n 次失败后等待 n * 1s 再试（1s、2s） */
    private static final long RETRY_BACKOFF_MS = 1000L;

    /** 单条向量化输入安全字符上限：仅截断 embedding 输入，调用方落库的 payload 原文不受影响。
     * 2026-09-25 按 text-embedding-v4 重校：单条上限 **16000 tokens**（实测 12000 字符
     * = 10726 tokens 通过，20000 字符 ≈ 17800 tokens 被拒 400 InvalidParameter）。
     * 8000 字符 ≈ 7150 tokens，余量 2.2 倍——比前一模型（qwen3.7-flash 实测 >10 万字符可过）紧得多，
     * 但仍远高于管线实际最大输入（章节记忆实测最长 976 字符/平均 421），保留防呆意义。
     * 历史：智谱 embedding-3 单条约 3072 tokens（实测 4294 字符被拒）→ 2000 字符；
     * qwen3.7-flash >10 万字符 → 8000；v4 16000 tokens → 8000 仍安全。换模型务必重校此值 */
    static final int MAX_EMBED_INPUT_CHARS = 8000;
    private final ConcurrentHashMap<String, OpenAiEmbeddingModel> modelCache = new ConcurrentHashMap<>();

    /** 瞬时失败最大尝试次数（含首次）；spring.ai.retry.embedding-max-attempts 可调，1=关闭重试 */
    private final int maxAttempts;

    /** 出网请求工厂（连接/读取超时）：避免供应商静默不回包时向量化调用永久阻塞作业 */
    private final ClientHttpRequestFactory requestFactory;

    public SpringAiEmbeddingGateway(@Value("${spring.ai.retry.embedding-max-attempts:3}") int maxAttempts,
                                    @Value("${novel.llm.connect-timeout-seconds:20}") int connectTimeoutSeconds,
                                    @Value("${novel.llm.read-timeout-seconds:600}") int readTimeoutSeconds) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.requestFactory = HttpTimeouts.requestFactory(connectTimeoutSeconds, readTimeoutSeconds);
    }

    @Override
    public List<float[]> embed(StoryVO.Module module, List<String> texts) {
        if (module == null || module.getEmbeddingApi() == null) {
            throw new IllegalStateException("未配置 embedding-api（yml story.module.embedding-api），无法向量化");
        }
        OpenAiEmbeddingModel model = embeddingModel(module);
        List<String> capped = capInputLength(texts);
        // 分批：供应商对**单次请求的条数**另有上限——实测 text-embedding-v3 为 10，
        // 一次发 390 条会被直接拒（400 batch size is invalid），而参考资料索引是**硬依赖**（失败即终止作业）。
        // 分批对调用方完全透明：返回顺序与入参一一对应。
        int batchSize = resolveBatchSize(module);
        List<float[]> vectors = new ArrayList<>(capped.size());
        for (int start = 0; start < capped.size(); start += batchSize) {
            int end = Math.min(capped.size(), start + batchSize);
            EmbeddingResponse response = callWithRetry(model, module, capped.subList(start, end));
            for (Embedding embedding : response.getResults()) {
                vectors.add(embedding.getOutput());
            }
        }
        return vectors;
    }

    /** 单次请求输入条数的兜底上限：主流供应商都支持 ≤10，故取 10 作默认（可被 yml 覆盖） */
    static final int DEFAULT_EMBED_BATCH_SIZE = 10;

    /** 批量大小：优先取 yml 的 embedding-api.batch-size，非法值（null/≤0）退回默认（包级可见以便测试） */
    static int resolveBatchSize(StoryVO.Module module) {
        Integer configured = module.getEmbeddingApi() == null
                ? null : module.getEmbeddingApi().getBatchSize();
        return configured == null || configured <= 0 ? DEFAULT_EMBED_BATCH_SIZE : configured;
    }

    /** 逐条截断超限输入：供应商按单条 token 数设限，任一超限即整批拒绝（400-1210），
     *  故事 bible/参考资料大段落随章节增长可越过该上限 */
    private static List<String> capInputLength(List<String> texts) {
        List<String> capped = new ArrayList<>(texts.size());
        for (String text : texts) {
            capped.add(text != null && text.length() > MAX_EMBED_INPUT_CHARS
                    ? text.substring(0, MAX_EMBED_INPUT_CHARS) : text);
        }
        return capped;
    }

    /**
     * 瞬时失败有界重试：仅 {@link TransientAiException}（限流/5xx）与 {@link ResourceAccessException}
     * （连接超时/重置等网络 IO）触发退避重试；确定性失败（4xx 参数/鉴权/欠费）与重试耗尽后
     * 原样上抛，由消费方快速失败终止作业
     */
    private EmbeddingResponse callWithRetry(OpenAiEmbeddingModel model, StoryVO.Module module, List<String> texts) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callModel(model, module, texts);
            } catch (RuntimeException e) {
                last = e;
                if (!isTransientEmbeddingFailure(e) || attempt == maxAttempts) {
                    throw e;
                }
                long backoff = RETRY_BACKOFF_MS * attempt;
                log.warn("embedding 调用瞬时失败（第 {}/{} 次尝试），{}ms 后重试：{}",
                        attempt, maxAttempts, backoff, e.getMessage());
                sleepBeforeRetry(backoff);
            }
        }
        throw last;
    }

    /** 单次模型调用（包级可见供单测 spy 桩替换） */
    EmbeddingResponse callModel(OpenAiEmbeddingModel model, StoryVO.Module module, List<String> texts) {
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder()
                .model(module.getEmbeddingApi().getModel());
        // 维度显式钉死：供应商默认值漂移会让 Qdrant 集合维度静默不匹配（见 EmbeddingApi.dimensions）
        if (module.getEmbeddingApi().getDimensions() != null) {
            options.dimensions(module.getEmbeddingApi().getDimensions());
        }
        return model.call(new EmbeddingRequest(texts, options.build()));
    }

    /** 瞬时/确定性失败分类（包级可见供单测） */
    static boolean isTransientEmbeddingFailure(RuntimeException e) {
        return e instanceof TransientAiException || e instanceof ResourceAccessException;
    }

    void sleepBeforeRetry(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 重试等待被中断", ie);
        }
    }

    OpenAiEmbeddingModel embeddingModel(StoryVO.Module module) {
        StoryVO.Module.EmbeddingApi api = module.getEmbeddingApi();
        String key = api.getBaseUrl() + "|" + api.getApiKey() + "|" + api.getModel();
        return modelCache.computeIfAbsent(key, k -> {
            OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                    .baseUrl(api.getBaseUrl())
                    .apiKey(api.getApiKey());
            if (StringUtils.isNotBlank(api.getEmbeddingsPath())) {
                apiBuilder.embeddingsPath(api.getEmbeddingsPath());
            }
            log.info("Embedding 网关构建模型客户端：baseUrl={}, model={}", api.getBaseUrl(), api.getModel());
            // 1.0.0 无 builder：模型名等默认参数走每次调用的 EmbeddingRequest options
            return new OpenAiEmbeddingModel(apiBuilder
                    .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                    .build());
        });
    }

}
