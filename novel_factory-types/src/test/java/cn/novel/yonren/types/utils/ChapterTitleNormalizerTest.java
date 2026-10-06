package cn.novel.yonren.types.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 章节标题归一化测试：剥除模型自带的"第N章"引导前缀；保留剧情性标题；空值安全
 */
class ChapterTitleNormalizerTest {

    @Test
    void normalize_stripsLeadingChapterPrefix() {
        assertEquals("疼痛是唯一的真实", ChapterTitleNormalizer.normalize("第4章 疼痛是唯一的真实"));
        assertEquals("疼痛是唯一的真实", ChapterTitleNormalizer.normalize("第4章：疼痛是唯一的真实"));
        assertEquals("疼痛是唯一的真实", ChapterTitleNormalizer.normalize("第14章、疼痛是唯一的真实"));
        // 无分隔符不剥：与"第3章的约定"这类剧情性标题无法机械区分，宁可保留前缀
        assertEquals("第4章疼痛是唯一的真实", ChapterTitleNormalizer.normalize("第4章疼痛是唯一的真实"));
    }

    @Test
    void normalize_keepsPlotStyleTitles_withoutSeparator() {
        // "第3章的约定"中"第3章"是标题本身的一部分（后跟文字而非分隔符），不剥
        assertEquals("第3章的约定", ChapterTitleNormalizer.normalize("第3章的约定"));
        // 无前缀标题原样返回
        assertEquals("疼痛是唯一的真实", ChapterTitleNormalizer.normalize("疼痛是唯一的真实"));
    }

    @Test
    void normalize_barePrefixTitle_keptAsIs() {
        // 标题只有"第4章"时剥完为空，保留原样（宁重复不空标题）
        assertEquals("第4章", ChapterTitleNormalizer.normalize("第4章"));
    }

    @Test
    void normalize_nullAndBlank_safe() {
        assertEquals(null, ChapterTitleNormalizer.normalize(null));
        assertEquals("  ", ChapterTitleNormalizer.normalize("  "));
        assertEquals("", ChapterTitleNormalizer.normalize(""));
    }

    @Test
    void stripDuplicatePrefix_onlyStripsMatchingChapterNo() {
        // 存量文件兼容：只剥与解析章节号重复的前缀
        assertEquals("疼痛是唯一的真实",
                ChapterTitleNormalizer.stripDuplicatePrefix("第4章 疼痛是唯一的真实", 4));
        // 章节号不匹配时不剥（防误伤：如第 4 章文件装着标题带"第12章"引用的内容）
        assertEquals("第12章我醒来时", ChapterTitleNormalizer.stripDuplicatePrefix("第12章我醒来时", 4));
        // 无前缀原样返回
        assertEquals("疼痛是唯一的真实", ChapterTitleNormalizer.stripDuplicatePrefix("疼痛是唯一的真实", 4));
    }
}
