package cn.novel.yonren.domain.novel.service.armory;

import cn.novel.yonren.api.dto.LlmConfigDTO;
import cn.novel.yonren.api.dto.LlmConfigSaveRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.types.enums.ModelScene;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
@Component
public class LlmRuntimeConfig {

    /** 覆盖文件：相对进程工作目录（与 usage 记账目录 data/log 同层） */
    private static final Path DEFAULT_FILE = Path.of("data", "llm-config-override.json");

    @Data
    public static class Overlay {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Long maxTokens;
        /** 分场景覆盖：key=ModelScene.configKey；null/空表=无场景覆盖 */
        private Map<String, SceneOverlay> scenes;

        public boolean isEmpty() {
            return StringUtils.isBlank(baseUrl) && StringUtils.isBlank(apiKey)
                    && StringUtils.isBlank(model) && maxTokens == null
                    && (scenes == null || scenes.isEmpty());
        }
    }

    /** 单场景覆盖条目：空字段表示该字段不覆盖（回退全局覆盖/静态配置） */
    @Data
    public static class SceneOverlay {
        private String model;
        private Long maxTokens;
        private Double temperature;

        public boolean isEmpty() {
            return StringUtils.isBlank(model) && maxTokens == null && temperature == null;
        }
    }

    private final Path file;
    private volatile Overlay overlay;

    public LlmRuntimeConfig() {
        this(DEFAULT_FILE);
    }

    public LlmRuntimeConfig(Path file) {
        this.file = file;
        this.overlay = loadFromDisk();
    }

    @PostConstruct
    void init() {
        if (!overlay.isEmpty()) {
            log.info("LLM 运行时覆盖已加载：{}（baseUrl={}，model={}，hasKey={}）",
                    file, overlay.getBaseUrl(), overlay.getModel(), StringUtils.isNotBlank(overlay.getApiKey()));
        }
    }

    /** 保存/合并覆盖：request 非空字段覆盖，apiKey 空则保留现值；reset=true 清空全部（含场景覆盖） */
    public synchronized LlmConfigDTO save(LlmConfigSaveRequestDTO request) {
        Overlay next = new Overlay();
        if (request.isReset()) {
            overlay = next;
            persist(next);
            log.info("LLM 运行时覆盖已清空，回退静态配置");
            return view();
        }
        Overlay current = overlay == null ? new Overlay() : overlay;
        if (StringUtils.isNotBlank(request.getBaseUrl())) {
            next.setBaseUrl(request.getBaseUrl().trim());
        } else {
            next.setBaseUrl(current.getBaseUrl());
        }
        if (StringUtils.isNotBlank(request.getApiKey())) {
            next.setApiKey(request.getApiKey().trim());
        } else {
            next.setApiKey(current.getApiKey());
        }
        if (StringUtils.isNotBlank(request.getModel())) {
            next.setModel(request.getModel().trim());
        } else {
            next.setModel(current.getModel());
        }
        next.setMaxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : current.getMaxTokens());
        next.setScenes(mergeScenes(current, request));
        overlay = next;
        persist(next);
        log.info("LLM 运行时覆盖已保存：{}（场景覆盖 {} 项）", next,
                next.getScenes() == null ? 0 : next.getScenes().size());
        return view();
    }

    /**
     * 场景覆盖合并：resetScenes=true 或 request.scenes 为空表 → 清空；
     * request.scenes=null → 保持现状（全局字段保存请求不带 scenes，不误伤场景覆盖）；
     * 否则全量替换（前端矩阵整表提交，条目内空字段=清除该字段覆盖）。
     * 未知场景 key（非 ModelScene.configKey）丢弃并告警——存进去也永远不会被路由匹配
     */
    private static Map<String, SceneOverlay> mergeScenes(Overlay current, LlmConfigSaveRequestDTO request) {
        if (request.isResetScenes()) {
            return null;
        }
        Map<String, LlmConfigSaveRequestDTO.SceneOverride> incoming = request.getScenes();
        if (incoming == null) {
            return current.getScenes();
        }
        Set<String> validKeys = Arrays.stream(ModelScene.values())
                .map(ModelScene::getConfigKey).collect(Collectors.toSet());
        Map<String, SceneOverlay> merged = new LinkedHashMap<>();
        for (Map.Entry<String, LlmConfigSaveRequestDTO.SceneOverride> entry : incoming.entrySet()) {
            if (!validKeys.contains(entry.getKey())) {
                log.warn("场景覆盖含未知场景 key: {}（非 ModelScene.configKey），已丢弃", entry.getKey());
                continue;
            }
            LlmConfigSaveRequestDTO.SceneOverride in = entry.getValue();
            if (in == null || in.isEmpty()) {
                continue;
            }
            SceneOverlay so = new SceneOverlay();
            so.setModel(StringUtils.trimToNull(in.getModel()));
            so.setMaxTokens(in.getMaxTokens());
            so.setTemperature(in.getTemperature());
            merged.put(entry.getKey(), so);
        }
        return merged.isEmpty() ? null : merged;
    }

    /** apiKey 覆盖落到 module（baseUrl + apiKey）；无覆盖原样返回 */
    public StoryVO.Module applyApi(StoryVO.Module module) {
        Overlay o = overlay;
        if (module == null || o == null || (StringUtils.isBlank(o.getBaseUrl()) && StringUtils.isBlank(o.getApiKey()))) {
            return module;
        }
        StoryVO.Module.AiApi original = module.getAiApi();
        StoryVO.Module.AiApi aiApi = new StoryVO.Module.AiApi();
        aiApi.setBaseUrl(StringUtils.isNotBlank(o.getBaseUrl()) ? o.getBaseUrl() : original == null ? null : original.getBaseUrl());
        aiApi.setApiKey(StringUtils.isNotBlank(o.getApiKey()) ? o.getApiKey() : original == null ? null : original.getApiKey());
        if (original != null) {
            aiApi.setCompletionsPath(original.getCompletionsPath());
        }
        StoryVO.Module copy = new StoryVO.Module();
        copy.setAiApi(aiApi);
        copy.setChatModel(module.getChatModel());
        copy.setEmbeddingApi(module.getEmbeddingApi());
        copy.setUnifiedModelEnabled(module.getUnifiedModelEnabled());
        copy.setSceneModels(module.getSceneModels());
        // 场景级模型降级链必须随拷贝走——否则一旦存在运行时覆盖（前端设置面板），
        // 降级链就被静默丢弃，挂机时的"换模型救调用"能力会无声失效
        copy.setModelFallbacks(module.getModelFallbacks());
        return copy;
    }

    /** model/maxTokens 覆盖到已解析的场景模型（unified 或 scene）；无覆盖原样返回 */
    public StoryVO.Module.ChatModel applyModel(StoryVO.Module.ChatModel selected) {
        Overlay o = overlay;
        if (selected == null || o == null || (StringUtils.isBlank(o.getModel()) && o.getMaxTokens() == null)) {
            return selected;
        }
        StoryVO.Module.ChatModel copy = new StoryVO.Module.ChatModel();
        // 场景级 baseUrl/apiKey 必须原样保留（第二模型族如 chapter-judge 依赖它路由到异供应商）：
        // 覆盖只作用于 model/maxTokens，绝不吞地址与密钥，否则覆盖后评审静默回退主族
        copy.setBaseUrl(selected.getBaseUrl());
        copy.setApiKey(selected.getApiKey());
        copy.setCompletionsPath(selected.getCompletionsPath());
        copy.setModel(StringUtils.isNotBlank(o.getModel()) ? o.getModel() : selected.getModel());
        copy.setMaxTokens(o.getMaxTokens() != null ? o.getMaxTokens() : selected.getMaxTokens());
        copy.setTemperature(selected.getTemperature());
        copy.setEnableThinking(selected.getEnableThinking());
        return copy;
    }

    /**
     * 场景感知的模型覆盖：先按全局覆盖合成（applyModel(selected)），再叠加该场景的覆盖条目——
     * 按字段优先级 场景覆盖 > 全局覆盖 > 静态。场景未配置覆盖时与全局路径完全一致
     */
    public StoryVO.Module.ChatModel applyModel(ModelScene scene, StoryVO.Module.ChatModel selected) {
        StoryVO.Module.ChatModel merged = applyModel(selected);
        SceneOverlay so = sceneOverlay(scene);
        if (so == null) {
            return merged;
        }
        StoryVO.Module.ChatModel copy = new StoryVO.Module.ChatModel();
        copy.setBaseUrl(merged.getBaseUrl());
        copy.setApiKey(merged.getApiKey());
        copy.setCompletionsPath(merged.getCompletionsPath());
        copy.setEnableThinking(merged.getEnableThinking());
        copy.setModel(StringUtils.isNotBlank(so.getModel()) ? so.getModel() : merged.getModel());
        copy.setMaxTokens(so.getMaxTokens() != null ? so.getMaxTokens() : merged.getMaxTokens());
        copy.setTemperature(so.getTemperature() != null ? so.getTemperature() : merged.getTemperature());
        return copy;
    }

    /** 该场景的运行时覆盖条目；场景为 null 或未覆盖时返回 null */
    private SceneOverlay sceneOverlay(ModelScene scene) {
        Overlay o = overlay;
        if (scene == null || o == null || o.getScenes() == null) {
            return null;
        }
        SceneOverlay so = o.getScenes().get(scene.getConfigKey());
        return so == null || so.isEmpty() ? null : so;
    }

    /** 当前覆盖视图（apiKey 只给掩码） */
    public LlmConfigDTO view() {
        Overlay o = overlay;
        LlmConfigDTO dto = new LlmConfigDTO();
        if (o == null || o.isEmpty()) {
            dto.setConfigured(false);
            return dto;
        }
        dto.setConfigured(true);
        dto.setBaseUrl(o.getBaseUrl());
        dto.setModel(o.getModel());
        dto.setMaxTokens(o.getMaxTokens());
        if (StringUtils.isNotBlank(o.getApiKey())) {
            dto.setHasKey(true);
            dto.setApiKeyMasked(mask(o.getApiKey()));
        }
        return dto;
    }

    /**
     * 全量视图：全局覆盖视图 + 场景模型矩阵（静态生效值 + 运行时覆盖 + 合成生效值）。
     * 静态侧取 yml 绑定的 StoryProperties.module；unifiedModelEnabled=true 时矩阵展示
     * "全部走统一模型"的提示，但行内静态值仍按 scene-models 回落逻辑给出（供切换开关后参考）
     */
    public LlmConfigDTO view(StoryProperties statics) {
        LlmConfigDTO dto = view();
        StoryVO.Module module = statics == null ? null : statics.getModule();
        if (module == null) {
            return dto;
        }
        dto.setUnifiedModelEnabled(!Boolean.FALSE.equals(module.getUnifiedModelEnabled()));
        dto.setUnified(toModelView(module.getChatModel()));
        dto.setEmbedding(toEmbeddingView(module.getEmbeddingApi()));

        List<LlmConfigDTO.SceneView> rows = new ArrayList<>();
        for (ModelScene scene : ModelScene.values()) {
            LlmConfigDTO.SceneView row = new LlmConfigDTO.SceneView();
            row.setKey(scene.getConfigKey());
            row.setLabel(scene.getLabel());
            StoryVO.Module.ChatModel staticScene = module.getSceneModels() == null
                    ? null : module.getSceneModels().get(scene.getConfigKey());
            row.setSceneConfigured(staticScene != null);
            row.setIndependentApi(staticScene != null
                    && (StringUtils.isNotBlank(staticScene.getBaseUrl())
                        || StringUtils.isNotBlank(staticScene.getApiKey())));
            StoryVO.Module.ChatModel staticBase = staticScene != null ? staticScene : module.getChatModel();
            if (staticBase != null) {
                row.setStaticModel(staticBase.getModel());
                row.setStaticMaxTokens(staticBase.getMaxTokens());
                row.setStaticTemperature(staticBase.getTemperature());
            }
            SceneOverlay so = sceneOverlay(scene);
            if (so != null) {
                row.setOverridden(true);
                row.setOverrideModel(so.getModel());
                row.setOverrideMaxTokens(so.getMaxTokens());
                row.setOverrideTemperature(so.getTemperature());
            }
            rows.add(row);
        }
        dto.setScenes(rows);
        return dto;
    }

    private static LlmConfigDTO.ModelView toModelView(StoryVO.Module.ChatModel chatModel) {
        if (chatModel == null) {
            return null;
        }
        LlmConfigDTO.ModelView view = new LlmConfigDTO.ModelView();
        view.setModel(chatModel.getModel());
        view.setMaxTokens(chatModel.getMaxTokens());
        view.setTemperature(chatModel.getTemperature());
        view.setEnableThinking(chatModel.getEnableThinking());
        return view;
    }

    /** Embedding 分区只读视图（不参与运行时覆盖，仅透出 yml 静态配置与掩码） */
    private static LlmConfigDTO.EmbeddingView toEmbeddingView(StoryVO.Module.EmbeddingApi embeddingApi) {
        if (embeddingApi == null) {
            return null;
        }
        LlmConfigDTO.EmbeddingView view = new LlmConfigDTO.EmbeddingView();
        view.setConfigured(true);
        view.setModel(embeddingApi.getModel());
        view.setBaseUrl(embeddingApi.getBaseUrl());
        if (StringUtils.isNotBlank(embeddingApi.getApiKey())) {
            view.setHasKey(true);
            view.setApiKeyMasked(mask(embeddingApi.getApiKey()));
        }
        view.setDimensions(embeddingApi.getDimensions());
        return view;
    }

    private static String mask(String apiKey) {
        if (apiKey.length() <= 8) {
            return "***";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }

    private void persist(Overlay o) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(o), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("LLM 运行时覆盖落盘失败（仅影响本次设置持久化，不影响内存生效）：{}", e.getMessage());
        }
    }

    private Overlay loadFromDisk() {
        try {
            if (!Files.isRegularFile(file)) {
                return new Overlay();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(content)) {
                return new Overlay();
            }
            Overlay loaded = JSON.parseObject(content, Overlay.class);
            return loaded == null ? new Overlay() : loaded;
        } catch (Exception e) {
            log.warn("LLM 运行时覆盖加载失败，忽略（回退静态配置）：{}", e.getMessage());
            return new Overlay();
        }
    }
}