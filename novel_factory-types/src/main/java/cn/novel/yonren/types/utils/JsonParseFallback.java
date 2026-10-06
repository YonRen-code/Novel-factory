package cn.novel.yonren.types.utils;

import java.util.function.Function;

/**
 * 模型结构化输出解析降级工具：①直接解析；②JsonRepair 修复常见 JSON 病后重解；
 * ③在前者基础上转义字符串值内裸引号后重解；④再叠加键名吞逗号修复后重解。
 * 全败返回 null，由调用方决定后续动作（重试 LLM / 抢救字段 / 抛异常 / fail-soft）。
 * 与 JsonRepair 配套：JsonRepair 负责"修复文本"，本类负责"解析降级的使用模式"
 */
public final class JsonParseFallback {

    private JsonParseFallback() {
    }

    /**
     * 四级解析降级；raw 为 null/blank 或四轮均失败时返回 null（不抛出）
     *
     * @param raw    模型原始输出
     * @param parser 解析函数（如 BeanOutputConverter::convert），异常视为解析失败
     */
    public static <T> T parse(String raw, Function<String, T> parser) {
        T result = safely(raw, parser);
        if (result == null) {
            result = safely(JsonRepair.repair(raw), parser);
        }
        if (result == null) {
            result = safely(JsonRepair.escapeInteriorQuotes(JsonRepair.repair(raw)), parser);
        }
        if (result == null) {
            result = safely(JsonRepair.escapeInteriorQuotes(JsonRepair.fixCommaInKey(JsonRepair.repair(raw))), parser);
        }
        return result;
    }

    private static <T> T safely(String raw, Function<String, T> parser) {
        if (raw == null || raw.isBlank() || parser == null) {
            return null;
        }
        try {
            return parser.apply(raw);
        } catch (Exception e) {
            return null;
        }
    }

}
