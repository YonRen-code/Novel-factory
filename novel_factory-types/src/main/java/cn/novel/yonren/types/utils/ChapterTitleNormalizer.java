package cn.novel.yonren.types.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节标题归一化：模型输出的标题常自带"第N章"前缀，而仓储落盘与前端展示都会再拼一次章节号，
 * 造成"第 4 章 第4章 xxx"式重复。两处使用：
 * 1. 入口归一化（正文/修订/候选解析后）——{@link #normalize}：剥除任意"第N章"引导前缀；
 * 2. 存量兼容（readChapter 解析旧文件后）——{@link #stripDuplicatePrefix}：仅当标题以
 *    "与解析章节号相同的第N章"开头时剥除，避免误伤"第3章的约定"这类剧情性标题。
 */
public final class ChapterTitleNormalizer {

    /** "第N章"引导前缀：后必须跟分隔符（空格/冒号/顿号等）或到串尾才剥——无分隔符的"第3章的约定"是剧情性标题，不剥 */
    private static final Pattern LEADING_CHAPTER_PREFIX =
            Pattern.compile("^第\\d+章(?:[\\s:：、．.·\\-—]+|$)(.+)$");

    /** 与指定章节号重复的前缀："第{n}章"后跟分隔符或到串尾 */
    private static Pattern duplicatePrefixOf(int chapterNo) {
        return Pattern.compile("^第" + chapterNo + "章(?:[\\s:：、．.·\\-—]+|$)(.+)$");
    }

    /** 剥除标题开头的"第N章"引导前缀；剥除后为空或无匹配时原样返回 */
    public static String normalize(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }
        Matcher matcher = LEADING_CHAPTER_PREFIX.matcher(title.trim());
        if (!matcher.matches()) {
            return title.trim();
        }
        String stripped = matcher.group(1).trim();
        return stripped.isEmpty() ? title.trim() : stripped;
    }

    /** 仅剥除与章节号重复的"第{chapterNo}章"前缀（存量文件读取兼容，防误伤剧情性标题） */
    public static String stripDuplicatePrefix(String title, int chapterNo) {
        if (title == null || title.isBlank()) {
            return title;
        }
        Matcher matcher = duplicatePrefixOf(chapterNo).matcher(title.trim());
        if (!matcher.matches()) {
            return title.trim();
        }
        String stripped = matcher.group(1).trim();
        return stripped.isEmpty() ? title.trim() : stripped;
    }

    private ChapterTitleNormalizer() {
    }
}
