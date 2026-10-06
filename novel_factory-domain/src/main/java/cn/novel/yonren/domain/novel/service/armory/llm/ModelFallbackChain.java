package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景级模型降级链：把「主模型失败就整批终止」变成「按序换模型再试一次」。
 *
 * <p><b>为什么换模型有效</b>：实测百炼（DashScope）的免费额度<em>按模型 endpoint 分别计量</em>——
 * 快照名 {@code deepseek-v4-pro-0813} 37/37 成功，而裸别名 {@code deepseek-v4-pro} 报
 * {@code AllocationQuota.FreeTierOnly}。所以"主模型没额度"不等于"账号没额度"，
 * 换一个仍有额度的同族模型即可继续，这比整批终止便宜得多。
 *
 * <p><b>链的构成</b>：场景主模型 → 该场景 {@code model-fallbacks} 条目 → {@code default} 条目。
 * 备选模型只替换<em>模型名</em>，其余参数（maxTokens / temperature / enableThinking /
 * baseUrl / apiKey / completionsPath）全部沿用主模型——降级只应改变"用哪个模型"，
 * 顺带改温度或端点会让副作用不可解释。
 *
 * <p>本类只负责<b>构链</b>（纯函数，可单测）；是否真的换下一个由网关的分流判据决定
 * （见 {@code SpringAiLlmGateway#shouldFallback}，2026-09-30 起口径为"除内容审计外一律换模型"）——
 * 错误类型不对就立刻上抛，不做无谓尝试（每次尝试都是一次真金白银的调用）。
 */
public final class ModelFallbackChain {

    /** 全局兜底键：任何场景的链尾部都会并入该键下的模型 */
    public static final String DEFAULT_KEY = "default";

    /** 降级链最大长度（含主模型）：防止 yml 写成长链导致一次失败烧掉整串调用的费用 */
    public static final int MAX_CHAIN_LENGTH = 3;

    private ModelFallbackChain() {
    }

    /**
     * 构建降级链。主模型为 null（无可用配置）时返回空表——调用方据此直接失败，不做无谓尝试。
     *
     * @param module  请求级模型配置（读 modelFallbacks）
     * @param scene   本次调用的场景（取其 configKey 查链）
     * @param primary 该场景已解析出的主模型配置（已应用运行时覆盖）
     */
    public static List<StoryVO.Module.ChatModel> build(StoryVO.Module module, ModelScene scene,
                                                       StoryVO.Module.ChatModel primary) {
        List<StoryVO.Module.ChatModel> chain = new ArrayList<>();
        if (primary == null || StringUtils.isBlank(primary.getModel())) {
            return chain;
        }
        chain.add(primary);
        for (String candidate : candidateNames(module, scene)) {
            if (chain.size() >= MAX_CHAIN_LENGTH) {
                break;
            }
            if (candidate.equals(primary.getModel())) {
                continue;
            }
            boolean duplicated = chain.stream()
                    .anyMatch(m -> candidate.equals(m.getModel()));
            if (duplicated) {
                continue;
            }
            chain.add(copyWithModel(primary, candidate));
        }
        return chain;
    }

    /**
     * 备选模型名清单：场景条目在前、{@code default} 条目在后（场景更具体，优先尝试）。
     * 保持顺序并对同名去重——链是有序语义，用 List 而非 Set 收集。
     */
    private static List<String> candidateNames(StoryVO.Module module, ModelScene scene) {
        Map<String, List<String>> fallbacks = module == null ? null : module.getModelFallbacks();
        if (fallbacks == null || fallbacks.isEmpty()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        // 必须先判 scene 是否为 null 再查表：**不可变 Map（Map.of / yml 绑定的结果）对 null 键会抛 NPE**，
        // 而统一模型模式下场景语境可能为 null。这里不查表、直接跳过，不是"顺手防御"而是必要保护
        if (scene != null) {
            addAll(ordered, fallbacks.get(scene.getConfigKey()));
        }
        addAll(ordered, fallbacks.get(DEFAULT_KEY));
        List<String> names = new ArrayList<>();
        for (String name : ordered) {
            if (StringUtils.isNotBlank(name)) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private static void addAll(Set<String> sink, List<String> names) {
        if (names != null) {
            sink.addAll(names);
        }
    }

    /** 复制主模型参数、只替换模型名：降级只改变"用哪个模型"，其余参数必须与主模型一致 */
    static StoryVO.Module.ChatModel copyWithModel(StoryVO.Module.ChatModel source, String model) {
        StoryVO.Module.ChatModel copy = new StoryVO.Module.ChatModel();
        copy.setModel(model);
        copy.setMaxTokens(source.getMaxTokens());
        copy.setTemperature(source.getTemperature());
        copy.setEnableThinking(source.getEnableThinking());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setCompletionsPath(source.getCompletionsPath());
        return copy;
    }
}
