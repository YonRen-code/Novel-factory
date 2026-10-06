package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 已用情节模式判据（2026-09-29）：把"最近 N 章在什么舞台、什么章型、发生了什么事"做成
 * <b>可机械比对的模式指纹</b>，回灌给<b>规划层</b>在编排那一刻避开重复。
 *
 * <p><b>为什么要有它</b>：正文层原本已有一份"已用情节模式黑名单"，但它在四个维度上被削弱——
 * <ol>
 *   <li><b>只在正文层注入</b>：而重复的源头在规划层（规划决定舞台与事件，正文只是照办）；</li>
 *   <li><b>内容复述而非模式抽象</b>：原实现产出的是"最近 5 章摘要前 80 字 + 首个节拍 goal→结果"，
 *       模型读到"第 12 章：解决五金铺经营危机"并不会推断出"别再拿五金铺当舞台"；</li>
 *   <li><b>窗口只有 5 章</b>：更早的套路早已滑出窗口；</li>
 *   <li><b>且自第 12 章起被前缀预算守门每章丢弃</b>（实测第 12–16 章连续被丢弃）。</li>
 * </ol>
 *
 * <p><b>本类做什么</b>：在内容复述之上补一层<b>指纹统计</b>——按（舞台 × 章型）聚合最近 N 章，
 * 对达到重复阈值的组合<b>点名</b>。它不理解剧情，只做机械计数并说清"哪个舞台用了多少次"，
 * 与 {@link PlaceTrajectoryPolicy}、{@link ChapterTitlePolicy} 同属
 * "把现状摆到规划层面前"的范式（观测不到就不会被修）。
 *
 * <p><b>刻意不把 suspenseBeat 纳入指纹</b>：档位表是按阶段各自生成的，档位号跨阶段不可比，
 * 混进指纹会得出无意义的组合。悬念推进由 {@link SuspenseLadderPolicy} 单独负责，两者不重叠。
 *
 * <p>本类只产<b>建议与警示</b>（WARN 语义），不进任何 BLOCKING 闸门。
 */
public final class UsedPatternPolicy {

    /** 正文层沿用最近 5 章（与原黑名单窗口一致，保持行为可比、便于对照） */
    public static final int BODY_LOOKBACK = 5;

    /**
     * 规划层回看更长：编排一段 5 章的计划时只看 5 章，容易把上一段的套路原样再来一遍——
     * 实测"老陆五金铺"在第 8–16 章之间反复出现，5 章窗口根本看不到完整重复史。
     */
    public static final int PLAN_LOOKBACK = 10;

    /** 同一舞台在窗口内出现达到该次数即点名（反复用同一舞台与"每章换场"是一对反向病症） */
    private static final int PLACE_REPEAT_ALERT = 3;

    /** 同一（舞台 × 章型）组合达到该次数即点名 */
    private static final int COMBO_REPEAT_ALERT = 3;

    /** 内容复述部分单章摘要的截断长度 */
    private static final int SUMMARY_EXCERPT = 80;

    /**
     * 冲突动词表：角色节拍的 goal/decision 命中任一，即计该角色"在本章发起冲突"。
     *
     * <p>用结构化标签而不是文本相似度，是因为实测后者的区分力不足（见 {@link #render} 中的说明）。
     * 词表刻意保持小而通用，覆盖"制造麻烦"这一族动作；若日后要按题材扩展，
     * 可照 {@code FatiguePatternCatalog} 的做法外置为 rules 文件。
     */
    private static final String[] CONFLICT_MARKERS = {
            "抢", "夺", "报复", "揭穿", "打", "骂", "逼", "威胁", "骗", "偷", "拦", "堵",
            "施压", "催促", "栽赃", "恐吓", "争执", "刁难", "挤兑", "挑衅", "找茬"};

    /** 同一角色在窗口内作为冲突发起方达到该章数即点名（3 次即"反复"，实测胖墩为 3 次） */
    private static final int CONFLICT_ROLE_ALERT = 3;

    /** 能力展示频次的告警阈值：按窗口长度的约一半起算（5 章窗口 2 次即告警，10 章窗口 5 次），
     *  且至少为 2——新书 6-10 章实测 5/5 章连续展示，读者第 3 次即可预判套路 */
    private static int abilityShowcaseAlertThreshold(int windowSize) {
        return Math.max(2, windowSize / 2);
    }

    private UsedPatternPolicy() {
    }

    /**
     * 渲染【已用情节模式】块；窗口内无任何已产出章节时返回 null（首发不注入）。
     *
     * @param beforeChapterNo 只统计章号 <b>小于</b> 该值的章节（本章/本段尚未发生的不能算"已用"）
     * @param lookback        回看章数；&le;0 时用 {@link #BODY_LOOKBACK}
     */
    public static String render(List<ChapterSummaryEntity> summaries, int beforeChapterNo, int lookback) {
        List<ChapterSummaryEntity> window = window(summaries, beforeChapterNo, lookback <= 0 ? BODY_LOOKBACK : lookback);
        if (window.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【已用情节模式·本章必须避开】（以下为最近 ")
                .append(window.size()).append(" 章已使用的舞台与核心情节）");

        // 一、模式指纹：按舞台聚合，重复达到阈值即点名
        Map<String, List<ChapterSummaryEntity>> byPlace = new LinkedHashMap<>();
        for (ChapterSummaryEntity s : window) {
            byPlace.computeIfAbsent(placeLabel(s), k -> new ArrayList<>()).add(s);
        }
        sb.append("\n一、舞台复用统计：");
        List<String> alerts = new ArrayList<>();
        for (Map.Entry<String, List<ChapterSummaryEntity>> entry : byPlace.entrySet()) {
            List<ChapterSummaryEntity> group = entry.getValue();
            Map<String, Integer> typeCount = new TreeMap<>();
            for (ChapterSummaryEntity s : group) {
                typeCount.merge(typeLabel(s), 1, Integer::sum);
            }
            sb.append("\n- ").append(entry.getKey()).append(" ×").append(group.size()).append("（");
            boolean first = true;
            for (Map.Entry<String, Integer> tc : typeCount.entrySet()) {
                sb.append(first ? "" : "、").append(tc.getKey()).append(tc.getValue());
                first = false;
            }
            sb.append("；第").append(chapterNumbers(group)).append("章）");
            if (group.size() >= PLACE_REPEAT_ALERT) {
                alerts.add("舞台「" + entry.getKey() + "」在最近 " + window.size() + " 章已用 "
                        + group.size() + " 次");
            }
            if (group.size() >= COMBO_REPEAT_ALERT && typeCount.size() == 1) {
                alerts.add("「" + entry.getKey() + " · " + typeCount.keySet().iterator().next()
                        + "」这一组合已连用 " + group.size() + " 次");
            }
        }

        // 二、内容复述：保留原黑名单的信息量（模型需要知道"具体发生过什么"才谈得上避开）
        sb.append("\n二、各章核心情节：");
        for (ChapterSummaryEntity s : window) {
            sb.append("\n- 第").append(s.getChapterNo()).append("章（")
                    .append(placeLabel(s)).append(" · ").append(typeLabel(s)).append("）：")
                    .append(excerpt(s.getSummary()));
            String beat = firstBeat(s);
            if (beat != null) {
                sb.append("；").append(beat);
            }
        }

        // 三、角色冲突发起频次（2026-09-29 补）
        // ⚠️ 刻意**不用文本相似度**判定套路重复——这是实测换来的结论：同一个套路
        //（"抢夺 → 被物证揭穿 → 长辈介入 → 逃离"）在三次出现时的节拍文本，两两 3-gram Jaccard
        // ≤0.09（胖墩第 3 章 vs 第 19 章仅 0.02）。套路是**抽象结构**的重复，措辞每次不同，
        // 相似度路线没有区分力（试过并放弃）。故改用"角色 + 冲突动词"这种结构化标签：
        // 它抓的不是措辞，而是"谁在反复制造麻烦"。
        Map<String, List<ChapterSummaryEntity>> conflictByRole = new LinkedHashMap<>();
        Map<String, String> sampleByRole = new LinkedHashMap<>();
        for (ChapterSummaryEntity s : window) {
            if (s.getCharacterBeats() == null) {
                continue;
            }
            for (ChapterSummaryEntity.CharacterBeat beat : s.getCharacterBeats()) {
                if (beat == null || StringUtils.isBlank(beat.getName())) {
                    continue;
                }
                String text = StringUtils.defaultString(beat.getGoal())
                        + StringUtils.defaultString(beat.getDecision());
                if (!containsConflictMarker(text)) {
                    continue;
                }
                String role = beat.getName().trim();
                List<ChapterSummaryEntity> seen = conflictByRole.computeIfAbsent(role, k -> new ArrayList<>());
                if (seen.isEmpty() || !seen.get(seen.size() - 1).getChapterNo().equals(s.getChapterNo())) {
                    seen.add(s);
                }
                sampleByRole.putIfAbsent(role, StringUtils.defaultString(beat.getGoal()).trim());
            }
        }
        if (!conflictByRole.isEmpty()) {
            sb.append("\n三、角色冲突发起频次（同一角色反复当「麻烦制造者」，是最典型的套路化指纹）：");
            for (Map.Entry<String, List<ChapterSummaryEntity>> entry : conflictByRole.entrySet()) {
                List<ChapterSummaryEntity> seen = entry.getValue();
                sb.append("\n- ").append(entry.getKey()).append(" ×").append(seen.size())
                        .append("（第").append(chapterNumbers(seen)).append("章）");
                String sample = sampleByRole.get(entry.getKey());
                if (StringUtils.isNotBlank(sample)) {
                    sb.append("；典型：").append(excerpt(sample));
                }
                if (seen.size() >= CONFLICT_ROLE_ALERT) {
                    alerts.add("角色「" + entry.getKey() + "」在最近 " + window.size() + " 章有 "
                            + seen.size() + " 章作为冲突发起方");
                }
            }
            sb.append("\n被点名的角色若再次登场，必须更换冲突形态（争夺对象 / 手段 / 对手 / 结局至少换两项），"
                    + "或直接让它暂时退出冲突线——同一个角色反复被同一种方式收拾，读者立刻能看出来。");
        }

        // 四、能力/早慧展示频次（2026-10-04 补，新书 6-10 章实测）：「主角微动作展示 → 旁人注意/评价」
        // 这一抽象结构连续复用（5 章 5 次，每次只换载体：蘸水画圈/盯钢琴/翻数字页），是早慧/重生题材
        // 最典型的套路化指纹。与第三节同理由：抽象结构重复抓不了文本相似度，只能靠摘要的结构化标注
        //（abilityShowcased/abilityDisplayForm）——标注是"展示时刻"的判定，不是关键词匹配。
        List<ChapterSummaryEntity> showcases = new ArrayList<>();
        for (ChapterSummaryEntity s : window) {
            if (Boolean.TRUE.equals(s.getAbilityShowcased())) {
                showcases.add(s);
            }
        }
        if (!showcases.isEmpty()) {
            sb.append("\n四、能力/早慧展示频次（「主角微动作展示 → 旁人注意/评价」结构的重复，")
                    .append("是早慧题材最典型的套路指纹）：");
            for (ChapterSummaryEntity s : showcases) {
                sb.append("\n- 第").append(s.getChapterNo()).append("章");
                if (StringUtils.isNotBlank(s.getAbilityDisplayForm())) {
                    sb.append("（").append(excerpt(s.getAbilityDisplayForm())).append("）");
                }
            }
            int threshold = abilityShowcaseAlertThreshold(window.size());
            if (showcases.size() >= threshold) {
                sb.append("\n⚠️ 最近 ").append(window.size()).append(" 章已有 ").append(showcases.size())
                        .append(" 章安排能力/早慧展示——**接下来不得再连续安排**此类场景：")
                        .append("「展示时刻」连续出现会模板化，读者第 N 次看到『主角做出微动作 → 旁人惊叹不像孩子』")
                        .append("就会厌烦。后续章节的推进改由日常细节、人际事件、选择与两难承担；")
                        .append("早慧的显形依靠观察者**跨章累积**的认知变化（推进单位是他人认知的改变，")
                        .append("不是主角展示本身）；确需展示，必须换用与上面各章**完全不同**的载体与场景功能，")
                        .append("且不得再使用「旁人评价不像孩子」这一形态。");
                alerts.add("能力/早慧展示在最近 " + window.size() + " 章中已出现 " + showcases.size() + " 次");
            }
        }

        sb.append("\n本段要求：同类情节必须更换舞台、对手、困难类型、解决方式或视角之一；");
        sb.append("上面点名的舞台若再次使用，必须带来与前几次实质不同的冲突，不得只是换一批人重演同一件事。");
        if (!alerts.isEmpty()) {
            sb.append("\n⚠ 重复点名：");
            sb.append(String.join("；", alerts)).append("。");
        }
        return sb.toString();
    }

    /** 节拍文本是否含冲突动词（结构化标签，不依赖语义理解） */
    private static boolean containsConflictMarker(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        for (String marker : CONFLICT_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 只保留章号小于 beforeChapterNo 的章节，取最近 lookback 章 */
    private static List<ChapterSummaryEntity> window(List<ChapterSummaryEntity> summaries,
                                                     int beforeChapterNo, int lookback) {
        List<ChapterSummaryEntity> eligible = new ArrayList<>();
        if (summaries == null) {
            return eligible;
        }
        for (ChapterSummaryEntity s : summaries) {
            if (s == null || s.getChapterNo() == null) {
                continue;
            }
            if (s.getChapterNo() < beforeChapterNo) {
                eligible.add(s);
            }
        }
        int from = Math.max(0, eligible.size() - lookback);
        return new ArrayList<>(eligible.subList(from, eligible.size()));
    }

    /** 舞台标签：与地点轨迹同一口径（placePoint 优先，timePoint 回退），取不到时归为"（未记录地点）" */
    private static String placeLabel(ChapterSummaryEntity summary) {
        String place = PlaceTrajectoryPolicy.placeOf(summary);
        return StringUtils.isBlank(place) ? "（未记录地点）" : place;
    }

    private static String typeLabel(ChapterSummaryEntity summary) {
        return StringUtils.isBlank(summary.getChapterType()) ? "normal" : summary.getChapterType();
    }

    private static String chapterNumbers(List<ChapterSummaryEntity> group) {
        Set<Integer> nos = new LinkedHashSet<>();
        for (ChapterSummaryEntity s : group) {
            nos.add(s.getChapterNo());
        }
        return String.join("/", nos.stream().map(String::valueOf).toList());
    }

    private static String excerpt(String summary) {
        if (StringUtils.isBlank(summary)) {
            return "（无摘要）";
        }
        String trimmed = summary.trim();
        return trimmed.length() > SUMMARY_EXCERPT ? trimmed.substring(0, SUMMARY_EXCERPT) + "…" : trimmed;
    }

    /** 首个角色节拍的 goal→consequence；无节拍返回 null */
    private static String firstBeat(ChapterSummaryEntity summary) {
        if (summary.getCharacterBeats() == null || summary.getCharacterBeats().isEmpty()) {
            return null;
        }
        for (ChapterSummaryEntity.CharacterBeat beat : summary.getCharacterBeats()) {
            if (beat == null || StringUtils.isBlank(beat.getGoal())) {
                continue;
            }
            String text = "目标：" + beat.getGoal();
            return StringUtils.isBlank(beat.getConsequence())
                    ? text : text + "→结果：" + beat.getConsequence();
        }
        return null;
    }
}
