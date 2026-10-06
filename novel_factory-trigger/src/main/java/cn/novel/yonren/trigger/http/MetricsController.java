package cn.novel.yonren.trigger.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 单体运行观测：汇总 LLM 网关已落盘的 usage JSONL，读取失败时返回空统计。 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private static final Path USAGE_LOG = Path.of("data", "log", "llm-usage.jsonl");

    @GetMapping("/llm")
    public Map<String, Object> llm() {
        long calls = 0, failures = 0, promptChars = 0, responseChars = 0;
        long promptTokens = 0, completionTokens = 0, totalTokens = 0, durationMs = 0;
        Map<String, Long> byModel = new LinkedHashMap<>();
        if (Files.exists(USAGE_LOG)) {
            try {
                for (String line : Files.readAllLines(USAGE_LOG)) {
                    if (line == null || line.isBlank()) continue;
                    try {
                        JSONObject item = JSON.parseObject(line);
                        calls++;
                        if (!item.getBooleanValue("success")) failures++;
                        promptChars += item.getLongValue("promptChars");
                        responseChars += item.getLongValue("responseChars");
                        promptTokens += item.getLongValue("promptTokens");
                        completionTokens += item.getLongValue("completionTokens");
                        totalTokens += item.getLongValue("totalTokens");
                        durationMs += item.getLongValue("durationMs");
                        String model = item.getString("model");
                        if (model != null && !model.isBlank()) byModel.merge(model, 1L, Long::sum);
                    } catch (RuntimeException ignored) {
                        // 允许末尾半行或旧格式记录，不影响其余统计
                    }
                }
            } catch (IOException ignored) {
                // 观测接口 fail-soft，不反噬写作功能
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls", calls);
        result.put("failures", failures);
        result.put("failureRate", calls == 0 ? 0D : (double) failures / calls);
        result.put("promptChars", promptChars);
        result.put("responseChars", responseChars);
        result.put("promptTokens", promptTokens);
        result.put("completionTokens", completionTokens);
        result.put("totalTokens", totalTokens);
        result.put("durationMs", durationMs);
        result.put("averageDurationMs", calls == 0 ? 0D : (double) durationMs / calls);
        result.put("byModel", byModel);
        return result;
    }
}
