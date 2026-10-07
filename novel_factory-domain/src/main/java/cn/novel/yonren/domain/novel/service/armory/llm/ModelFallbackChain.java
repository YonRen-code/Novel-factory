package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


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
