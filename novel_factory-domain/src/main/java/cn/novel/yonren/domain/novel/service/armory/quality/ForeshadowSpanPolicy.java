package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * **伏笔寿命（埋设 → 回收的章距）度量**（2026-10-01 新增）。
 *
 * <p><b>为什么要它</b>：实测第 1–15 章共回收 13 条伏笔，跨度分布是
 * <b>1 章 ×8、2 章 ×2、3 章 ×1、6 章 ×1、8 章 ×1，平均 2.23 章，77% 在 2 章内兑现</b>。
 * 42 条埋设里绝大多数是"后天登门""下周前需答复"这类**约定式伏笔**——埋下去就立刻收，
 * 读者来不及惦记。小说读起来浅，这是直接原因之一。
 *
 * <p><b>它解决的是哪个问题</b>：{@code SecrecyGuard} 的误拦（禁泄表持续把"上一章刚埋、
 * 这一章就要用"的词判成泄露）只是**症状**；根因是伏笔寿命过短。那个组件负责"别误拦正常兑现"，
 * 本类负责把"伏笔太短命"这件事**变成规划层看得见的信号**——只有回灌给规划者才有牙。
 *
 * <p><b>与体裁无关</b>：判据只有章距与相似度，不含任何本书特有名词。
 *
 * <p>⚠️ 阈值须经真实批次校准（项目约定：不要凭直觉取整）。当前取值来自第 1–15 章的实测分布。
 */
public final class ForeshadowSpanPolicy {

    /**
     * 短命伏笔的判定线：跨度**小于**该值即算短命（2026-10-01 取值 3）。
     *
     * <p>取 3 的直接依据：实测跨度分布中 1~2 章占 77%，而这些几乎都是"约定式"伏笔；
     * 跨度 ≥3 才可能形成"读者惦记"的悬念。
     *
     * <p>这是**观测与反馈**的判定线，不是硬门禁——短命伏笔不会被打回，只会让规划层收到
     * "请把伏笔拉长"的反馈。因此宁可取偏松的值，避免把"短平快的支线小包袱"也一起指责。
     */
    public static final int SHORT_SPAN_CHAPTERS = 3;

    /** 触发反馈所需的最小样本量：回收条数太少时比例没有统计意义，不报 */
    public static final int MIN_SAMPLES_FOR_FEEDBACK = 5;

    /** 触发反馈的短命占比线：低于此值说明伏笔结构已经健康，不再唠叨 */
    public static final double SHORT_SPAN_RATE_LINE = 0.5;

    /**
     * 相似度匹配的公共子串下限：低于此长度不认为是同一条伏笔。
     * 取 6 与 {@code ChapterMemoryService} 的伏笔揭示匹配阈值（{@code REVEAL_MATCH_MIN_CHARS}）
     * 保持一致——同一类"这条回收指的是哪条埋设"的判断，全仓只用一把尺子。
     */
    private static final int MATCH_MIN_CHARS = 6;

    private ForeshadowSpanPolicy() {
    }

    /**
     * 一条伏笔的寿命观测点。
     *
     * @param plantChapterNo   埋设章
     * @param resolveChapterNo 回收章
     * @param content          伏笔描述（回收侧原文）
     */
    public record Span(int plantChapterNo, int resolveChapterNo, String content) {
        public int chapters() {
            return resolveChapterNo - plantChapterNo;
        }

        public boolean shortLived() {
            return chapters() < SHORT_SPAN_CHAPTERS;
        }
    }

    /**
     * 从逐章摘要中还原伏笔寿命：把每章的"回收"条目与更早章节的"埋设"条目配对。
     *
     * <p>配对策略与项目其它处一致——先精确匹配（摘要常逐字复制），再退到最长公共子串
     * （模型改写措辞时），阈值 {@value #MATCH_MIN_CHARS} 字。配不上的条目**丢弃不计**，
     * 不猜（宁可漏报也不制造假数据）。
     *
     * <p><b>口径：严格</b>——只统计声明了兑现义务（{@code resolvable=TRUE}）的埋设。
     * 这是**指标**口径，必须干净。给规划层的**反馈**用 {@link #feedbackSpans}（见其注释）。
     */
    public static List<Span> spans(List<ChapterSummaryEntity> summaries) {
        return spans(summaries, true, List.of());
    }

    /**
     * 同上，但先排除**已被卷末清账弃置（VOID）**的条目。
     *
     * <p><b>为什么必须排除</b>（2026-10-02 代码核对发现的口径分裂）：`stripVoidedForeshadows`
     * 只清理 `foreshadowingNew`（字符串清单），而本类读的是 `foreshadowSeeds`——**后者从未被清理**。
     * 若不排除，一旦弃置掉一条带 {@code resolvable=TRUE} 的线，它会**永远**被计成在途，
     * 滞留中位数被死条目单调污染。{@code voided} 由
     * {@code ForeshadowSettlementEntity.voidedContents(settlements)} 提供。
     */
    public static List<Span> spans(List<ChapterSummaryEntity> summaries, Collection<String> voided) {
        return spans(summaries, true, voided);
    }

    /**
     * 反馈口径的寿命样本：**严格口径样本不足时退回宽松口径**（含未标注条目）。
     *
     * <p><b>为什么反馈不能跟指标用同一把尺</b>（2026-10-02 实测教训）：本类只统计
     * {@code resolvable=TRUE}，而老故事的历史埋设**全部未标注**（{@code null}）。
     * 于是规划下一批时严格样本 = <b>0 条</b> ⇒ {@link #shouldAdvise} 因欠采样返回 false
     * ⇒ **伏笔长度反馈整段不注入**，治本通道静默失效——指标口径的收紧顺手把反馈也关掉了。
     *
     * <p>两者的代价不对等：**指标宁可失明也不许脏**（脏了会误导判断），
     * **反馈宁可多唠叨也不许瞎**（少提示只是少一次改善机会）。故反馈在欠采样时退回宽松口径，
     * 一旦严格样本够用就自动切回。
     */
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

    /**
     * 仍在途（埋了未收）的伏笔 —— **`spans()` 的互补指标**（2026-10-02 新增）。
     *
     * <p><b>为什么必须有它</b>：{@link #spans} 只统计**已回收**的伏笔。
     * 于是当规划层按指令"养长线"（故意不立刻兑现）时，那些伏笔**不进分母**，
     * 指标反而变差——实测从 2.23 章"恶化"到 1.90 章。**只统计闭环事件的指标会惩罚正确的修复。**
     * 本方法看的是另一半：**长线有没有真的在养**。
     *
     * <p><b>但要两个一起看</b>：滞留久既可能是"有意养的长线"，也可能是"被遗忘的线"。
     * 实测 31 条在途里混着两类——ch15「蓝皮书夹层那张纸，来源未明」（有意留白）
     * 与 ch3「鸿运科技」（12 章没人再提）。单看滞留时长无法区分，须配合卷末清账的
     * 弃置/限期回收裁决一起判断。
     *
     * @param currentChapter 当前写到的章号（滞留基准）
     */
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

    /**
     * **排期落空**：已过 {@code scheduledPayoffChapter} 但仍未回收的条数（2026-10-02，P2a）。
     *
     * <p>这是"**有意养的长线**"与"**被写忘了的线**"的分界线——现有任何指标都区分不了这两者。
     * 没有排期数据（P2b 之前）时恒为 0，属预期惰性。
     */
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
            // 严格口径只统计**声明了兑现义务**的条目（2026-10-02）：
            // 人物状态/氛围点缀/事实陈述不承担兑现义务，计入会把"在途"与寿命分母一起注水，
            // 实测导致指标从 2.23 章"恶化"到 1.90 章——**指标惩罚了正确的修复**。
            // resolvable == null（老数据/模型漏标）同样不计入，改用 unlabeledRate 单独观测。
            // 宽松口径（strict=false）则把未标注的也算进来——供 feedbackSpans 在欠采样时兜底。
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

    /**
     * 未标注占比（0~1）：`resolvable == null` 的条目占全部埋设条目的比例。
     *
     * <p>为什么必须观测它：本策略只统计 `resolvable=TRUE` 的条目（见 {@link #seedsOf}）。
     * 若模型大面积漏标，寿命指标会因**样本被掏空**而失真——看似"伏笔变长了"，
     * 实际只是没人被计入。这个占比就是那道防伪线：
     * **占比明显偏高时，先修标注，不要相信寿命指标。**
     */
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

    /** 归一化：去空白与引号（与项目其它文本比对口径一致） */
    /**
     * 文本归一化（去空白与引号）：**仓库内文本比对统一口径**。
     * 2026-10-02 起对 {@code ForeshadowScheduleService}（排期打标）开放——
     * 两处必须用同一套口径，否则同一对文本在指标里算匹配、在打标里算不匹配。
     */
    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s\u3000“”\"「」『』]", "");
    }

    /** 最长公共子串长度（沿用 {@code PlanAdherencePolicy} 的既有实现，避免第三份口径） */
    /**
     * 最长公共子串长度：**仓库内模糊匹配统一口径**（伏笔埋设↔回收、排期 intent↔seed 都用它）。
     * 公开是为了避免出现第二份实现——两份 LCS 的阈值稍有差异，就会让"打标命中"与
     * "跨度计入"对同一条线给出相反结论。
     */
    public static int longestCommonSubstring(String a, String b) {
        return PlanAdherencePolicy.longestCommonSubstring(a, b);
    }
}
