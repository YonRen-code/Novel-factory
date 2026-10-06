package cn.novel.yonren.domain.novel.service.armory.quality;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 疲劳词表目录：assets/rules/fatigue-patterns.txt 的加载器。
 * 单章门禁（StyleViolationPolicy）与跨章疲劳统计（StyleStatService）共用同一份词表，
 * 避免两处硬编码漂移；文件缺失/解析失败时回退内置词表并告警，不阻塞主流程。
 */
@Slf4j
public final class FatiguePatternCatalog {

    private static final String FILE = "assets/config/fatigue-patterns.txt";

    /** 词表类别：adverb=万能副词（密度判定）；eye=眼神套话（合计次数）；body=身体反应套话（单词复读）；extra=仅跨章统计 */
    public record Catalog(List<String> adverbs, List<String> eyeCliches, List<String> bodyCliches, List<String> extras) {
        /** 跨章疲劳统计用的全量词表 */
        public List<String> all() {
            List<String> all = new ArrayList<>(adverbs);
            all.addAll(eyeCliches);
            all.addAll(bodyCliches);
            all.addAll(extras);
            return all;
        }
    }

    /** 内置兜底词表：文件不可用时保证门禁不完全失效（与 txt 保持同步） */
    private static final Catalog FALLBACK = new Catalog(
            List.of("不禁", "不由得", "忍不住", "下意识", "缓缓", "微微", "轻轻", "顿时", "瞬间", "随即",
                    "紧接着", "与此同时", "仿佛", "宛如", "似乎", "隐隐", "悄然", "默默"),
            List.of("眼神复杂", "眼神深邃", "目光深沉", "眸光一暗", "眼底闪过", "眼中闪过", "眼神晦暗不明",
                    "目光一凛", "眸光微动", "眼神闪烁", "目光游离", "眼神飘忽", "目光灼灼", "目光如炬",
                    "眼神锐利", "目光如刀"),
            List.of("喉结滚动", "呼吸一滞", "心口一沉", "心口一揪", "指节攥白", "深吸一口气", "身体僵住",
                    "如遭雷击", "瞳孔骤缩", "眼皮一跳", "眼角抽搐", "嘴角抽了抽", "眉心拧成一团", "后背发凉",
                    "头皮发麻", "心脏漏跳一拍", "血液凝固", "身体一僵", "声音压低"),
            List.of("一丝", "一抹", "一缕", "竟然", "难以言喻", "心中一凛", "嘴角勾起", "空气仿佛凝固", "涌上心头"));

    private static volatile Catalog cached;

    public static Catalog get() {
        if (cached == null) {
            synchronized (FatiguePatternCatalog.class) {
                if (cached == null) {
                    cached = load();
                }
            }
        }
        return cached;
    }

    private static Catalog load() {
        try {
            ClassPathResource resource = new ClassPathResource(FILE);
            if (!resource.exists()) {
                log.warn("疲劳词表 {} 不存在，回退内置词表", FILE);
                return FALLBACK;
            }
            String text;
            try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                text = FileCopyUtils.copyToString(reader);
            }
            Catalog catalog = parse(text);
            if (catalog.all().isEmpty()) {
                log.warn("疲劳词表 {} 解析为空，回退内置词表", FILE);
                return FALLBACK;
            }
            return catalog;
        } catch (Exception e) {
            log.warn("疲劳词表 {} 加载失败，回退内置词表：{}", FILE, e.getMessage());
            return FALLBACK;
        }
    }

    /** 解析 txt：# 注释与空行忽略，[类别] 行切换当前桶，未知类别归入 extra */
    static Catalog parse(String text) {
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        buckets.put("adverb", new ArrayList<>());
        buckets.put("eye", new ArrayList<>());
        buckets.put("body", new ArrayList<>());
        buckets.put("extra", new ArrayList<>());
        String current = "extra";
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1).trim();
                current = buckets.containsKey(section) ? section : "extra";
                continue;
            }
            buckets.get(current).add(line);
        }
        return new Catalog(buckets.get("adverb"), buckets.get("eye"), buckets.get("body"), buckets.get("extra"));
    }

    private FatiguePatternCatalog() { }
}
