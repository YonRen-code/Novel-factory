package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * LLM 运行时配置保存请求（PUT /api/config/llm）：
 * 全局字段仅非空生效覆盖；apiKey 留空/空白表示保留现有值（不回显原文，避免被清空）；
 * reset=true 清空全部运行时覆盖（含场景覆盖），回退 novel-generation.yml 静态配置。
 * scenes：按场景覆盖（key=ModelScene.configKey），前端场景矩阵全量提交、后端整体替换——
 * 条目内空字段=清除该字段覆盖（区别于全局字段的"留空保留"）；scenes=null 表示本次不动场景覆盖；
 * resetScenes=true 仅清空场景覆盖（不动全局覆盖），可与全局字段同请求
 */
@Data
public class LlmConfigSaveRequestDTO {
    private String baseUrl;
    private String apiKey;
    private String model;
    private Long maxTokens;
    private boolean reset;
    /** 仅清空分场景覆盖（保留全局覆盖与全局字段语义） */
    private boolean resetScenes;
    /** 分场景覆盖全量替换表；null=本次不触碰场景覆盖（区别于空表=清空） */
    private Map<String, SceneOverride> scenes;

    /** 单场景覆盖条目：任一字段为空=该字段清除覆盖，回退全局覆盖/静态配置 */
    @Data
    public static class SceneOverride {
        private String model;
        private Long maxTokens;
        private Double temperature;

        public boolean isEmpty() {
            return model == null && maxTokens == null && temperature == null;
        }
    }
}
