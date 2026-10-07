package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;


public final class ForeshadowSpanPolicy {


    public static final int SHORT_SPAN_CHAPTERS = 3;

    /** 触发反馈所需的最小样本量：回收条数太少时比例没有统计意义，不报 */
    public static final int MIN_SAMPLES_FOR_FEEDBACK = 5;

    /** 触发反馈的短命占比线：低于此值说明伏笔结构已经健康，不再唠叨 */
    public static final double SHORT_SPAN_RATE_LINE = 0.5;

    private static final int MATCH_MIN_CHARS = 6;

    private ForeshadowSpanPolicy() {
    }

    public record Span(int plantChapterNo, int resolveChapterNo, String content) {
        public int chapters() {
            return resolveChapterNo - plantChapterNo;
        }

        public boolean shortLived() {
            return chapters() < SHORT_SPAN_CHAPTERS;
        }
    }

    public static List<Span> spans(List<ChapterSummaryEntity> summaries) {
        return spans(summaries, true, List.of());
    }

    public static List<Span> spans(List<ChapterSummaryEntity> summaries, Collection<String> voided) {
        return spans(summaries, true, voided);
    }

    public static List<Span> feedbackSpans(List<ChapterSummaryEntity> summaries) {
        return feedbackSpans(summaries, List.of());
    }

    /** 同上（带弃置排除）：严格样本不足时退回宽松口径，仍排除已弃置条目 */
    public static List<Span> feedbackSpans(List<ChapterSummaryEntity> summaries, Collection<String> voided) {
        List<Span> strict = spans(summaries, true, voided);
        return strict.size() >= MIN_SAMPLES_FOR_FEEDBACK ? strict : spans(summaries, false, voided);
    }

    private static List<Span> spans(List<ChapterSummaryEntity> summaries, boolean strict,
                                    Collection<String> voided) {
        List<Span> result = new ArrayList<>();
        if (summaries == null || summaries.isEmpty()) {
            return result;
        }
        // 汇总埋设条目（回收时只在更早的章里找配对）
        Set<String> voidedSet = normalizedVoided(voided);
        List<Seed> seeds = new ArrayList<>();
        for (ChapterSummaryEntity summary : summaries) {
            if (summary == null || summary.getChapterNo() == null) {
                continue;
            }
            seeds.addAll(seedsOf(summary, strict, voidedSet));
        }
        for (ChapterSummaryEntity summary : summaries) {
            if (summary == null || summary.getChapterNo() == null) {
                continue;
            }
            int resolveNo = summary.getChapterNo();
            List<String> resolved = summary.getForeshadowingResolved();
            if (resolved == null || resolved.isEmpty()) {
                continue;
            }
            for (String raw : resolved) {
                if (StringUtils.isBlank(raw)) {
                    continue;
                }
                int bestNo = -1;
                int bestScore = 0;
                String target = normalize(raw);
                for (Seed seed : seeds) {
                    if (seed.chapterNo() >= resolveNo) {
                        continue;
                    }
                    String candidate = normalize(seed.content());
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    int score = candidate.equals(target) ? Integer.MAX_VALUE
                            : longestCommonSubstring(candidate, target);
                    if (score > bestScore) {
                        bestScore = score;
                        bestNo = seed.chapterNo();
                    }
                }
                if (bestNo > 0 && bestScore >= MATCH_MIN_CHARS) {
                    result.add(new Span(bestNo, resolveNo, raw.trim()));
                }
            }
        }
        return result;
    }

    /** 平均跨度（章）；无样本返回 0（调用方据此跳过指标，而不是报 0） */
    public static double averageSpan(List<ChapterSummaryEntity> summaries) {
        List<Span> all = spans(summaries);
        if (all.isEmpty()) {
            return 0.0;
        }        return all.stream().mapToInt(Span::chapters).average().orElse(0.0);
    }

    /** 短命伏笔占比（0~1）；无样本返回 0 */
    public static double shortSpanRate(List<ChapterSummaryEntity> summaries) {
        List<Span> all = spans(summaries);
        if (all.isEmpty()) {
            return 0.0;
        }
        long shortCount = all.stream().filter(Span::shortLived).count();
        return (double) shortCount / all.size();
    }

    /**
     * 该不该给规划层发反馈：样本足够且短命占比超线。
     * 返回 false 时不注入 prompt——**欠采样与健康态都不该唠叨**。
     *
     * <p>样本取自 {@link #feedbackSpans}（严格口径不足时退回宽松口径）——
     * 用严格口径会让"老数据全未标注"的故事**永久失明**，治本通道静默失效。
     */
    public static boolean shouldAdvise(List<ChapterSummaryEntity> summaries) {
        List<Span> all = feedbackSpans(summaries);
        if (all.size() < MIN_SAMPLES_FOR_FEEDBACK) {
            return false;
        }
        long shortCount = all.stream().filter(Span::shortLived).count();
        return (double) shortCount / all.size() > SHORT_SPAN_RATE_LINE;
    }

    /**
     * 最典型的短命样本（按跨度升序取前 N 条），供反馈文本举例。
     * 与 {@link #shouldAdvise} 同口径（{@link #feedbackSpans}），否则会出现
     * "触发了反馈却举不出例子"或"举例数与触发数对不上"的矛盾。
     */
    public static List<Span> shortest(List<ChapterSummaryEntity> summaries, int limit) {
        return feedbackSpans(summaries).stream()
                .filter(Span::shortLived)
                .sorted((a, b) -> Integer.compare(a.chapters(), b.chapters()))
                .limit(Math.max(0, limit))
                .toList();
    }

    // ==================== 在途（互补指标） ====================

    /**
     * 一条**尚未兑现**的伏笔：埋设章 + 至今滞留的章数。
     *
     * @param plantChapterNo 埋设章
     * @param ageChapters    滞留章数（当前末章 − 埋设章）
     */
    public record Pending(int plantChapterNo, int ageChapters, String content,
                          Integer scheduledPayoffChapter) {

        /** 兼容构造（无排期信息） */
        public Pending(int plantChapterNo, int ageChapters, String content) {
            this(plantChapterNo, ageChapters, content, null);
        }
    }

    public static List<Pending> pending(List<ChapterSummaryEntity> summaries, int currentChapter) {
        return pending(summaries, currentChapter, List.of());
    }

    /** 同上（带弃置排除）：已弃置的条目不算在途——否则死条目会永久推高滞留中位数 */
    public static List<Pending> pending(List<ChapterSummaryEntity> summaries, int currentChapter,
                                       Collection<String> voided) {
        List<Pending> result = new ArrayList<>();
        if (summaries == null || summaries.isEmpty() || currentChapter <= 0) {
            return result;
        }
        Set<String> voidedSet = normalizedVoided(voided);
        List<Seed> seeds = new ArrayList<>();
        for (ChapterSummaryEntity summary : safeList(summaries)) {
            if (summary != null && summary.getChapterNo() != null) {
                // 在途统计与寿命指标同口径（严格）：只算声明了兑现义务的条目
                seeds.addAll(seedsOf(summary, true, voidedSet));
            }
        }
        // 已被回收的埋设条目（按"回收文本能匹配上哪条埋设"标记）
        List<Span> resolved = spans(summaries);
        for (Seed seed : seeds) {
            boolean closed = resolved.stream().anyMatch(s -> s.plantChapterNo() == seed.chapterNo()
                    && normalize(s.content()).equals(normalize(seed.content())));
            if (!closed && seed.chapterNo() < currentChapter) {
                result.add(new Pending(seed.chapterNo(), currentChapter - seed.chapterNo(), seed.content(),
                        seed.scheduledPayoffChapter()));
            }
        }
        return result;
    }

    /** 在途伏笔滞留章数的中位数；无样本返回 0 */
    public static double pendingAgeMedian(List<ChapterSummaryEntity> summaries, int currentChapter) {
        return pendingAgeMedian(summaries, currentChapter, List.of());
    }

    /** 同上（带弃置排除） */
    public static double pendingAgeMedian(List<ChapterSummaryEntity> summaries, int currentChapter,
                                          Collection<String> voided) {
        List<Pending> all = pending(summaries, currentChapter, voided);
        if (all.isEmpty()) {
            return 0.0;
        }
        List<Integer> ages = all.stream().map(Pending::ageChapters).sorted().toList();
        int size = ages.size();
        return size % 2 == 1
                ? ages.get(size / 2)
                : (ages.get(size / 2 - 1) + ages.get(size / 2)) / 2.0;
    }

    // ==================== 漏收检测（排期落空） ====================

    public static long missedCount(List<ChapterSummaryEntity> summaries, int currentChapter) {
        return missedCount(summaries, currentChapter, List.of());
    }

    /** 同上（带弃置排除） */
    public static long missedCount(List<ChapterSummaryEntity> summaries, int currentChapter,
                                   Collection<String> voided) {
        if (summaries == null || currentChapter <= 0) {
            return 0L;
        }
        List<Pending> inFlight = pending(summaries, currentChapter, voided);
        return inFlight.stream().filter(p -> p.scheduledPayoffChapter() != null
                && currentChapter > p.scheduledPayoffChapter()).count();
    }

    /** 已弃置内容的归一化集合（供比对照用） */
    private static Set<String> normalizedVoided(Collection<String> voided) {
        if (voided == null || voided.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String v : voided) {
            if (StringUtils.isNotBlank(v)) {
                set.add(normalize(v));
            }
        }
        return set;
    }

    // ==================== 内部 ====================

    private record Seed(int chapterNo, String content, Integer scheduledPayoffChapter) {
    }

    private static List<Seed> seedsOf(ChapterSummaryEntity summary, boolean strict, Set<String> voided) {
        List<Seed> list = new ArrayList<>();
        if (summary.getForeshadowSeeds() == null) {
            return list;
        }
        for (ChapterSummaryEntity.SeedEntry entry : summary.getForeshadowSeeds()) {
            if (entry == null || StringUtils.isBlank(entry.getContent())) {
                continue;
            }
            if (strict && !entry.countedForSpan()) {
                continue;
            }
            // 已弃置的条目一律不计（与账本同口径——账本读 foreshadowingNew，已被 strip 掉）
            if (voided.contains(normalize(entry.getContent()))) {
                continue;
            }
            list.add(new Seed(summary.getChapterNo(), entry.getContent().trim(),
                    entry.getScheduledPayoffChapter()));
        }
        return list;
    }

    public static double unlabeledRate(List<ChapterSummaryEntity> summaries) {
        int total = 0;
        int unlabeled = 0;
        for (ChapterSummaryEntity summary : safeList(summaries)) {
            if (summary == null || summary.getForeshadowSeeds() == null) {
                continue;
            }
            for (ChapterSummaryEntity.SeedEntry entry : summary.getForeshadowSeeds()) {
                if (entry == null || StringUtils.isBlank(entry.getContent())) {
                    continue;
                }
                total++;
                if (entry.getResolvable() == null) {
                    unlabeled++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) unlabeled / total;
    }

    private static List<ChapterSummaryEntity> safeList(List<ChapterSummaryEntity> summaries) {
        return summaries == null ? List.of() : summaries;
    }


    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s\u3000“”\"「」『』]", "");
    }


    public static int longestCommonSubstring(String a, String b) {
        return PlanAdherencePolicy.longestCommonSubstring(a, b);
    }
}
