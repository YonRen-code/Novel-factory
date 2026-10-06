package cn.novel.yonren.domain.novel.service.armory.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则文件读取工具：classpath 加载 assets 下的 md 文件，静态缓存
 */
@Component
public class PromptRuleFileLoader {

    private static final String ASSETS_BASE = "assets/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 读取 assets 下的规则文件；文件缺失时返回 null（规则跳过，不阻塞主流程）
     */
    public String load(String relativePath) {
        return cache.computeIfAbsent(relativePath, path -> {
            try {
                ClassPathResource resource = new ClassPathResource(ASSETS_BASE + path);
                if (!resource.exists()) {
                    return null;
                }
                try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                    return FileCopyUtils.copyToString(reader);
                }
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * 批量读取；任意文件缺失则整体返回 null（调用方跳过该规则）
     */
    public String loadAll(String... relativePaths) {
        List<String> contents = new ArrayList<>();
        for (String path : relativePaths) {
            String content = load(path);
            if (content == null) {
                return null;
            }
            contents.add(content);
        }
        return String.join("\n\n---\n\n", contents);
    }

    /**
     * 按<b>二级小节标题前缀</b>抽取内容：只返回命中的 {@code ## } 小节（含各自标题行与正文），
     * 不含一级标题与文件引言——引言常交叉引用别的小节，按节抽取时带上会造成悬空引用。
     *
     * <p>用途（2026-09-16）：同一份规则文件供不同场景取用<b>不同子集</b>。
     * 例如 {@code rules/anti-ai-tone.md} 全文 7637 字符，而章节计划场景只需要其中的结构条款——
     * 与其为每个场景维护一份会互相漂移的"简版"副本，不如从同一份文件里按节取。
     *
     * @param headingPrefixes 小节标题前缀（如 {@code "## 一、"}），命中即取该节
     * @return 命中内容（以空行分隔的各节）；文件缺失或一节未命中返回 null（调用方跳过该规则）
     */
    public String loadSections(String relativePath, String... headingPrefixes) {
        String content = load(relativePath);
        if (content == null || headingPrefixes == null || headingPrefixes.length == 0) {
            return null;
        }
        // 按二级标题切成「标题行 + 正文」块；split 的下标 0 是文件头（一级标题与引言），丢弃
        String[] blocks = content.split("(?m)^(?=## )");
        List<String> picked = new ArrayList<>();
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            for (String prefix : headingPrefixes) {
                if (block.startsWith(prefix)) {
                    picked.add(block.stripTrailing());
                    break;
                }
            }
        }
        return picked.isEmpty() ? null : String.join("\n\n", picked);
    }

}
