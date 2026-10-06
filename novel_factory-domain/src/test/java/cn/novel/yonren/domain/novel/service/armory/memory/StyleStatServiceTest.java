package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风格统计测试：跨章逐字句查重、疲劳词累加与超频、警示渲染与上限
 */
class StyleStatServiceTest {

    private StyleStatService service;
    private StyleStatEntity stat;

    @BeforeEach
    void setUp() {
        service = new StyleStatService();
        stat = service.empty();
    }

    @Test
    void merge_collectsLongSentencesAndSkipsShort() {
        service.merge(stat, "林尘在夜色中悄然潜入后山废矿深处。短句。他握紧了怀中那面沉默的古镜。");
        // 句末标点被切分符剥掉，句集条目不含标点；短句（2 字）不参与
        System.out.println("切分结果: " + stat.getUsedSentences());
        assertEquals(2, stat.getUsedSentences().size());
        assertTrue(stat.getUsedSentences().contains("林尘在夜色中悄然潜入后山废矿深处"));
    }

    @Test
    void merge_detectsCrossChapterRepeatedSentence() {
        String sentence = "林尘在夜色中悄然潜入后山废矿深处，脚步轻得像一片落叶";
        service.merge(stat, sentence + "。本章其余内容。");
        service.merge(stat, "另一章的内容。" + sentence + "。");

        assertEquals(1, stat.getRepeatedSentences().size());
        assertEquals(sentence, stat.getRepeatedSentences().get(0));
    }

    @Test
    void merge_detectsNearDuplicateSentenceNotJustExactMatch() {
        // 实测病症（2026-09-29）：第 9/10 章结尾仅差一个"他"字——
        // 「枕头底下，那根烟硌着后脑勺，像一根小小的骨头」vs「…硌着他的后脑勺…」。
        // 精确匹配下跨章重复统计为 0 条，等于完全失明；本用例守住近重复判据。
        service.merge(stat, "枕头底下，那根烟硌着后脑勺，像一根小小的骨头。");
        service.merge(stat, "枕头底下，那根烟硌着他的后脑勺，像一根小小的骨头。");

        assertTrue(stat.getRepeatedSentences().stream().anyMatch(s -> s.contains("那根烟硌着")),
                "仅改一个字的近重复必须被捕获：" + stat.getRepeatedSentences());
    }

    @Test
    void merge_doesNotFlagUnrelatedSentences() {
        // 阈值取偏保守：宁可漏报，也不要把正常文体误标成重复
        service.merge(stat, "陆建国把自行车推到弄堂口，链条上沾满了泥。");
        service.merge(stat, "林婉端着搪瓷盆从灶间出来，盆里是刚晾好的绿豆汤。");

        assertTrue(stat.getRepeatedSentences().isEmpty(),
                "内容无关的句子不得被误判为重复：" + stat.getRepeatedSentences());
    }

    @Test
    void merge_accumulatesFatigueWordCounts() {
        service.merge(stat, "一丝寒意爬上脊背。仿佛 whole world 静了。一缕血腥味弥漫。");
        service.merge(stat, "又一丝寒意。再次仿佛停滞。");

        assertEquals(2, stat.getFatigueWords().get("一丝"));
        assertEquals(2, stat.getFatigueWords().get("仿佛"));
        assertEquals(1, stat.getFatigueWords().get("一缕"));
    }

    @Test
    void renderWarning_listsRepeatedSentencesAndOverusedWords() {
        String sentence = "他咽下喉头的腥甜，指节因为用力而发白，眼神一点点冷了下去";
        service.merge(stat, sentence + "。");
        // 同一句再出现一次进重复名单；疲劳词灌到阈值以上
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD; i++) {
            service.merge(stat, "一丝寒意。" + i);
        }
        service.merge(stat, sentence + "。再次出现。");

        String warning = service.renderWarning(stat);

        assertTrue(warning.contains("风格警示"));
        // 措辞随近重复判据一并调整（2026-09-29）：不再只针对"逐字"，包含"高度相似"的句子
        assertTrue(warning.contains("高度相似"), "警示应覆盖近重复而不只是逐字重复：" + warning);
        assertTrue(warning.contains(sentence));
        assertTrue(warning.contains("疲劳词超频"));
        assertTrue(warning.contains("一丝(" + StyleStatService.FATIGUE_THRESHOLD + "次)"));
    }

    @Test
    void renderWarning_emptyWhenNoIssues() {
        service.merge(stat, "正常的一段文字，没有重复也没有超频词。");
        assertEquals("", service.renderWarning(stat));
        assertEquals("", service.renderWarning(null));
    }

    @Test
    void renderContentFatigueBlacklist_listsFullCatalogAndNearLimitWords() {
        // 逼近阈值（阈值-1）的词应被点名
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD - 1; i++) {
            service.merge(stat, "一丝寒意。" + i);
        }

        String blacklist = service.renderContentFatigueBlacklist(stat);

        assertTrue(blacklist.contains("疲劳词预警名单"));
        // 全量词表在场（门禁词表三类各抽一个）
        assertTrue(blacklist.contains("不禁"));
        assertTrue(blacklist.contains("眼神复杂"));
        assertTrue(blacklist.contains("喉结滚动"));
        // 门禁红线文案与 StyleViolationPolicy 判据一致
        assertTrue(blacklist.contains("3 次/千字"));
        assertTrue(blacklist.contains("已逼近阈值的词"));
        assertTrue(blacklist.contains("一丝（全书已累计" + (StyleStatService.FATIGUE_THRESHOLD - 1) + "次）"));
    }

    @Test
    void renderContentFatigueBlacklist_noNearLimitOnFreshStat() {
        // 新故事/空账本：只有全量词表，无逼近点名；null 账本同样不炸
        String fresh = service.renderContentFatigueBlacklist(stat);
        assertTrue(fresh.contains("疲劳词预警名单"));
        assertFalse(fresh.contains("已逼近阈值的词"));
        assertFalse(service.renderContentFatigueBlacklist(null).contains("已逼近阈值的词"));
    }

    @Test
    void renderReviseFatigueBlacklist_keepsNearLimitCallout() {
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD - 1; i++) {
            service.merge(stat, "一丝寒意。" + i);
        }

        String blacklist = service.renderReviseFatigueBlacklist(stat);

        assertTrue(blacklist.contains("疲劳词禁新增名单"));
        assertTrue(blacklist.contains("已逼近阈值的词"));
        assertTrue(blacklist.contains("一丝（全书已累计" + (StyleStatService.FATIGUE_THRESHOLD - 1) + "次）"));
    }

    @Test
    void merge_tracksStockMicroActionFatigueWords() {
        for (int i = 0; i < StyleStatService.FATIGUE_THRESHOLD; i++) {
            service.merge(stat, "他喉结滚动，目光沉了下去。");
        }

        String warning = service.renderWarning(stat);

        assertTrue(warning.contains("疲劳词超频"));
        assertTrue(warning.contains("喉结滚动(" + StyleStatService.FATIGUE_THRESHOLD + "次)"));
    }

    @Test
    void merge_capsUsedSentencesToRollingWindow() {
        // 用**互不相似**的句子验证滚动窗口本身：近重复判据（2026-09-29）会把"只差一两个字的"
        // 模板式句子判为重复、不再进"已见句集"，所以不能用同模板批量生成的句子做本用例的 fixture。
        // 下面三组短句两两不同，任意两句至多共享一组（约占 1/3 篇幅），远低于近重复阈值。
        String first = sentence(0);
        for (int i = 0; i < StyleStatService.MAX_USED_SENTENCES + 10; i++) {
            service.merge(stat, sentence(i));
        }

        assertEquals(StyleStatService.MAX_USED_SENTENCES, stat.getUsedSentences().size(),
                "已见句集必须被滚动窗口封顶，否则无界增长");
        assertTrue(!stat.getUsedSentences().contains(first), "最老的句子应被挤出");
    }

    /** 由三组互不相同的短句拼成的长句；索引组合覆盖 5×5×5=125 种，足够 90 句互不重复 */
    private static String sentence(int i) {
        String[] subjects = {
                "陆建国推着那辆永久牌自行车", "林婉端着搪瓷盆从灶间出来", "陆瑾瑜蹲在弄堂口的青石板上",
                "王阿婆拎着煤球篮子走过去", "沈清欢把碎花布罩在电视机上"};
        String[] actions = {
                "说要赶在晌午前把账对完", "顺手把窗台上的灰抹了一遍", "听见远处传来一阵自行车的铃响",
                "把口袋里的零钱数了两遍", "抬头看了看天边压过来的乌云"};
        String[] tails = {
                "心里盘算着这个月还差多少", "嘴上应着手上却没停", "打算等雨小些再去铺子里",
                "想着明早还得去一趟供销社", "把东西归整齐了才坐下"};
        return subjects[i % subjects.length]
                + actions[(i / subjects.length) % actions.length]
                + tails[(i / (subjects.length * actions.length)) % tails.length] + "。";
    }

    @Test
    void merge_nullSafe() {
        service.merge(stat, null);
        service.merge(null, "内容");
        assertEquals(0, stat.getUsedSentences().size());
    }

}
