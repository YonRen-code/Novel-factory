package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;


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
