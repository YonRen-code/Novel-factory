package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.types.utils.ChapterTitleNormalizer;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 章节标题唯一性判据：机械检测"两章同名"，并把已用标题回灌规划层从源头避免。
 *
 * <p><b>问题</b>：全书标题唯一性此前只靠 prompt 请求，没有任何机械检查。长篇连载里
 * "新的开始""风暴前夕""暗流涌动"这类模板标题会反复出现，读者与检索都会混淆；
 * {@link ChapterTitleNormalizer} 只负责剥"第N章"前缀，不是唯一性检测。
 *
 * <p><b>为什么是 MINOR 而不是 BLOCKING</b>（沿用 2026-09-16 严重度分层原则）：
 * BLOCKING 的语义是"必须定向修订"，而修订链路是<em>整章重写</em>——为改一个标题重写整章
 * 既不成比例，也不保证重写后标题会变。真正的根治点在规划层：因此本判据产出 MINOR
 * （只记录 + 随质量债回灌下一章），并由 {@link #renderUsedTitles} 把已用标题清单
 * 直接注入规划 prompt，让模型在**起名时**就避开。
 *
 * <p><b>等价键口径</b>：先走 {@code ChapterTitleNormalizer.normalize} 剥编号前缀，
 * 再剔掉空白与常见标点后比较——这样"第3章的约定"、"第3章的约定。"、"第 3 章 的 约定"
 * 视为同一标题（模型对同一标题的标点/空格写法并不稳定）。刻意<b>不做</b>模糊相似度匹配：
 * "风暴"与"风暴前夕"是不同标题，近似匹配会误报，误报的代价（逼模型改好标题）比漏报更高。
 */
public final class ChapterTitlePolicy {

    /** 注入规划 prompt 的已用标题数量上限（按最近使用倒序取；超出部分只报计数） */
    public static final int RENDER_LIMIT = 300;

    /** 等价键需剔除的字符：空白 + 中英文常见标点（标题里的标点不承载区分语义） */
    private static final Pattern IGNORED_CHARS =
            Pattern.compile("[\\s\\u3000，,。.、；;：:！!？?…·—\\-－「」『』“”\"'‘’《》〈〉()（）\\[\\]【】]+");

    private ChapterTitlePolicy() {
    }

    /**
     * 标题等价键：剥编号前缀 → 剔空白与标点。
     * 归零后为空（如标题只有"……"）时回退为剥前缀后的原文，避免所有异常标题互相等价。
     */
    public static String dedupeKey(String title) {
        if (StringUtils.isBlank(title)) {
            return "";
        }
        String normalized = ChapterTitleNormalizer.normalize(title);
        String key = IGNORED_CHARS.matcher(StringUtils.defaultString(normalized)).replaceAll("");
        return key.isEmpty() ? StringUtils.trimToEmpty(normalized) : key;
    }

    /**
     * 在历史章节里查找与候选标题等价的章节（返回**最早**出现的那一章号，便于提示"与第N章重复"）。
     * 无冲突返回 null；候选为空返回 null。
     */
    public static Integer findDuplicate(String title, List<ChapterSummaryEntity> history) {
        String key = dedupeKey(title);
        if (key.isEmpty() || history == null || history.isEmpty()) {
            return null;
        }
        Integer earliest = null;
        for (ChapterSummaryEntity summary : history) {
            if (summary == null || summary.getChapterNo() == null || StringUtils.isBlank(summary.getTitle())) {
                continue;
            }
            if (!key.equals(dedupeKey(summary.getTitle()))) {
                continue;
            }
            if (earliest == null || summary.getChapterNo() < earliest) {
                earliest = summary.getChapterNo();
            }
        }
        return earliest;
    }

    /**
     * 本次检查：标题与历史重复时产出一条 MINOR issue（dimension=continuity）。
     * 无冲突返回空表。
     */
    public static List<ChapterIssueEntity> check(String title, List<ChapterSummaryEntity> history) {
        Integer duplicateOf = findDuplicate(title, history);
        if (duplicateOf == null) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("continuity");
        issue.setSeverity(StyleViolationPolicy.SEVERITY_MINOR);
        issue.setDescription("章节标题与第 " + duplicateOf + " 章重复（长篇里模板化标题反复出现会让读者与检索混淆）");
        issue.setEvidence(title);
        issue.setSuggestion("换一个贴合本章具体事件/意象的标题，不要用「新的开始」「风暴前夕」这类通用模板标题；"
                + "后续章节起名时请对照【已用章节标题】清单避开");
        return List.of(issue);
    }

    /**
     * 渲染【已用章节标题】提示块（回灌规划层）；无任何历史标题返回 null。
     *
     * <p>一次规划段只注入一次，成本可接受（章均标题 8~12 字）；这是本判据<b>治本的那一半</b>——
     * 检测只能事后记账，把清单摆到起名那一刻才能真的少重复。
     */
    public static String renderUsedTitles(List<ChapterSummaryEntity> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        List<ChapterSummaryEntity> ordered = history.stream()
                .filter(s -> s != null && s.getChapterNo() != null && StringUtils.isNotBlank(s.getTitle()))
                .sorted((a, b) -> Integer.compare(b.getChapterNo(), a.getChapterNo()))
                .toList();
        if (ordered.isEmpty()) {
            return null;
        }
        Set<String> distinctKeys = new LinkedHashSet<>();
        List<String> rendered = new ArrayList<>();
        // 注意：**不能**在 rendered 满额时 break——那样 distinctKeys 也停在同一处，
        // 后面的总数就统计不到，"其余省略"提示永远不会触发（去重计数必须走完全程）
        for (ChapterSummaryEntity summary : ordered) {
            String key = dedupeKey(summary.getTitle());
            if (!distinctKeys.add(key)) {
                continue;
            }
            if (rendered.size() < RENDER_LIMIT) {
                rendered.add("第" + summary.getChapterNo() + "章" + summary.getTitle().trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【已用章节标题】以下是已用过的标题（已去重，共 ").append(ordered.size()).append(" 章）。")
                .append("新章标题**严禁与之重复**——含仅标点/空格/数字不同的写法（如「第3章的约定」与「第3章的约定。」视为重复）。")
                .append("尤其不要使用「新的开始」「风暴前夕」这类通用模板标题。");
        sb.append("\n").append(String.join("、", rendered));
        if (rendered.size() < distinctKeys.size()) {
            sb.append("\n（以上为最近的 ").append(rendered.size()).append(" 个不同标题，其余省略）");
        }
        return sb.toString();
    }
}
