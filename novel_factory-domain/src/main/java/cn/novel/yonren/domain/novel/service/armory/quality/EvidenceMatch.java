package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

/**
 * 证据命中判定：反编造门的统一口径。
 *
 * <p>背景：同一套「引用必须是正文子串」的反编造门被复用在三处——摘要状态入账（≤50 字）、
 * 摘要一致性事实入账（≤50 字）、审校 BLOCKING 证据。三处历史上各写一套，宽松度互不相同：
 * 审校侧早已实现归一化 + 省略号分段，而摘要入账侧仍是裸 {@code content.indexOf()}，
 * 导致模型惯用的「...」压缩引用在入账侧被整批误判为编造——实测 607 条隔离条目中
 * 75.8% 的 evidence 含省略号，其中 72.1% 归一化后即可在当章正文定位。
 * 本类把判定集中到一处、三处共用，杜绝再次分叉。
 *
 * <p>判定按宽松度递进，取首个命中档：
 * <ol>
 *   <li>{@link Tier#EXACT} 逐字包含（原 indexOf 语义）</li>
 *   <li>{@link Tier#NORMALIZED} 归一化后包含（去空白/统一引号/剥首尾引号）</li>
 *   <li>{@link Tier#FRAGMENTED} 按省略号切段后各段均包含，且引用在正文中足够集中</li>
 *   <li>{@link Tier#SPREAD} 各段均包含，但引用横跨大半章（留痕档，仍入账）</li>
 *   <li>{@link Tier#PHRASE_COVERED} 按标点切短语后全部命中（模型改写了连接处措辞）</li>
 *   <li>{@link Tier#ANCHORED} 只剩单条短语，但该短语足够长且逐字命中</li>
 *   <li>未命中 → {@link Tier#PHRASE_PARTIAL} / {@link Tier#NO_MATCH}，隔离待裁决</li>
 * </ol>
 *
 * <p>放宽的是「表述差异」，不是「事实有无」：所有可入账档位都要求证据能在正文中被定位；
 * 完全找不到证据（含编造引用）仍保持逐出，反编造门不放弃。
 *
 * <p><b>SPREAD / ANCHORED 为何可入账</b>（2026-09-16 依离线重放修正）：
 * 集中度（{@link #SPAN_COMPACT_RATIO}）原本用于识别「模型把相距很远的描写拼成一条状态」，
 * 但它与「证据能否在正文定位」正交——一条复合状态（伤情 + 持有物 + 位置 + 认知）本就散落
 * 章内多处，跨度大是正常形态；且该档要求各段<em>全部</em>逐字命中，严格度高于早已入账的
 * {@link Tier#PHRASE_COVERED}（允许改写部分措辞），逐出它在档位序上自相矛盾。
 * 同理，单短语证据原先被 {@code len(phrases) >= 2} 的数量门槛直接漏到最弱的隔离档，
 * 而「证据条数少」不等于「证据弱」，故改用锚定字数（{@link #ANCHOR_MIN_LENGTH}）判可信。
 * 实测：SPREAD 52 条 + ANCHORED 14 条全部为真；而「全库无据」条目分段必有段命中不到，
 * 与这两档零交集——放行不影响反编造门的拦截力。
 */
public final class EvidenceMatch {

    /** 状态证据原文引用上限字数（与摘要 prompt 约束一致，超长截断以保住子串有效性） */
    public static final int STATE_EVIDENCE_MAX_LENGTH = 50;

    /**
     * 引用集中度上限：各片段在正文中的最小包围区间占正文的比例。
     * 仅用于区分 {@link Tier#FRAGMENTED}（同段引用）与 {@link Tier#SPREAD}（跨章散布）这两个
     * <b>都已入账</b>的档位，供观测层统计「模型引用纪律」的漂移，不再作为逐出依据。
     * 取值依据：实测分段引用的中位数仅 0.063，69.7% 落在 0.2 以内，0.5 只筛出真正跨章的引用。
     */
    private static final double SPAN_COMPACT_RATIO = 0.5;

    /**
     * 引用集中度的绝对跨度上限（字），与比例条件取或。
     * 比例条件在短章里会过严——正文越短，同样的绝对跨度占比越高（短章 700 字时
     * 0.5 相当于只允许 350 字跨度），故补一个绝对下限兜住短章，避免误伤正常引用。
     */
    private static final int SPAN_COMPACT_MAX_SPAN = 400;

    /** 短语最短字数：过短短语（「然后」「于是」）不参与覆盖判定，防误命中 */
    private static final int PHRASE_MIN_LENGTH = 6;

    /** 短语覆盖档所需最少短语数：多短语时全部命中即放行 */
    private static final int PHRASE_MIN_COUNT = 2;

    /**
     * 单短语证据的最小锚定字数。单条短语也会进入 {@link #matchPhrases}，此处用「锚定强度」
     * 而非「短语条数」判可信：一段 ≥8 字的逐字连续原文，锚定力强于三段各 6 字的短语；
     * 而 6-7 字的短锚（半截短语）与关键词碰巧命中的量级接近，不足以放行。
     * 取值依据：离线重放中该档 15 条里 14 条最长短语 ≥8 字、1 条仅 6-7 字。
     */
    private static final int ANCHOR_MIN_LENGTH = 8;

    /**
     * 审校 BLOCKING 证据校验的「长段」下限（2026-09-17 新增）。
     *
     * <p>切段后短于此的分句（多为主语、连接语、以及被截断的残段）**不参与判定**——
     * 这正是给"模型在句首补主语、或丢掉一个前置分句"留的余地。
     * 取 8 与 {@link #ANCHOR_MIN_LENGTH} 同值：低于 8 字构不成可信锚点。
     */
    private static final int SEGMENT_MIN_LENGTH = 8;

    /** 长段命中比例下限：半数及以上逐字可定位即视为证据成立（放宽的是表述，不是事实有无） */
    private static final double SEGMENT_HIT_RATIO = 0.5;

    /** 省略号：全角「…」连续，或三个以上英文句点（与审校侧原口径一致） */
    private static final Pattern ELLIPSIS = Pattern.compile("…+|\\.{3,}");

    /** 短语切分：中文标点或省略号 */
    private static final Pattern PHRASE_SEPARATOR = Pattern.compile("[，。；、！？：,;!?:]+|…+|\\.{3,}");

    private EvidenceMatch() {
    }

    /** 命中档位。前六档可入账（SPREAD / ANCHORED 为留痕档），后两档转隔离待裁决。 */
    public enum Tier {

        EXACT("exact"),
        NORMALIZED("normalized"),
        FRAGMENTED("fragmented"),
        PHRASE_COVERED("phrase-covered"),
        ANCHORED("anchored"),
        SPREAD("spread"),
        PHRASE_PARTIAL("phrase-partial"),
        NO_MATCH("no-match");

        private final String code;

        Tier(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        /**
         * 该档位是否可入账（放宽表述差异，但不放宽事实有无）。
         * SPREAD / ANCHORED 属「留痕档」：调用方应把档位写回条目的 {@code evidenceTier}，
         * 供观测层统计占比——一旦这两档对 continuityConflicts 的贡献异常，即可回收复核。
         */
        public boolean isAccepted() {
            return this == EXACT || this == NORMALIZED || this == FRAGMENTED
                    || this == PHRASE_COVERED || this == ANCHORED || this == SPREAD;
        }

        /**
         * 是否为「放宽留痕档」：已入账，但证据形态弱于标准档（跨章引用 / 单条短语锚定）。
         * 调用方应把此类档位写回条目的 {@code evidenceTier}，供观测层统计放宽档占账本的比例。
         */
        public boolean isLoose() {
            return this == SPREAD || this == ANCHORED;
        }
    }

    /**
     * 归一化：去空白/换行（实体中可能残留 JSON 转义形态）、统一引号、剥除首尾引号。
     *
     * <p><b>引号口径（2026-09-18 收紧为"只分有引号/无引号"）</b>：所有引号形态——
     * 全角双 {@code “”}、全角单 {@code ‘’}、半角单 {@code '}、半角双 {@code "}——**一律归一为半角双引号**。
     * 改造前只做「全角→对应半角」（{@code “”→"、‘’→'}），于是 {@code '无月'} 与 {@code "无月"}
     * 仍然互不匹配，而模型复述引文时最典型的退化恰恰就是**换一种引号**（真实数据里"线上角色'无月'"
     * 被引用成各种引号形态，导致证据整条判死）。
     *
     * <p>为什么可以放弃引号形态的区分：引号是**装饰性标点**，不承载语义；同一段原文无论用哪种引号包裹，
     * 指向的事实完全相同。而归一化对「正文」与「证据」**同时施加**，所以不会引入单向偏移。
     * 唯一代价是嵌套引号的层级信息丢失（{@code 他喊‘救命’} → {@code 他喊"救命"}），
     * 但两侧同规则，比对结果不受影响。半角 {@code '} 也一并归一——它在中文正文里作对白引号时
     * 与 {@code "} 等价，而英文缩略（{@code don't}）两侧同样被改，不产生误匹配。
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\n", "\n")
                .replaceAll("[\\s\\u3000]+", "")
                .replace('“', '"').replace('”', '"')
                .replace('‘', '"').replace('’', '"')
                .replace('\'', '"')
                .replaceAll("^[\"「『]+|[\"」』]+$", "");
    }

    /** 证据裁剪：trim 后截断到上限（与摘要 prompt 的字数约束对齐） */
    public static String truncate(String evidence, int maxLength) {
        if (evidence == null) {
            return "";
        }
        String trimmed = evidence.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /**
     * 宽松命中判定（审校 BLOCKING 证据校验口径）：归一化后整段包含，或省略号分段后各段全部包含。
     *
     * @param evidence          待校验证据
     * @param normalizedContent 已归一化的正文（调用方批量复用以省重复归一化）
     */
    public static boolean contained(String evidence, String normalizedContent) {
        String normalized = normalize(evidence);
        if (normalized.isEmpty() || normalizedContent == null) {
            return false;
        }
        if (normalizedContent.contains(normalized)) {
            return true;
        }
        String[] fragments = ELLIPSIS.split(normalized);
        if (fragments.length <= 1) {
            return false;
        }
        for (String fragment : fragments) {
            if (!fragment.isEmpty() && !normalizedContent.contains(fragment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 「分段多数命中」判定（审校 BLOCKING 证据校验口径，2026-09-17 新增）。
     *
     * <p><b>为什么新增</b>：审校模型引用原文的习惯会破坏"整串连续"，而 {@link #contained} 只认
     * 「整串」或「省略号分段」。实测（20260917-story-0001 第 6-17 章）有 8 条 BLOCKING 因此被判
     * 证据不成立并**整条降级为 MINOR**（不进修订、不落债），其中数条只是：
     * 正文「他**将**周彦的那块骨片…」→引文「**陈默将**周彦的那块骨片…」（补主语）；
     * 正文「陈默**停下脚步，**从怀里摸出一枚碎灵石。」→引文「**陈默**从怀里摸出一枚碎灵石。」（丢前置分句）。
     *
     * <p><b>为什么不直接用 {@link #classify}</b>：它会把证据 {@link #truncate} 到 50 字，
     * 等于只校验引文开头，对 BLOCKING 来说太松。
     *
     * <p><b>口径</b>：按句读与省略号切段，只对长段（≥ {@link #SEGMENT_MIN_LENGTH}）提要求，
     * 要求**半数及以上**长段能在正文中逐字定位；一条长段都定位不到即整条不成立。
     * 这样「纯账本摘录」「转述式引文」仍会被拒（它们没有可锚定的正文长段），
     * 而「补主语/丢前置分句」这种表述差异不再导致整条证据作废。
     *
     * @param evidence          待校验证据
     * @param normalizedContent 已归一化的正文（调用方批量复用以省重复归一化）
     */
    public static boolean segmentsContained(String evidence, String normalizedContent) {
        String normalized = normalize(evidence);
        if (normalized.isEmpty() || normalizedContent == null) {
            return false;
        }
        // 快路径：整串命中（含省略号分段全命中），与旧口径保持一致
        if (contained(evidence, normalizedContent)) {
            return true;
        }
        String[] segments = PHRASE_SEPARATOR.split(normalized);
        int longSegments = 0;
        int hits = 0;
        for (String segment : segments) {
            if (segment.length() < SEGMENT_MIN_LENGTH) {
                continue;
            }
            longSegments++;
            if (normalizedContent.contains(segment)) {
                hits++;
            }
        }
        if (longSegments == 0) {
            // 整条都是短段：没有可锚定的长片段，退回整串判定（宁可漏放，不可放过）
            return false;
        }
        return hits >= Math.max(1, (int) Math.ceil(longSegments * SEGMENT_HIT_RATIO));
    }

    /**
     * 分档判定（摘要入账口径）。
     *
     * <p>空证据不属本类判定范围——门 1（状态账本）对空证据沿用旧规则直接入账、门 2（一致性事实）
     * 对空证据直接隔离，两者策略相反，故由调用方先行分流。此处为安全返回 {@link Tier#EXACT}。
     *
     * @param evidence 模型给出的正文引用（可为任意长度，内部截断）
     * @param content  当章正文原文
     */
    public static Tier classify(String evidence, String content) {
        if (StringUtils.isBlank(evidence)) {
            return Tier.EXACT;
        }
        String trimmed = truncate(evidence, STATE_EVIDENCE_MAX_LENGTH);
        if (StringUtils.isBlank(content)) {
            return Tier.NO_MATCH;
        }
        if (content.contains(trimmed)) {
            return Tier.EXACT;
        }
        String normalizedContent = normalize(content);
        String normalizedEvidence = normalize(trimmed);
        if (normalizedEvidence.isEmpty()) {
            return Tier.NO_MATCH;
        }
        if (normalizedContent.contains(normalizedEvidence)) {
            return Tier.NORMALIZED;
        }
        Tier fragmented = matchFragments(normalizedEvidence, normalizedContent);
        return fragmented != null ? fragmented : matchPhrases(normalizedEvidence, normalizedContent);
    }

    /**
     * 省略号分段命中：各段均可在正文中定位，且最小包围区间不超过正文的 {@link #SPAN_COMPACT_RATIO}。
     *
     * @return 命中档位；某段完全找不到返回 null（交由下一档继续判定）
     */
    private static Tier matchFragments(String normalizedEvidence, String normalizedContent) {
        String[] fragments = ELLIPSIS.split(normalizedEvidence);
        if (fragments.length <= 1) {
            return null;
        }
        int minStart = Integer.MAX_VALUE;
        int maxEnd = -1;
        int cursor = 0;
        for (String fragment : fragments) {
            if (fragment.isEmpty()) {
                continue;
            }
            int index = normalizedContent.indexOf(fragment, cursor);
            if (index < 0) {
                // 模型可能重排同一段对话的语序，回退全局查找；语序不影响可信度，跨度才影响
                index = normalizedContent.indexOf(fragment);
                if (index < 0) {
                    return null;
                }
            }
            minStart = Math.min(minStart, index);
            maxEnd = Math.max(maxEnd, index + fragment.length());
            cursor = index + fragment.length();
        }
        if (maxEnd < 0) {
            return null;
        }
        int span = maxEnd - minStart;
        double ratio = normalizedContent.isEmpty()
                ? 0d : (double) span / normalizedContent.length();
        return ratio <= SPAN_COMPACT_RATIO || span <= SPAN_COMPACT_MAX_SPAN
                ? Tier.FRAGMENTED : Tier.SPREAD;
    }

    /**
     * 短语覆盖命中：按标点切出的长短语能在正文中找到。
     * 覆盖的是「模型改写了连接处措辞、但各语义单元仍是原文」这一类引用。
     * 全部命中且短语数 ≥ {@link #PHRASE_MIN_COUNT} → {@link Tier#PHRASE_COVERED}；
     * 只剩单条短语但长度 ≥ {@link #ANCHOR_MIN_LENGTH} → {@link Tier#ANCHORED}（条数少不等于证据弱）；
     * 其余（部分命中 / 单条短锚）→ {@link Tier#PHRASE_PARTIAL}，交裁决层。
     */
    private static Tier matchPhrases(String normalizedEvidence, String normalizedContent) {
        String[] phrases = PHRASE_SEPARATOR.split(normalizedEvidence);
        int total = 0;
        int hit = 0;
        int longest = 0;
        for (String phrase : phrases) {
            if (phrase.length() < PHRASE_MIN_LENGTH) {
                continue;
            }
            total++;
            longest = Math.max(longest, phrase.length());
            if (normalizedContent.contains(phrase)) {
                hit++;
            }
        }
        if (total == 0 || hit == 0) {
            return Tier.NO_MATCH;
        }
        if (hit < total) {
            // 部分短语命中：模型改写了部分措辞，不足以自证，交裁决层
            return Tier.PHRASE_PARTIAL;
        }
        if (total >= PHRASE_MIN_COUNT) {
            return Tier.PHRASE_COVERED;
        }
        // 只剩单条短语：以锚定强度而非段数判可信，短锚仍交裁决层
        return longest >= ANCHOR_MIN_LENGTH ? Tier.ANCHORED : Tier.PHRASE_PARTIAL;
    }
}
