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
