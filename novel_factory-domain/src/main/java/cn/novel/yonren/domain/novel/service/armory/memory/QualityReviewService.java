package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.QualityReviewProperties;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.alibaba.fastjson2.JSON;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;


@Slf4j
@Service
@RequiredArgsConstructor
public class QualityReviewService {

    /** 逐章评分的容错解析器（与阶段出口核验同一套：先直解，失败再走 JsonRepair 逐级修复） */
    private static final BeanOutputConverter<ReviewOutput> CONVERTER =
            new BeanOutputConverter<>(ReviewOutput.class);

    /** 维度键：顺序即落盘顺序，前端/分析脚本据此画趋势线 */
    public static final List<String> DIMENSIONS = List.of(
            "personaConsistency", "prose", "dialogue", "expectation", "payoff");

    private final LlmGateway llmGateway;
    private final IStoryRepository storyRepository;
    private final QualityReviewProperties properties;


    public void reviewWindow(Path storyDir, StoryVO storyVO, List<ChapterSummaryEntity> summaries, int windowEnd) {
        if (!properties.isEnabled() || storyDir == null || storyVO == null || storyVO.getModule() == null) {
            return;
        }
        if (windowEnd <= 0 || properties.getIntervalChapters() <= 0) {
            return;
        }
        // 触发规则收敛在服务内部（调用方每章无脑调一次）：末章号不是窗口整数倍就静默返回。
        // 放在这里而不是 worker，是为了让"多久评一次"只有一处定义、可被单测直接覆盖。
        if (windowEnd % properties.getIntervalChapters() != 0) {
            return;
        }
        try {
            int windowStart = Math.max(1, windowEnd - properties.getIntervalChapters() + 1);

            List<Integer> existing = storyRepository.readChapterNumbers(storyDir).stream()
                    .filter(no -> no != null && no >= windowStart && no <= windowEnd)
                    .sorted()
                    .toList();
            if (existing.isEmpty()) {
                log.info("质量评分窗口 {}-{} 内无正文，跳过", windowStart, windowEnd);
                return;
            }
            List<Integer> sampled = sampleEvenly(existing, properties.getSampleSize());

            List<ChapterReview> reviews = new ArrayList<>();
            for (Integer chapterNo : sampled) {
                ChapterReview review = scoreOne(storyVO, storyDir, chapterNo);
                if (review != null) {
                    reviews.add(review);
                }
            }
            if (reviews.isEmpty()) {
                log.warn("质量评分窗口 {}-{} 全部抽样章节评分失败，本轮不落趋势行", windowStart, windowEnd);
                return;
            }

            Map<String, Object> line = buildTrendLine(storyVO, summaries, windowStart, windowEnd, sampled, reviews);
            storyRepository.appendQualityTrend(storyDir, JSON.toJSONString(line));
            log.info("质量评分完成：第 {}-{} 章，抽样 {} 章，综合 {}/10（rubric {}）",
                    windowStart, windowEnd, reviews.size(), line.get("overall"), properties.getRubricVersion());
        } catch (Exception e) {
            // 度量失败不得反噬生成：吞掉异常只告警
            log.warn("全书质量评分异常（fail-soft，不影响生成），窗口末章 {}", windowEnd, e);
        }
    }

    /** 单章评分：读正文 → 调用强模型 → 容错解析；任一步失败返回 null（由调用方跳过该样本） */
    private ChapterReview scoreOne(StoryVO storyVO, Path storyDir, int chapterNo) {
        try {
            ChapterContentEntity content = storyRepository.readChapter(storyDir, chapterNo);
            if (content == null || StringUtils.isBlank(content.getContent())) {
                log.info("质量评分跳过第 {} 章：正文为空", chapterNo);
                return null;
            }
            String text = truncate(content.getContent(), properties.getMaxChapterChars());
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .systemPrompt(systemPrompt())
                    .userPrompt(buildUserPrompt(chapterNo, content.getTitle(), text))
                    .label("quality-review-第" + chapterNo + "章")
                    .scene(ModelScene.QUALITY_REVIEW)
                    .build());
            ChapterReview review = parse(raw, chapterNo);
            if (review == null) {
                log.warn("质量评分第 {} 章输出无法解析，丢弃该样本", chapterNo);
            }
            return review;
        } catch (Exception e) {
            log.warn("质量评分第 {} 章调用失败，丢弃该样本（fail-soft）", chapterNo, e);
            return null;
        }
    }

    /**
     * 在窗口内**均匀**抽样：首尾必取、按等距索引取值并去重。
     * 刻意不做"只取最近 N 章"——那会让窗口前段的退化完全不可见，而"前段变差"正是长篇最典型的衰退形态。
     */
    static List<Integer> sampleEvenly(List<Integer> chapterNos, int sampleSize) {
        if (chapterNos == null || chapterNos.isEmpty()) {
            return List.of();
        }
        int k = Math.min(Math.max(sampleSize, 1), chapterNos.size());
        if (k >= chapterNos.size()) {
            return List.copyOf(chapterNos);
        }
        if (k == 1) {
            return List.of(chapterNos.get(0));
        }
        LinkedHashSet<Integer> picked = new LinkedHashSet<>();
        for (int i = 0; i < k; i++) {
            int index = Math.round(i * (chapterNos.size() - 1) / (float) (k - 1));
            picked.add(chapterNos.get(index));
        }
        return List.copyOf(picked);
    }

    /** 趋势行：rubric 版本 + 抽样各章维度分 + 窗口均值 + 窗口机械指标 */
    private Map<String, Object> buildTrendLine(StoryVO storyVO, List<ChapterSummaryEntity> summaries,
                                               int windowStart, int windowEnd,
                                               List<Integer> sampled, List<ChapterReview> reviews) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("rubricVersion", properties.getRubricVersion());
        line.put("windowStart", windowStart);
        line.put("windowEnd", windowEnd);
        line.put("sampledChapters", sampled);
        line.put("scene", ModelScene.QUALITY_REVIEW.getConfigKey());
        // 场景"配置"的模型名（运行时覆盖/降级后的真实模型以 llm-usage.jsonl 里 label=quality-review-* 为准）
        line.put("configuredModel", configuredModel(storyVO));
        line.put("samples", reviews);
        line.put("avg", averageScores(reviews));
        line.put("overall", overallScore(reviews));
        line.put("mechanical", mechanicalMetrics(summaries, windowStart, windowEnd));
        return line;
    }

    private String configuredModel(StoryVO storyVO) {
        try {
            StoryVO.Module.ChatModel scene = storyVO.getModule().getSceneModels() == null
                    ? null : storyVO.getModule().getSceneModels().get(ModelScene.QUALITY_REVIEW.getConfigKey());
            return scene != null ? scene.getModel() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Double> averageScores(List<ChapterReview> reviews) {
        Map<String, Double> avg = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            OptionalDouble mean = reviews.stream()
                    .map(r -> r.score(dim))
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Integer::doubleValue)
                    .average();
            avg.put(dim, mean.isPresent() ? round1(mean.getAsDouble()) : null);
        }
        return avg;
    }

    /** 综合分 = 各维度均值的平均（维度等权，避免人为加权后再也说不清"综合分变了是谁变了"） */
    private Double overallScore(List<ChapterReview> reviews) {
        OptionalDouble mean = averageScores(reviews).values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
        return mean.isPresent() ? round1(mean.getAsDouble()) : null;
    }

    /** 窗口机械指标：来自 summaries（零额外成本），与 LLM 主观分互为交叉验证 */
    private Map<String, Object> mechanicalMetrics(List<ChapterSummaryEntity> summaries,
                                                  int windowStart, int windowEnd) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        List<ChapterSummaryEntity> inWindow = summaries == null ? List.of() : summaries.stream()
                .filter(s -> s != null && s.getChapterNo() != null
                        && s.getChapterNo() >= windowStart && s.getChapterNo() <= windowEnd)
                .toList();
        metrics.put("chapterCount", inWindow.size());
        metrics.put("avgValidChars", meanRounded(inWindow.stream()
                .map(ChapterSummaryEntity::getValidChars).filter(java.util.Objects::nonNull)
                .mapToDouble(Integer::doubleValue).average()));
        metrics.put("minValidChars", inWindow.stream()
                .map(ChapterSummaryEntity::getValidChars).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).min().orElse(0));
        metrics.put("chaptersBelow1500", inWindow.stream()
                .map(ChapterSummaryEntity::getValidChars).filter(java.util.Objects::nonNull)
                .filter(v -> v < 1500).count());
        metrics.put("avgDialogueRatio", meanRounded(inWindow.stream()
                .map(ChapterSummaryEntity::getDialogueRatio).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).average()));
        metrics.put("minDialogueRatio", round1(inWindow.stream()
                .map(ChapterSummaryEntity::getDialogueRatio).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).min().orElse(Double.NaN)));
        return metrics;
    }

    private static Double round1(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return Math.round(value * 10.0) / 10.0;
    }

    /** 均值 → 保留一位小数的 Double；空集合返回 null（写成 {@code round1(mean(...))} 会在空集上拆箱 NPE） */
    private static Double meanRounded(OptionalDouble value) {
        return value.isPresent() ? round1(value.getAsDouble()) : null;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n…（正文超出评分长度上限，已截断）";
    }

    private ChapterReview parse(String raw, int chapterNo) {
        ReviewOutput output;
        try {
            output = JsonParseFallback.parse(raw, CONVERTER::convert);
        } catch (Exception e) {
            return null;
        }
        if (output == null || output.getReviews() == null || output.getReviews().isEmpty()) {
            return null;
        }
        for (ChapterReview review : output.getReviews()) {
            // 模型偶尔漏填章号：单章调用时可直接回填，避免整条样本作废
            if (review.getChapterNo() == null) {
                review.setChapterNo(chapterNo);
            }
        }
        return output.getReviews().get(0);
    }

    private String systemPrompt() {
        return """
                你是资深长篇小说编辑，负责对已完成章节做**标准化的质量打分**，用于追踪全书质量趋势。
                要求：
                1. 只输出 JSON，不要任何解释性文字、不要 Markdown 代码块；
                2. 只依据正文本身判断，不得因为"这是草稿/后续会修"而放水；
                3. 打分尺度固定（这是一把要跨时间使用的尺子，松紧必须稳定）：
                   1-3 = 明显不合格；4-5 = 勉强合格但有硬伤；6 = 合格；7 = 良好；
                   8 = 优秀（可发表水平）；9-10 = 罕见（留白，非必要不给）。
                4. problems 写真问题并指明位置线索，不要写"可以更精彩"这类无法执行的空话；
                   没有问题时给空数组，不要为凑数编问题。
                """;
    }

    private String buildUserPrompt(int chapterNo, String title, String text) {
        // 注意：本段用 .formatted 注入章号，JSON 里的引号一律写成 \" ——
        // 直接写成 ASCII 引号会在 `:"` 处凑出三个引号，把 Java 文本块提前终止（编译期语法错误）
        return ("""
                【评分量表版本】%s

                【评分维度】各 1-10 分：
                - personaConsistency 人设一致：人物言行是否符合已建立的身份、年龄、认知边界与利益立场；
                  有无为推进剧情而让人物做出不符合其性格或处境的举动；
                - prose 文风：叙述是否具体可感、有无套话与模板腔、有无时代错位或术语越界、
                  有无报告腔/总结腔；句式是否有变化；
                - dialogue 对话质量：对白是否承担推进情节/揭示人物/制造冲突的功能；
                  有无无意义寒暄凑数；不同人物说话是否有可辨识的差异；
                - expectation 期待感：章末是否把读者拽向下一章；张力曲线是否成立；有无提前泄底或空转；
                - payoff 爽点：本章承诺是否兑现；兑现是否来自主角自己的行动与代价
                  （而非他人施舍或巧合）；是否言之有物而非空喊。

                【待评章节】
                第%d章 %s

                %s

                【输出格式】严格按以下 JSON 输出（scores 为 1-10 整数）：
                {\"reviews\":[{\"chapterNo\":%d,\"personaConsistency\":0,\"prose\":0,\"dialogue\":0,\"expectation\":0,\"payoff\":0,\"problems\":[\"...\"],\"highlight\":\"本章最好的一处\"}]}
                """).formatted(properties.getRubricVersion(), chapterNo,
                StringUtils.defaultString(title), text, chapterNo);
    }

    /** 单章评分结果（字段名与 LLM 输出 JSON 一一对应） */
    @Data
    public static class ChapterReview {
        private Integer chapterNo;
        private Integer personaConsistency;
        private Integer prose;
        private Integer dialogue;
        private Integer expectation;
        private Integer payoff;
        private List<String> problems;
        private String highlight;

        /** 按维度键取分；未知键返回 null（新增维度时不会静默当成 0 拉低均值） */
        public Integer score(String dimension) {
            return switch (dimension) {
                case "personaConsistency" -> personaConsistency;
                case "prose" -> prose;
                case "dialogue" -> dialogue;
                case "expectation" -> expectation;
                case "payoff" -> payoff;
                default -> null;
            };
        }
    }

    /** 模型输出的外层包装 */
    @Data
    public static class ReviewOutput {
        private List<ChapterReview> reviews;
    }
}
