package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 机械文风门禁：纯代码扫描单章正文的 AI 味违规（零 LLM 调用）。
 * 词表与 StyleStatService 共用 {@link FatiguePatternCatalog}。
 *
 * <p><b>严重度分层（2026-09-16 依 162 章实测改造）</b>：本类曾把 5 项检查一律标为 BLOCKING，
 * 与审校 rubric（明示「文风与疲劳词」属 MINOR）自相矛盾，并造成 severity 通胀——
 * 实测 162 章数据里本类产出 53 条 BLOCKING，占全部 BLOCKING 债的 37.6%，
 * 其中 <b>人工逐条核验后 0 条成立</b>：
 * <ul>
 *   <li>万能副词密度 41 条：9 条实测/阈值 ≤1.1×，最低仅超 <b>0.014 次/千字</b>；
 *       中位 3.92 而阈值 3.0 —— 阈值标定低于该模型的常态写作水位，属标定问题不是缺陷信号</li>
 *   <li>章末升华 5 条：5/5 误判——词表含「正是/这一刻/这一切」等<em>叙述高频词</em>
 *       （「意识体在这一刻溃散」是时间状语），且判定式用 OR 导致「无对白」段几乎逢报必中</li>
 *   <li>眼神套话 6 条：4/6 恰为 2 次（阈值即 2），且多为<em>不同词各 1 次</em>合计成 2</li>
 *   <li>章节编号元信息 1 条：误判——「第一章」出现在<em>对白内</em>（「这就是《模块化功法导论》的第一章」）</li>
 * </ul>
 *
 * <p><b>分档原则</b>：BLOCKING 的语义是「必须定向修订」，故只授予同时满足
 * ① 损伤不可逆/累积、② 定向修订可收敛 的项。阈值型统计指标（密度、计数）在整章重写下
 * 相当于重投骰子，<em>不可收敛</em>——实测 110 条修订样本全部两轮耗尽，其中 50 条带副词密度残留。
 * 故：
 * <ul>
 *   <li>{@link #SEVERITY_BLOCKING} 仅保留「章节编号元信息泄露」（真泄漏即读者出戏的硬伤，
 *       且是精确串、可定点清除）</li>
 *   <li>其余 4 项降为 {@link #SEVERITY_MINOR}：只记录 + 回灌规划层治本，不触发修订
 *       （与「字数不足不拒收、改走密度信号回灌」同款处理）</li>
 * </ul>
 *
 * <p>判定规则（阈值即 rules/fatigue-patterns.txt 头注释语义）：
 * 1. 万能副词密度：全章命中次数 > 5 次/千字（按有效字符计）
 * 2. 眼神套话：全章合计命中 >= 3 次
 * 3. 身体反应套话复读：同一词条全章命中 >= 2 次
 * 4. 章末升华：最后一段包含升华词 且 既无动作动词 又无对话
 * 5. 章节编号元信息泄露：<em>叙述文本</em>中出现"第X章""本章/上一章"等作者层面元信息
 */
public final class StyleViolationPolicy {

    /** 修订触发档：只有本档进入修订闭环 */
    public static final String SEVERITY_BLOCKING = "BLOCKING";
    /** 记录档：落质量债/供观测统计与规划层回灌，不触发修订也不触发候选选优 */
    public static final String SEVERITY_MINOR = "MINOR";

    /**
     * 万能副词密度上限：次/千字有效字符。
     * 由 3.0 上调至 5.0——实测 41 条命中里中位 3.92、p90 5.07，阈值 3.0 拦到的是
     * 该模型的<em>常态水位</em>而非异常（最低一条仅超 0.014）。5.0 只拦显著超标。
     */
    public static final double ADVERB_MAX_PER_1000_CHARS = 5.0;
    /**
     * 眼神套话全章合计次数上限（达到该值即违规）。
     * 由 2 上调至 3——实测 6 条命中里 4 条恰为 2 次，且多为不同词各 1 次合计成 2。
     */
    public static final int EYE_CLICHE_MAX_OCCURRENCES = 3;
    /** 身体反应套话同一词条全章次数上限（达到该值即违规） */
    public static final int BODY_CLICHE_SAME_WORD_MAX = 2;
    /** 章末升华检测：最后一段最小长度（低于此长度不触发） */
    public static final int ENDING_ELEVATION_MIN_LENGTH = 30;

    /** 章节编号元信息泄露正则：匹配"第12章""第十二章""本章""上一章""下一章"等作者层面的元信息 */
    private static final java.util.regex.Pattern CHAPTER_REF_PATTERN =
            java.util.regex.Pattern.compile("第[\\d一二三四五六七八九十百千零〇两]+章|本章|上一章|下一章|前一章|后一章|此章");

    /**
     * 章末升华总结性词汇表。
     * <p>2026-09-16 剔除「正是/这一刻/这一切/原来如此/便是/终于/终究/注定」——
     * 这些是<em>叙述高频词</em>：「正在这一刻溃散」是时间状语、「正是X」是指认用法、
     * 「注视着这一切」的「这一切」是代词宾语，把它们当升华信号必然误判（实测 4/5 由此误报）。
     */
    private static final String[] ELEVATION_KEYWORDS = {
            "从此", "这就是", "归根结底", "总而言之",
            "他明白了", "她明白了", "他知道了", "她知道了",
            "命运", "宿命", "新的篇章", "故事才刚刚开始",
            "从此以后"
    };

    /** 常见动作动词表（用于判断最后一段是否停在动作上） */
    private static final String[] ACTION_VERBS = {
            "说", "道", "喊", "叫", "笑", "哭", "走", "跑", "站", "坐",
            "拿", "放", "打", "砍", "刺", "握", "攥", "推", "拉", "开",
            "关", "看", "望", "盯", "转", "动", "伸手", "抬头", "低头",
            "转身", "拔出", "抽出", "落下", "升起", "敲响", "推开", "关上"
    };

    /**
     * 扫描正文，返回文风 issue（严重度按项分层，见类注释）。
     * 每条 evidence 为"词条×次数"清单，供修订定点清除（BLOCKING 档）或观测统计（MINOR 档）。
     */
    public static List<ChapterIssueEntity> check(String content) {
        if (StringUtils.isBlank(content)) {
            return List.of();
        }
        FatiguePatternCatalog.Catalog catalog = FatiguePatternCatalog.get();
        int effectiveChars = Math.max(1, ChapterLengthPolicy.effectiveCharacterCount(content));

        List<ChapterIssueEntity> issues = new ArrayList<>();

        // 1. 万能副词密度（程度问题，MINOR）
        Map<String, Integer> adverbHits = countHits(content, catalog.adverbs());
        int adverbTotal = adverbHits.values().stream().mapToInt(Integer::intValue).sum();
        double perThousand = adverbTotal * 1000.0 / effectiveChars;
        if (perThousand > ADVERB_MAX_PER_1000_CHARS) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "万能副词密度超标（" + adverbTotal + " 次 / " + effectiveChars + " 有效字，上限 "
                            + ADVERB_MAX_PER_1000_CHARS + " 次/千字）",
                    renderHits(adverbHits),
                    "删掉后句意不变就删（“他不禁笑了”→“他笑了”）；确需保留的每千字至多 "
                            + ADVERB_MAX_PER_1000_CHARS + " 次"));
        }

        // 2. 眼神套话合计（程度问题，MINOR）
        Map<String, Integer> eyeHits = countHits(content, catalog.eyeCliches());
        int eyeTotal = eyeHits.values().stream().mapToInt(Integer::intValue).sum();
        if (eyeTotal >= EYE_CLICHE_MAX_OCCURRENCES) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "眼神套话命中 " + eyeTotal + " 次（眼睛没有“复杂”这种通用表情，读者无法成像）",
                    renderHits(eyeHits),
                    "改写为视线的落点、停留时长与移动，或从被看者感受侧面写"));
        }

        // 3. 身体反应套话同一词条复读（程度问题，MINOR）
        Map<String, Integer> bodyHits = countHits(content, catalog.bodyCliches());
        Map<String, Integer> repeated = bodyHits.entrySet().stream()
                .filter(e -> e.getValue() >= BODY_CLICHE_SAME_WORD_MAX)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (!repeated.isEmpty()) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "身体反应套话复读（同一词条全章至多 1 次）",
                    renderHits(repeated),
                    "替换为环境烘托、微表情、他人视角反应，或干脆让角色做出反常动作"));
        }

        // 4. 章末升华检测（程度问题，MINOR）
        String endingEvidence = checkEndingElevation(content);
        if (endingEvidence != null) {
            issues.add(buildIssue(
                    SEVERITY_MINOR,
                    "章末升华（结尾停在感悟/总结而非画面或动作上，读者情绪被旁白总结闷死）",
                    endingEvidence,
                    "删掉最后一段的感悟总结，停在具体的画面或动作上；如需留白，用环境细节或角色微动作收尾；严禁“从此/这就是”等金句点题"));
        }

        // 5. 章节编号元信息泄露检测（硬伤，BLOCKING——精确串、可定点清除，读者看到立刻出戏）
        String chapterRefEvidence = checkChapterReference(content);
        if (chapterRefEvidence != null) {
            issues.add(buildIssue(
                    SEVERITY_BLOCKING,
                    "章节编号元信息泄露（叙述中出现作者层面的章节指代，读者立刻出戏，意识到这是AI写的小说）",
                    chapterRefEvidence,
                    "用时间/地点/事件指代替代章节编号：\"第12章他在阵眼核心发现的\"→\"十二天前在阵眼核心发现的\"；\"第28章袭击事件后\"→\"上次遇袭之后\"；\"本章/上一章\"→删除或改为\"此刻/此前\""));
        }

        return issues;
    }

    /**
     * 章末升华检测：取正文最后一个非空段落，若包含升华词且长度达标，
     * 且<em>既</em>缺乏动作动词<em>又</em>无对话，则判定为章末升华。
     *
     * <p><b>判定式由 OR 改为 AND（2026-09-16）</b>：原实现是「无动作 <em>或</em> 无对白」，
     * 于是任何「无对白」的章末段只要含一个升华词就命中——而章末无对白在小说里极常见，
     * 实测第 110 章以对白收尾（有中文引号）仍被判升华，正是该 OR 所致。
     * 「旁白总结」的严格定义应是<em>既无动作也无对白</em>（人话：只说不做、且不是台词）。
     *
     * 返回最后一段的前80字作为 evidence，未命中返回 null。
     */
    private static String checkEndingElevation(String content) {
        String[] paragraphs = content.split("\\n+");
        // 从后往前找第一个长度达标的非空段落
        String lastParagraph = null;
        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String trimmed = paragraphs[i].trim();
            if (trimmed.length() >= ENDING_ELEVATION_MIN_LENGTH) {
                lastParagraph = trimmed;
                break;
            }
        }
        if (lastParagraph == null) return null;

        // 检查是否包含总结性词汇
        boolean hasElevationWord = false;
        for (String keyword : ELEVATION_KEYWORDS) {
            if (lastParagraph.contains(keyword)) {
                hasElevationWord = true;
                break;
            }
        }
        if (!hasElevationWord) return null;

        // 检查是否包含动作动词
        boolean hasAction = false;
        for (String verb : ACTION_VERBS) {
            if (lastParagraph.contains(verb)) {
                hasAction = true;
                break;
            }
        }

        // 检查是否包含对话引号（必须含中文弯引号 U+201C/U+201D：正文实际用弯引号，
        // 只判 ASCII 双引号与「」会漏判，导致末段有对白仍被误判为"章末升华"）
        boolean hasDialogue = lastParagraph.contains("\"") || lastParagraph.contains("\u201C")
                || lastParagraph.contains("\u201D") || lastParagraph.contains("「")
                || lastParagraph.contains("『");

        // 判定：有升华词 + 长度达标 + 既无动作又无对白 → 才是旁白总结
        if (!hasAction && !hasDialogue) {
            String evidence = lastParagraph.length() > 80
                    ? lastParagraph.substring(0, 80) + "..."
                    : lastParagraph;
            return evidence;
        }
        return null;
    }

    /**
     * 章节编号元信息泄露检测：扫描正文章节中是否出现"第X章""本章/上一章"等作者层面的元信息。
     *
     * <p><b>只在叙述文本中判定（2026-09-16 加）</b>：命中点若落在成对引号内即豁免——
     * 「作者层面的元信息」只可能出现在叙述里，<em>不可能是角色台词</em>：角色说
     * 「这就是《模块化功法导论》的第一章」指的是书里的章节，与本书的章节编号无关
     * （实测第 39 章即此例，占该检查全部命中的 1/1）。这是判据口径修正，不是放水。
     *
     * 返回命中清单（如"第12章×2、本章×1"），未命中返回 null。
     */
    public static String checkChapterReference(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = CHAPTER_REF_PATTERN.matcher(content);
        Map<String, Integer> hits = new LinkedHashMap<>();
        while (m.find()) {
            if (insideDialogue(content, m.start())) {
                continue;
            }
            hits.merge(m.group(), 1, Integer::sum);
        }
        if (hits.isEmpty()) return null;
        return hits.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining("、"));
    }

    /** 引号字符（中文弯引号成对出现；ASCII 双引号按奇偶配对计） */
    private static boolean isQuoteChar(char c) {
        return c == '\u201C' || c == '\u201D' || c == '\u300C' || c == '\u300D'
                || c == '\u300E' || c == '\u300F' || c == '"';
    }

    /**
     * 判定某下标是否位于引号（对白）内部：统计其之前的引号字符数，奇数即在引号内。
     * 正文用成对弯引号，奇偶判据足够稳健；混排 ASCII 双引号亦按同一奇偶口径处理。
     */
    static boolean insideDialogue(String content, int pos) {
        int quotes = 0;
        for (int i = 0; i < pos && i < content.length(); i++) {
            if (isQuoteChar(content.charAt(i))) {
                quotes++;
            }
        }
        return quotes % 2 == 1;
    }

    /**
     * 快速检测正文是否包含章节编号元信息（用于 QualityGate 审校前加审预警，不产出 issue）。
     * 与 {@link #checkChapterReference} 同口径：引号内（对白）命中不算，否则预警与 issue 会互相打架。
     * @return true 表示叙述中存在章节编号，false 表示干净
     */
    public static boolean hasChapterReference(String content) {
        if (StringUtils.isBlank(content)) return false;
        java.util.regex.Matcher m = CHAPTER_REF_PATTERN.matcher(content);
        while (m.find()) {
            if (!insideDialogue(content, m.start())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 渲染章节编号加审预警文本（注入 auditGuard，让审校 LLM 特别关注并定向替换）。
     * @param evidence checkChapterReference 返回的命中清单
     * @return 预警文本，未命中返回 null
     */
    public static String renderChapterRefAuditHint(String evidence) {
        if (evidence == null) return null;
        return "【章节编号元信息泄露·加审预警】正文检测到作者层面的章节指代（" + evidence
                + "），读者看到会立刻出戏。审校时必须逐处定位并替换为时间/地点/事件指代："
                + "\"第12章他在阵眼核心发现的\"→\"十二天前在阵眼核心发现的\"；"
                + "\"第28章袭击事件后\"→\"上次遇袭之后\"；\"第6章那个模糊的感知\"→\"之前那次模糊的感知\"；"
                + "\"本章/上一章\"→删除或改为\"此刻/此前\"。修订时必须全部清除，不得残留任何章节编号。";
    }

    private static ChapterIssueEntity buildIssue(String severity, String description,
                                                 String evidence, String suggestion) {
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity(severity);
        issue.setDescription(description);
        issue.setEvidence(evidence);
        issue.setSuggestion(suggestion);
        return issue;
    }

    /** 逐词计数（String.indexOf 线性扫描，正文数千字 × 数十词，量级可忽略） */
    private static Map<String, Integer> countHits(String content, List<String> words) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String word : words) {
            int count = 0;
            int idx = 0;
            while ((idx = content.indexOf(word, idx)) >= 0) {
                count++;
                idx += word.length();
            }
            if (count > 0) {
                hits.put(word, count);
            }
        }
        return hits;
    }

    private static String renderHits(Map<String, Integer> hits) {
        return hits.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining("、"));
    }

    private StyleViolationPolicy() { }
}
