package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对白判据（机械、零 LLM 成本）：从两个维度度量"角色互动是否够"。
 *
 * <p><b>为什么是两维</b>（2026-09-23）：行级占比只回答"引号行占比多少"，会漏掉一种典型失手——
 * 占比合格但**轮次稀疏**（几段长对白凑比例）。都市校园恋爱那本实测行级占比 25%（勉强合格），
 * 而轮次密度只有 7.4/千字（同批其他新书 10.0-10.9，全样本中位 9.5），读者反馈"对话太少、
 * 张力不足"——占比抓不到，密度能抓到。恋爱/智斗的拉扯感来自一来一回的**次数**，不是单段长度。
 *
 * <p><b>阈值标定</b>（388 章实测，2026-09-23）：全样本轮次密度 均值 9.9／中位 9.5／p10 4.3；
 * 玄幻两本 13.3 与 6.9（题材内部差异大）；都市恋爱那本 7.4。行级占比：旧书 45.6%（p10 27.9%）、
 * 独白坍缩本 11%。对话驱动题材（ROMANCE）要得更严：密度 OK ≥ 10.0 / DEGRADED < 8.0。
 */
public final class DialogueRatioPolicy {

    // ---------- 行级占比（全局线：实测两题材几乎无差异，不为题材感知而硬套） ----------
    // romance 35 章 均值 0.370／中位 0.375；fantasy 353 章 均值 0.344／中位 0.324——差异不足以分线
    private static final double RATIO_OK = 0.28;
    private static final double RATIO_DEGRADED = 0.16;
    // ---------- 轮次密度（每千**有效字**）（题材差异显著，故分线） ----------
    // 全样本 200 章：均值 15.2／中位 15.0／p10 8.3；恋爱同类 11.5-12.6；被评那本 8.4（离群低）
    private static final double DENSITY_OK = 9.0;
    private static final double DENSITY_DEGRADED = 6.0;
    private static final double DENSITY_OK_DIALOGUE_DRIVEN = 11.5;
    private static final double DENSITY_DEGRADED_DIALOGUE_DRIVEN = 9.0;

    private static final String[] QUOTES = {"「", "」", "“", "”", "\""};
    /** 对白轮次：一对中文/直角/ASCII 引号包裹的发言算 1 次（跨行用 DOTALL 非贪婪） */
    private static final Pattern UTTERANCE = Pattern.compile("(?:[“「].*?[”」]|\"[^\"\\r\\n]*\")", Pattern.DOTALL);

    private DialogueRatioPolicy() {
    }

    /** 是否对话驱动题材（轮次要求更严）。当前只认 ROMANCE——DEFAULT 下的智斗/掉马子类尚未覆盖，
     *  需按数据再定；已知局限记在此处，避免日后误以为"已全面覆盖" */
    public static boolean isDialogueDriven(String genreCode) {
        return "romance".equalsIgnoreCase(genreCode);
    }

    /** 行级占比的健康线（全局；题材差异实测不足以分线） */
    public static double ratioOk() {
        return RATIO_OK;
    }

    /** 行级占比的劣化线（全局） */
    public static double ratioDegraded() {
        return RATIO_DEGRADED;
    }

    public static double densityOk(String genreCode) {
        return isDialogueDriven(genreCode) ? DENSITY_OK_DIALOGUE_DRIVEN : DENSITY_OK;
    }

    public static double densityDegraded(String genreCode) {
        return isDialogueDriven(genreCode) ? DENSITY_DEGRADED_DIALOGUE_DRIVEN : DENSITY_DEGRADED;
    }

    /** 对白行占比（0~1）：含引号的非空行 / 全部非空行；无有效行返回 0 */
    public static double ratioOf(String content) {
        if (content == null || content.isBlank()) {
            return 0.0;
        }
        List<String> lines = List.of(content.split("\n"));
        int nonBlank = 0;
        int dialogue = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            nonBlank++;
            for (String q : QUOTES) {
                if (line.contains(q)) {
                    dialogue++;
                    break;
                }
            }
        }
        return nonBlank == 0 ? 0.0 : (double) dialogue / nonBlank;
    }

    /** 对白轮次：引号成对包裹的发言次数 */
    public static int utteranceCount(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        Matcher matcher = UTTERANCE.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 轮次密度 = 轮次 / (有效字/1000)；有效字缺失或为 0 时返回 0（观测层跳过该章） */
    public static double utteranceDensity(int utterances, Integer effectiveChars) {
        if (effectiveChars == null || effectiveChars <= 0) {
            return 0.0;
        }
        return utterances / (effectiveChars / 1000.0);
    }

    /** 判坍缩的最小篇幅：更短的正文样本太小，宁可漏报不误伤 */
    private static final int FORMAT_COLLAPSE_MIN_CHARS = 800;

    /**
     * 对白格式坍缩检测（机械，P1，2026-09-29 阅读实测后新增）：有篇幅的正文却<b>零对白引号</b>——
     * 读者无法区分叙述与角色发言。典型成因是写手把生成 prompt 的"纯文本禁令"过度泛化到对白
     * （禁令原文只针对 Markdown 与章节编号等元信息，对白引号是显式豁免项）。
     *
     * <p>触发条件从严（宁漏勿滥）：非 transition 章、篇幅 ≥ {@value #FORMAT_COLLAPSE_MIN_CHARS} 字、
     * 引号行占比与轮次<b>双双为零</b>才判——出现任何一处引号都算写作选择而非格式坍缩。
     * MINOR 落债回灌（不进修订：根因已在生成 prompt 侧修复，为补引号整章重写不成比例）。
     */
    public static List<ChapterIssueEntity> checkFormatCollapse(String content, String chapterTypeCode) {
        if (StringUtils.isBlank(content) || content.length() < FORMAT_COLLAPSE_MIN_CHARS) {
            return List.of();
        }
        if (chapterTypeCode != null && "transition".equalsIgnoreCase(chapterTypeCode.trim())) {
            return List.of();
        }
        if (utteranceCount(content) > 0 || ratioOf(content) > 0) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity("MINOR");
        issue.setEvidence("全章 " + content.length() + " 字，对白引号出现 0 次（对白轮次=0，行占比=0）");
        issue.setDescription("对白格式坍缩：全章没有任何引号包裹的角色发言，叙述与对白混作一团，"
                + "读者无法分辨谁在说话——疑似生成时把纯文本禁令误扩展到了对白。");
        issue.setSuggestion("为全部角色出声发言补「」（或中文双引号）包裹；注意纯文本禁令只约束 "
                + "Markdown 标记与章节编号等元信息，对白引号合法且必需。");
        return List.of(issue);
    }

    /** 括号包对话的最小篇幅：短样本太小，宁可漏报不误伤（与格式坍缩同口径） */
    private static final int BRACKET_DIALOGUE_MIN_CHARS = 800;

    /** 疑为台词的行：整行以括号起止、且内容长度像一句话（避免误伤"（此时他还不知道）"这类旁注） */
    private static final Pattern BRACKET_LINE =
            Pattern.compile("^\\s*[（(【\\[][^）)】\\]]{6,80}[）)】\\]]\\s*$");

    /** 台词常见的语气/人称线索：命中其一才算"像台词"，纯说明性旁注不算 */
    private static final String[] SPEECH_HINTS = {
            "你", "我", "他", "她", "咱", "们", "吧", "呢", "吗", "啊", "呀", "了", "不", "么",
            "什么", "怎么", "为什", "走", "来", "去", "好", "行", "是", "别", "要", "能", "会"};

    /**
     * 「括号包对话」检测（机械，P1，2026-10-01 新增）：角色台词被写成 {@code （……）} 而不是「……」。
     *
     * <p><b>为什么单独判</b>：这类写法不会被 {@link #checkFormatCollapse} 抓到——文里确实有引号
     * （叙述里的引用、书名号等），只是**台词本身**没用引号；而它的危害是双重的：
     * <ol>
     *   <li>{@link #utteranceCount} 与 {@link #ratioOf} 都不计入被括号包裹的句子 ⇒
     *       「对白行占比」「轮次密度」**双双虚高**，把对话坍缩伪装成达标；</li>
     *   <li>读者无法区分角色发言与旁白，{@code （} 在中文习惯里是**旁注/舞台提示**，用它当引号是硬伤。</li>
     * </ol>
     *
     * <p>判据从严（宁漏勿滥）：<b>整行</b>是括号起止、长度 6~80 字、且含语气/人称线索；
     * 命中 {@value #BRACKET_DIALOGUE_MIN_LINES} 行以上才报。单独的 {@code （此时天还没亮）}
     * 这类旁注不会命中——它不含人称语气词，长度也常在 6 字以下。
     *
     * <p>MINOR 落债回灌，不进修订：这是格式口径问题，整章重写不成比例；
     * 且改法机械（括号换「」），由下一章的规则注入自然收敛。
     */
    public static List<ChapterIssueEntity> checkBracketDialogue(String content, String chapterTypeCode) {
        if (StringUtils.isBlank(content) || content.length() < BRACKET_DIALOGUE_MIN_CHARS) {
            return List.of();
        }
        if (chapterTypeCode != null && "transition".equalsIgnoreCase(chapterTypeCode.trim())) {
            return List.of();
        }
        List<String> samples = new ArrayList<>();
        for (String line : content.split("\\n")) {
            String trimmed = line.trim();
            if (!BRACKET_LINE.matcher(trimmed).matches()) {
                continue;
            }
            // 去掉括号本身后再判"像不像一句话"，避免括号内残留标点干扰
            String inner = trimmed.replaceAll("^[（(【\\[]|[）)】\\]]$", "");
            if (!containsAny(inner, SPEECH_HINTS)) {
                continue;
            }
            samples.add(StringUtils.abbreviate(trimmed, 40));
            if (samples.size() >= 3) {
                break;
            }
        }
        if (samples.size() < BRACKET_DIALOGUE_MIN_LINES) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity("MINOR");
        issue.setEvidence("疑似以括号标注台词的整行 ≥ " + samples.size() + " 处：" + samples);
        issue.setDescription("角色台词用括号包裹而非引号：括号在中文排版里是旁注/舞台提示，"
                + "用它当引号会让读者分不清谁在说话；且这类句子不被计入对白统计，"
                + "会把「对白行占比」「轮次密度」虚报成达标。");
        issue.setSuggestion("把全部人物台词改成成对的中文引号「」：括号只留给旁注与说明。"
                + "引号内只放实际说出的话，动作与神态放在引号外的叙述里。");
        return List.of(issue);
    }

    /** 括号包对话的判定行数门槛：单行可能是排版偶然，多行才是系统性写法 */
    private static final int BRACKET_DIALOGUE_MIN_LINES = 2;

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
