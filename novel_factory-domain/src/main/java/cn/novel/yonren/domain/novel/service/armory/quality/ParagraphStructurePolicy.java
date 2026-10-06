package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落结构兜底（机械，2026-10-01 实测后新增）。
 *
 * <p><b>为什么必须有</b>：实测第 1–10 章批次里 4/10 章的原稿换行数为 0——写手把整章
 * 3000~4600 字当成一个字符串吐出来。其中 2 章因候选选优采纳了挑战者稿而被掩盖，
 * 另外 2 章（ch2 3021 字、ch10 4649 字）直接以"单段巨块"落盘。
 *
 * <p>这段坏文本的危害不止是排版：
 * <ul>
 *   <li>{@link DialogueRatioPolicy#ratioOf} = 含引号行 / 非空行。整章只有 1 行且含引号时
 *       结果恒为 <b>1.0</b>，把「对白行占比」这项体检指标系统性拉高；</li>
 *   <li>段落密度审校（paragraph-audit）按段落找注水，单段巨块会让它无从下手；</li>
 *   <li>读者侧直接不可读。</li>
 * </ul>
 *
 * <p><b>做法是纯机械重排，不改一个字</b>：只在"换行数明显低于篇幅应有段落数"时才介入，
 * 按中文句末标点切句并聚合成段落。任何已有正常换行的正文一律原样返回——
 * 宁可漏修也不动写手的分段选择。
 */
public final class ParagraphStructurePolicy {

    private ParagraphStructurePolicy() {
    }

    /** 判定"正常正文"的每段平均字数：小于该值认为换行充足，不介入 */
    private static final int SUSPECT_CHARS_PER_LINE = 300;

    /** 触发重排的最小篇幅：短正文样本太小，宁可漏修 */
    private static final int REPARAGRAPH_MIN_CHARS = 800;

    /** 单个重排段落的目标字数上沿（句末标点聚合到该长度即断段） */
    private static final int TARGET_PARAGRAPH_CHARS = 120;

    /** 单段绝对上沿：超过则强制断段，避免长句连缀又退回巨块 */
    private static final int MAX_PARAGRAPH_CHARS = 200;

    /**
     * 段落结构兜底：仅当正文换行明显偏少时，按句末标点重排段落。
     *
     * @param content 正文（可能为单段巨块）
     * @return 重排后的正文；无需介入时返回原值（引用相同）
     */
    public static String reparagraph(String content) {
        if (StringUtils.isBlank(content) || content.length() < REPARAGRAPH_MIN_CHARS) {
            return content;
        }
        int lines = countNonBlankLines(content);
        int expected = Math.max(1, content.length() / SUSPECT_CHARS_PER_LINE);
        if (lines >= expected) {
            return content;
        }
        List<String> paragraphs = splitToParagraphs(content);
        if (paragraphs.size() <= lines) {
            // 切不出更多段（例如全篇无句末标点）——保持原样，避免制造无意义差异
            return content;
        }
        return String.join("\n\n", paragraphs);
    }

    /**
     * 是否发生了重排（供调用方留痕：这是"写手格式异常"的信号，值得记日志）。
     */
    public static boolean needsReparagraph(String content) {
        return reparagraph(content) != content;
    }

    /**
     * 机械检测：正文段落结构坍缩。
     *
     * <p>与 {@link #reparagraph} 的分工——本方法只<b>报告</b>写手的格式异常，
     * 用于回灌规划层（提示写手分行）；重排本身是兜底动作，不产生质量结论。
     * 归为 MINOR/aesthetic：根因在写手侧，重写整章不成比例。
     *
     * @param originalContent 重排<b>前</b>的原始正文
     * @param limitChars      段落数下限（由本策略按篇幅推算）
     */
    public static List<ChapterIssueEntity> checkStructureCollapse(String originalContent, int limitChars) {
        if (StringUtils.isBlank(originalContent) || originalContent.length() < REPARAGRAPH_MIN_CHARS) {
            return List.of();
        }
        int lines = countNonBlankLines(originalContent);
        if (lines >= limitChars) {
            return List.of();
        }
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("aesthetic");
        issue.setSeverity("MINOR");
        issue.setEvidence("全章 " + originalContent.length() + " 字，但仅 " + lines + " 个段落"
                + "（本篇幅应有 " + limitChars + " 段以上），最长段 "
                + longestLine(originalContent) + " 字");
        issue.setDescription("段落结构坍缩：整章正文几乎没有换行，被写成一个巨型文本块。"
                + "这会让对白行占比、段落密度审校等按行统计的门禁全部失真"
                + "（实测单段含引号时对白行占比会被误算为 1.0）。");
        issue.setSuggestion("正文须按语义单元分段：场景转换、说话人切换、动作与心理描写交替处均应另起一段，"
                + "段落长度以 40~150 字为宜；落盘前系统已做机械兜底重排，但请从源头避免单段巨块。");
        return List.of(issue);
    }

    /**
     * 本策略对该篇幅要求的段落数下限。
     */
    public static int paragraphLimit(String content) {
        if (StringUtils.isBlank(content) || content.length() < REPARAGRAPH_MIN_CHARS) {
            return 1;
        }
        return Math.max(1, content.length() / SUSPECT_CHARS_PER_LINE);
    }

    private static List<String> splitToParagraphs(String content) {
        // 先按已有换行拆（巨块通常只有 1 行），再对每一行做句级切分并聚合
        List<String> paragraphs = new ArrayList<>();
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() <= MAX_PARAGRAPH_CHARS) {
                paragraphs.add(line);
                continue;
            }
            paragraphs.addAll(packSentences(line));
        }
        return paragraphs;
    }

    /**
     * 按句切分。切点落在句末标点<b>之后</b>（零宽后顾，不吞标点）。
     *
     * <p>关键约束：<b>不得从句子的内部切开</b>。台词形如 {@code 「你来了。」他说。} ——
     * 若允许在 {@code 。} 后切分，会把引号撕成孤立的 {@code 」}，既污染段落也让
     * 对白行判定错乱。所以 {@code 。！？…} 只有在<b>不在引号内</b>时才是合法切点。
     * 这里用不了变长后顾（Java 不支持），改为按引号栈状态手工扫描。
     */
    private static List<String> splitSentences(String line) {
        List<String> sentences = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int quoteDepth = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            buf.append(c);
            if (c == '「' || c == '『' || c == '“') {
                quoteDepth++;
                continue;
            }
            if (c == '」' || c == '』' || c == '”') {
                quoteDepth = Math.max(0, quoteDepth - 1);
                // 右引号收尾的台词本身就是一个完整句，允许在此断开
                if (quoteDepth == 0 && isSentenceBoundary(line, i)) {
                    sentences.add(buf.toString());
                    buf.setLength(0);
                }
                continue;
            }
            if (quoteDepth == 0 && (c == '。' || c == '！' || c == '？')) {
                sentences.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            sentences.add(buf.toString());
        }
        sentences.removeIf(s -> s.isBlank());
        return sentences;
    }

    /** 右引号处是否为合法句界：其后不是另一个句末标点（如 {@code 。」} 已由标点分支处理） */
    private static boolean isSentenceBoundary(String line, int idx) {
        if (idx + 1 >= line.length()) {
            return true;
        }
        char next = line.charAt(idx + 1);
        return next != '。' && next != '！' && next != '？' && next != '」' && next != '』';
    }

    /** 把一条超长行按句切分，再聚合到 {@link #TARGET_PARAGRAPH_CHARS} 附近 */
    private static List<String> packSentences(String line) {
        List<String> sentences = splitSentences(line);
        List<String> paragraphs = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() > 0 && buf.length() + s.length() > TARGET_PARAGRAPH_CHARS) {
                paragraphs.add(buf.toString());
                buf.setLength(0);
            }
            buf.append(s);
            // 单句本身超长：以硬上沿强制断段，避免又退回巨块
            if (buf.length() >= MAX_PARAGRAPH_CHARS) {
                paragraphs.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            paragraphs.add(buf.toString());
        }
        return paragraphs;
    }

    private static int countNonBlankLines(String content) {
        int n = 0;
        for (String line : content.split("\n")) {
            if (!line.isBlank()) {
                n++;
            }
        }
        return n;
    }

    private static int longestLine(String content) {
        int max = 0;
        for (String line : content.split("\n")) {
            max = Math.max(max, line.trim().length());
        }
        return max;
    }
}
