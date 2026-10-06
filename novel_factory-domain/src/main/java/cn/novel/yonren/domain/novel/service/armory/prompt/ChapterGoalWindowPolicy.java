package cn.novel.yonren.domain.novel.service.armory.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节带目标窗口化（2026-10-03）。
 *
 * <p><b>为什么需要它</b>：story-bible 的「章节目标」把全书数百章的剧情带一次性写成一段 4400+ 字文本，
 * 随每次 prompt 注入（计划/正文的【故事设定】块）。实测正文前缀长期 100% 饱和，每章都要丢弃
 * 【审校反馈】等纠错块——而其中九成以上的章节带与本段剧情毫无关系。窗口化把无关的远章带裁掉，
 * 换取纠错类块重新进得来。
 *
 * <p><b>安全性</b>：只按章号窗口裁剪，不改写任何带内文字；解析不出结构、或一条带都没命中时，
 * 一律 fail-soft 返回原文（宁可多注入，不可丢信息）。裁剪后附加一行说明，避免模型误以为全书只有这些带。
 */
public final class ChapterGoalWindowPolicy {

    /** 窗口回看章数：留一点余量给"上一段的目标"作衔接参照 */
    static final int LOOKBACK = 3;
    /** 窗口前瞻章数：约一个标准阶段窗长，够规划层看到本段及下一段方向 */
    static final int LOOKAHEAD = 40;

    /** 形如「1-10章」「(1-90章)」的章号区间 */
    private static final Pattern RANGE = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s*章");
    /** 卷的分隔符（story-bible 章节目标的卷间分隔） */
    private static final String VOLUME_SEPARATOR = "\\|\\|";
    /** 带的分隔符 */
    private static final String BAND_SEPARATOR = "；";

    private static final String WINDOW_NOTE = "（章节目标已按当前进度窗口化，仅保留与本阶段相关的章节带；其余章节带按原设定继续推进）";

    private ChapterGoalWindowPolicy() {
    }

    /**
     * 按当前章号裁剪章节带目标。
     *
     * @param chapterGoal      原始章节目标全文，可为 null/空
     * @param currentChapterNo 下一待写章号（&lt;=0 时不裁剪）
     * @return 裁剪后的文本；无法解析或裁不出内容时返回原文
     */
    public static String window(String chapterGoal, int currentChapterNo) {
        if (chapterGoal == null || chapterGoal.isBlank() || currentChapterNo <= 0) {
            return chapterGoal;
        }
        int lo = Math.max(1, currentChapterNo - LOOKBACK);
        int hi = currentChapterNo + LOOKAHEAD;

        List<String> keptVolumes = new ArrayList<>();
        boolean anyBand = false;
        for (String volume : chapterGoal.split(VOLUME_SEPARATOR)) {
            if (volume == null || volume.isBlank()) {
                continue;
            }
            Matcher volumeRange = RANGE.matcher(volume);
            if (!volumeRange.find()) {
                // 结构不认识：整体不裁剪（fail-soft）
                return chapterGoal;
            }
            int volumeStart = parseInt(volumeRange.group(1));
            int volumeEnd = parseInt(volumeRange.group(2));
            int colon = volume.indexOf('：', volumeRange.end());
            if (colon < 0) {
                return chapterGoal;
            }
            if (volumeEnd < lo || volumeStart > hi) {
                continue;
            }
            String header = volume.substring(0, colon + 1).strip();
            List<String> keptBands = new ArrayList<>();
            for (String band : volume.substring(colon + 1).split(BAND_SEPARATOR)) {
                if (band == null || band.isBlank()) {
                    continue;
                }
                Matcher bandRange = RANGE.matcher(band);
                if (bandRange.find()) {
                    int bandStart = parseInt(bandRange.group(1));
                    int bandEnd = parseInt(bandRange.group(2));
                    if (bandEnd < lo || bandStart > hi) {
                        continue;
                    }
                }
                // 无章号区间的残段保守保留——删掉它比留着更可能丢信息
                keptBands.add(band.strip());
            }
            if (keptBands.isEmpty()) {
                continue;
            }
            anyBand = true;
            keptVolumes.add(header + String.join(BAND_SEPARATOR, keptBands));
        }
        if (!anyBand) {
            return chapterGoal;
        }
        return String.join("\n", keptVolumes) + "\n" + WINDOW_NOTE;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
