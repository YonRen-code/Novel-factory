package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景级模型降级链测试。
 *
 * <p>核心不变量：<b>只替换模型名，其余参数一律沿用主模型</b>——
 * 降级只应改变"用哪个模型"；顺带改了温度或端点，副作用的来源就不可解释了。
 */
class ModelFallbackChainTest {

    private static StoryVO.Module.ChatModel primary(String model) {
        StoryVO.Module.ChatModel m = new StoryVO.Module.ChatModel();
        m.setModel(model);
        m.setMaxTokens(16384L);
        m.setTemperature(0.1);
        m.setEnableThinking(false);
        m.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        m.setApiKey("sk-test");
        m.setCompletionsPath("/v1/chat/completions");
        return m;
    }

    private static StoryVO.Module module(Map<String, List<String>> fallbacks) {
        StoryVO.Module module = new StoryVO.Module();
        module.setModelFallbacks(fallbacks);
        return module;
    }

    @Test
    @DisplayName("无配置时链长为 1（只有主模型），退化为原行为")
    void noFallbacksKeepsSingleEntry() {
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(null), ModelScene.CHAPTER_PLAN, primary("qwen3.7-max-2026-05-20"));

        assertEquals(1, chain.size());
        assertEquals("qwen3.7-max-2026-05-20", chain.get(0).getModel());
    }

    @Test
    @DisplayName("场景备选接在主模型之后，且只换模型名、其余参数原样沿用")
    void sceneFallbackPreservesOtherParams() {
        StoryVO.Module.ChatModel primary = primary("qwen3.7-max-2026-05-20");
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(Map.of("chapter-plan", List.of("qwen3.8-flash"))),
                ModelScene.CHAPTER_PLAN, primary);

        assertEquals(2, chain.size());
        StoryVO.Module.ChatModel fallback = chain.get(1);
        assertEquals("qwen3.8-flash", fallback.getModel());
        assertEquals(16384L, fallback.getMaxTokens(), "maxTokens 必须沿用主模型");
        assertEquals(0.1, fallback.getTemperature(), 1e-9);
        assertEquals(false, fallback.getEnableThinking());
        assertEquals(primary.getBaseUrl(), fallback.getBaseUrl());
        assertEquals(primary.getApiKey(), fallback.getApiKey());
        assertEquals(primary.getCompletionsPath(), fallback.getCompletionsPath());
    }

    @Test
    @DisplayName("default 条目接在场景条目之后（场景更具体，优先尝试）")
    void defaultEntryComesAfterSceneEntry() {
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(Map.of(
                        "revise", List.of("deepseek-v4-flash"),
                        ModelFallbackChain.DEFAULT_KEY, List.of("qwen3.8-flash"))),
                ModelScene.CHAPTER_REVISE, primary("qwen3.7-plus-2026-05-26"));

        assertEquals(3, chain.size());
        assertEquals("deepseek-v4-flash", chain.get(1).getModel());
        assertEquals("qwen3.8-flash", chain.get(2).getModel());
    }

    @Test
    @DisplayName("备选与主模型同名时跳过：换了个名字还是同一个模型，没有任何意义")
    void sameAsPrimaryIsSkipped() {
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(Map.of("chapter-content", List.of("qwen3.8-flash"))),
                ModelScene.CHAPTER_CONTENT, primary("qwen3.8-flash"));

        assertEquals(1, chain.size(), "同名备选不得进链");
    }

    @Test
    @DisplayName("重复备选去重且保持顺序（链是有序语义）")
    void duplicatesAreDedupedPreservingOrder() {
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(Map.of(
                        "chapter-plan", List.of("qwen3.8-flash", "qwen3.8-max", "qwen3.8-flash"),
                        ModelFallbackChain.DEFAULT_KEY, List.of("qwen3.8-max", "deepseek-v4-flash"))),
                ModelScene.CHAPTER_PLAN, primary("qwen3.7-max-2026-05-20"));

        // 主 + qwen3.8-flash + qwen3.8-max（已到上限 3）
        assertEquals(ModelFallbackChain.MAX_CHAIN_LENGTH, chain.size());
        assertEquals("qwen3.8-flash", chain.get(1).getModel());
        assertEquals("qwen3.8-max", chain.get(2).getModel());
    }

    @Test
    @DisplayName("链长有上限：yml 写成长链时截断，避免一次失败烧掉一整串调用的费用")
    void chainLengthIsCapped() {
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(Map.of("stage-blueprint", List.of("m1", "m2", "m3", "m4", "m5"))),
                ModelScene.STAGE_BLUEPRINT, primary("m0"));

        assertEquals(ModelFallbackChain.MAX_CHAIN_LENGTH, chain.size());
    }

    @Test
    @DisplayName("主模型缺失（无可用配置）时返回空链，调用方据此直接失败而不是空跑")
    void missingPrimaryYieldsEmptyChain() {
        assertTrue(ModelFallbackChain.build(module(Map.of()), ModelScene.CHAPTER_PLAN, null).isEmpty());

        StoryVO.Module.ChatModel blank = new StoryVO.Module.ChatModel();
        assertTrue(ModelFallbackChain.build(module(Map.of()), ModelScene.CHAPTER_PLAN, blank).isEmpty());
    }

    @Test
    @DisplayName("场景为 null 时仍能用 default 兜底（统一模型模式下无场景语境）")
    void nullSceneFallsBackToDefaultOnly() {
        StoryVO.Module.ChatModel primary = primary("qwen3.8-flash");
        List<StoryVO.Module.ChatModel> chain = ModelFallbackChain.build(
                module(Map.of("chapter-plan", List.of("never-used"), ModelFallbackChain.DEFAULT_KEY, List.of("deepseek-v4-flash"))),
                null, primary);

        assertEquals(2, chain.size());
        assertEquals("deepseek-v4-flash", chain.get(1).getModel());
    }
}
