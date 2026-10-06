package cn.novel.yonren.domain.novel.service.armory.prompt;

import cn.novel.yonren.domain.novel.model.valobj.properties.PromptBudgetProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前缀总预算守门：把"多块拼装 + 各自封顶 + 合计无界"的输入段收敛到一条总量约束上。
 * 各块虽有独立封顶，但"封顶之和"会随块数增长击穿预算（正文前缀尤为明显：每轮迭代都在加新块）。
 *
 * <p><b>三个入口</b>：
 * <ul>
 *   <li>{@link #assembleChapterPrefix(int, List)}：正文前缀（块与渲染顺序由调用方给出）</li>
 *   <li>{@link #assemblePlanInput(int, List)}：章节计划输入段（预算取 {@code planPrefixChars}）</li>
 *   <li>{@link #assemble(String, int, int, List)}：通用入口（场景自定块与预算）</li>
 * </ul>
 *
 * <p><b>装配语义（各入口共用）</b>：
 * ① 渲染顺序 = 入参顺序（超预算只影响"谁被丢"，不影响"谁在前面"，未超预算时输出与逐块直拼逐字一致）；
 * ② 超预算时按 priority 淘汰：数值小者先保住，装不下的块跳过并继续看后续候选（贪心填满预算）——
 * 跳过低价值块优于截断高价值块；
 * ③ 可截断块仅在剩余预算够一段可读内容时按段落边界截断并标注，否则同样丢弃；
 * ④ 兜底：若全部被淘汰，强制保留最高优先块（截断至上限），前缀不得为空。
 *
 * <p><b>优先级刻度（1=最不可牺牲）</b>：
 * 1 末态红线 / 境界锁定；2 近章摘要 / 偏差警示 / 质量债；
 * 3 禁泄清单 / 卷方向锚 / 阶段蓝图 / 上章结尾；
 * 4 一致性索引 / 三账本 / 伏笔账 /
 *   <b>审校反馈 / 疲劳词红线 / 风格警示</b>（2026-10-01 由 5·5·6 上调）；
 * 5 久远唤醒（2026-10-01 由 4 下调）；7 文风指纹；8 情节模式黑名单。
 * 前情记忆不再作为单块参与（整块截尾会先牺牲"最近/纠错类"尾部），
 * 已拆为子块由 {@code ChapterMemoryService.buildMemoryBlocks} 给出。
 *
 * <p><b>为什么上调这三块（2026-10-01 实测）</b>：第 8/12/13 章连续出现
 * 「丢弃[疲劳词红线、审校反馈、风格警示、情节模式黑名单]」，而同一批里
 * 超长章与禁泄违例**连续三批**反复发作——写手看不到"上一章已被指出的问题"，
 * 等于每章从头再犯。代价对比很直接：这三块合计不足「久远唤醒」（1988 字）的一半，
 * 而后者只是背景补完，缺一两章不影响连贯。**行为指导块必须优先于背景补完块。**
 *
 * <p><b>不变式</b>：
 * ① 场景内 label 必须唯一——重复 label 会让同一块被渲染两次、预算扣减与实际输出脱节，违反即 fail-fast；
 * ② {@code truncatable=false} 的块（截断即静默丢事实，如禁泄清单/境界锁定）放不下时整块丢弃，弃则 WARN。
 *
 * <p><b>可观测</b>：每次装配一行 INFO（各块字数 / 占比 / 截断与丢弃清单），
 * 占比长期高位或出现 WARN 即为设计信号——回查是哪个块在膨胀，而不是直接调大上限
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptBudgetGuard {

    private final PromptBudgetProperties properties;

    /** 块间分隔符（与逐块直拼的旧格式一致） */
    private static final String SEPARATOR = "\n\n";
    /** 截断标记：明确告知模型本块是不完整摘录 */
    private static final String TRUNCATION_MARKER = "\n[本块已按前缀总预算截断]";
    /** 允许截断的最小剩余预算：低于此值截出来的残句无意义，不如整块丢弃 */
    private static final int MIN_TRUNCATED_BLOCK_CHARS = 200;
    /** 核心优先级阈值：priority &le; 此值的块被裁剪即视为预算已咬到核心约束，装配后升 WARN */
    private static final int CORE_PRIORITY = 3;
    /** 余量告警阈值：未裁剪但占比达到此值同样升 WARN（"余量假设"失效的早期信号） */
    private static final int NEAR_LIMIT_PERCENT = 90;

    /**
     * 正文前缀的固定块：label / 淘汰次序 / 是否可截断。
     * 前情记忆已拆为子块（见 {@code ChapterMemoryService#buildMemoryBlocks}），故不在此枚举；
     * 渲染顺序由调用方给出（此类不再约定顺序），枚举声明顺序仅表分组
     */
    public enum PrefixBlock {
        /** 账本末态红线：位置/持有物/修为的硬对齐锚点 */
        EDGE_STATE("末态红线", 1, true),
        /** 风格警示：跨章逐字重复句 + 疲劳词超频统计（2026-10-01 由 6 上调至 4） */
        STYLE_WARNING("风格警示", 4, true),
        /** 疲劳词前置预警：机械门禁词表与红线（写手不看会被打回修订）；2026-10-01 由 5 上调至 4 */
        FATIGUE_BLACKLIST("疲劳词红线", 4, true),
        /** 一致性索引：时间线/伤势/术语/关键数字 */
        CONSISTENCY("一致性索引", 4, true),
        /** 本章禁泄清单：伏笔谜底关键词（与逐字扫描同源，截断会静默丢词，故不可截断——放不下就整块弃） */
        SECRECY_GUARD("禁泄清单", 3, false),
        /** 文风指纹库：本书已建立的风格基准（正向激励） */
        STYLE_FINGERPRINT("文风指纹", 7, true),
        /** 已用情节模式黑名单：防重复套路 */
        USED_PATTERN_BLACKLIST("情节模式黑名单", 8, true),
        /** 上章审校反馈（P2）：语义 MINOR 压缩要点——审校报出的问题首次产生行动通道（2026-10-01 由 5 上调至 4） */
        REVIEW_FEEDBACK("审校反馈", 4, true);

        private final String label;
        private final int priority;
        private final boolean truncatable;

        PrefixBlock(String label, int priority, boolean truncatable) {
            this.label = label;
            this.priority = priority;
            this.truncatable = truncatable;
        }

        /** 构造装配块（内容空白时由装配器统一过滤，不占预算、不进日志） */
        public Block toBlock(String content) {
            return new Block(label, priority, truncatable, content);
        }
    }

    /**
     * 通用装配块：调用方直接给出规格（label 需在场景内唯一）与内容，不必把场景专属条目塞进 {@link PrefixBlock}
     *
     * @param label       块名（渲染与日志用，场景内必须唯一）
     * @param priority    淘汰次序（1=先保住）
     * @param truncatable 能否按段落边界截断
     * @param content     本轮内容；空白视为"无此块"
     */
    public record Block(String label, int priority, boolean truncatable, String content) {
    }

    /**
     * 正文前缀入口（2026-09-28）：块与渲染顺序全部由调用方给出——
     * 前情记忆拆为子块后，"正文前缀"不再是固定枚举集，而是"枚举块 + 记忆子块"的有序序列
     *
     * @param chapterNo 章号（仅用于装配日志归因）
     */
    public String assembleChapterPrefix(int chapterNo, List<Block> blocks) {
        return assemble("正文", chapterNo, properties.getTotalPrefixChars(), blocks);
    }

    /**
     * 章节计划输入段入口：预算取自 {@code planPrefixChars}，块与渲染顺序由调用方给出。
     * 规划 prompt 的固定要求与 schema 段不进本入口——那是规划契约，不参与裁剪
     */
    public String assemblePlanInput(int startNo, List<Block> blocks) {
        return assemble("章节计划输入段", startNo, properties.getPlanPrefixChars(), blocks);
    }

    /**
     * 通用装配入口：渲染顺序 = 入参顺序，淘汰次序 = priority；budget &le; 0 时退化为直拼（便于对比排查）。
     * scene / scopeNo 仅用于装配日志归因（如"正文"+"章号"、"章节计划输入段"+"段起点章号"）
     */
    public String assemble(String scene, int scopeNo, int budget, List<Block> blocks) {
        List<Block> present = presentBlocks(scene, blocks);
        if (present.isEmpty()) {
            return "";
        }
        if (budget <= 0) {
            Map<String, Block> all = indexByLabel(present);
            String result = render(present, all);
            log.info("前缀预算[{}] 第{}章 合计{}字（块{}字+分隔{}字，总额约束未启用，逐块直拼）= [{}]",
                    scene, scopeNo, result.length(), contentChars(all), separatorChars(result, all),
                    describe(present, all));
            return result;
        }

        List<Block> admitted = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        List<Block> truncatedBlocks = new ArrayList<>();
        List<Block> skipped = new ArrayList<>();
        int remaining = budget;

        List<Block> byPriority = new ArrayList<>(present);
        byPriority.sort(Comparator.comparingInt(Block::priority));
        for (Block block : byPriority) {
            int separator = admitted.isEmpty() ? 0 : SEPARATOR.length();
            if (block.content().length() + separator <= remaining) {
                admitted.add(block);
                remaining -= block.content().length() + separator;
                continue;
            }
            int room = remaining - separator;
            if (block.truncatable() && room >= MIN_TRUNCATED_BLOCK_CHARS) {
                admitted.add(new Block(block.label(), block.priority(), true, truncate(block.content(), room)));
                truncated.add(block.label());
                truncatedBlocks.add(block);
                remaining = 0;
                continue;
            }
            skipped.add(block);
        }
        if (admitted.isEmpty()) {
            // 极端配置（上限小于最小可读块）：强制保留最高优先块，输入段不得为空
            Block top = byPriority.get(0);
            admitted.add(new Block(top.label(), top.priority(), top.truncatable(),
                    truncate(top.content(), budget)));
            truncated.add(top.label());
            truncatedBlocks.add(top);
            skipped.removeIf(block -> block.label().equals(top.label()));
        }

        Map<String, Block> admittedByLabel = indexByLabel(admitted);
        String result = render(present, admittedByLabel);
        int usedPercent = (int) Math.round(result.length() * 100.0 / budget);
        // 三个数必须自洽：合计 = 块内容字 + 分隔字，且后面的逐块清单之和恰等于"块内容字"——
        // 否则读日志的人（或解析脚本）拿分块数字去对账会永远差 2×(块数-1)，无法判断是不是出了别的问题
        log.info("前缀预算[{}] 第{}章 合计{}字（块{}字+分隔{}字）/上限{}字({}%) = [{}]{}",
                scene, scopeNo, result.length(), contentChars(admittedByLabel),
                separatorChars(result, admittedByLabel), budget, usedPercent,
                describe(present, admittedByLabel), notice(truncated, skipped));
        alertIfBudgetBites(scene, scopeNo, truncatedBlocks, skipped, usedPercent);
        return result;
    }

    /**
     * 入参过滤：非空内容才算"有此块"；label 在场景内重复即 fail-fast——
     * 重复 label 会让同一块被渲染两次、预算扣减与实际输出脱节（不变式被静默击穿）
     */
    private List<Block> presentBlocks(String scene, List<Block> blocks) {
        List<Block> present = new ArrayList<>();
        if (blocks == null) {
            return present;
        }
        Set<String> labels = new LinkedHashSet<>();
        for (Block block : blocks) {
            if (block == null) {
                continue;
            }
            if (!labels.add(block.label())) {
                throw new IllegalArgumentException("前缀装配块 label 重复：" + block.label()
                        + "（场景 " + scene + "）——重复 label 会使同一块被渲染两次并击穿预算不变式，"
                        + "请保证场景内 label 唯一");
            }
            if (StringUtils.isNotBlank(block.content())) {
                present.add(block);
            }
        }
        return present;
    }

    /**
     * 预算告警三档，**必须与同一行的"截断/丢弃"清单口径一致**：
     * <ol>
     *   <li>核心约束被咬：不可截断块被整块丢弃，或 priority &le; {@link #CORE_PRIORITY} 的块被裁剪——最高级；</li>
     *   <li>已裁剪但只牺牲了低价值块：**此前会错误地落进第 3 档**，导致同一行明写"截断[..]；丢弃[..]"、
     *       告警却说"未触发裁剪"，读日志的人只能怀疑两边有一边是错的。判据与"是否真的发生裁剪"无关，
     *       只看有没有波及核心块——这是两个维度，必须分开报；</li>
     *   <li>无任何裁剪、仅临近上限：{@link #NEAR_LIMIT_PERCENT}，是"余量假设失效"的早期信号。</li>
     * </ol>
     */
    private void alertIfBudgetBites(String scene, int scopeNo, List<Block> truncated, List<Block> skipped,
                                    int usedPercent) {
        List<Block> sacrificed = new ArrayList<>(truncated);
        sacrificed.addAll(skipped);
        List<String> hardDropped = skipped.stream()
                .filter(block -> !block.truncatable())
                .map(Block::label)
                .distinct()
                .toList();
        List<String> coreSacrificed = sacrificed.stream()
                .filter(block -> block.priority() <= CORE_PRIORITY)
                .map(Block::label)
                .distinct()
                .toList();
        if (!hardDropped.isEmpty() || !coreSacrificed.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            if (!hardDropped.isEmpty()) {
                detail.append("不可截断块被整块丢弃").append(hardDropped).append("（该约束本次未进入输入，依赖下游兜底）");
            }
            if (!coreSacrificed.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("核心块(≤").append(CORE_PRIORITY).append(")被裁剪").append(coreSacrificed);
            }
            log.warn("前缀预算[{}] 第{}章: 预算已触及核心约束——{}。设计信号：回查块的膨胀来源，而非直接调大上限",
                    scene, scopeNo, detail);
            return;
        }
        List<String> truncatedLow = truncated.stream().map(Block::label).distinct().toList();
        List<String> droppedLow = skipped.stream().map(Block::label).distinct().toList();
        if (!truncatedLow.isEmpty() || !droppedLow.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            if (!truncatedLow.isEmpty()) {
                detail.append("截断").append(truncatedLow);
            }
            if (!droppedLow.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("丢弃").append(droppedLow);
            }
            log.warn("前缀预算[{}] 第{}章: 占比{}%，已按优先级裁剪低价值块——{}。"
                            + "若这些块其实在起作用，应上调其 priority，而不是调大上限",
                    scene, scopeNo, usedPercent, detail);
            return;
        }
        if (usedPercent >= NEAR_LIMIT_PERCENT) {
            log.warn("前缀预算[{}] 第{}章: 占比已达{}%（未发生任何裁剪，仅临近上限）——"
                            + "上限的余量假设需按各块字数重新定标",
                    scene, scopeNo, usedPercent);
        }
    }

    /** 按渲染顺序（present 顺序）拼接已入选块，保证"超预算只影响谁被丢，不影响谁在前面" */
    private String render(List<Block> present, Map<String, Block> admittedByLabel) {
        StringBuilder sb = new StringBuilder();
        for (Block block : present) {
            Block hit = admittedByLabel.get(block.label());
            if (hit == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(hit.content());
        }
        return sb.toString();
    }

    private Map<String, Block> indexByLabel(List<Block> blocks) {
        Map<String, Block> byLabel = new LinkedHashMap<>();
        for (Block block : blocks) {
            byLabel.put(block.label(), block);
        }
        return byLabel;
    }

    /**
     * 按剩余预算截断：优先段落边界，其次行边界，兜底硬截；裁不动就截到只留标记。
     * 返回长度保证 &le; budget（截断标记也计入预算）
     */
    private String truncate(String content, int budget) {
        if (content.length() <= budget) {
            return content;
        }
        if (budget <= TRUNCATION_MARKER.length()) {
            return content.substring(0, Math.max(0, budget));
        }
        int limit = budget - TRUNCATION_MARKER.length();
        int cut = content.lastIndexOf("\n\n", limit);
        if (cut < limit / 2) {
            cut = content.lastIndexOf('\n', limit);
        }
        if (cut < limit / 2) {
            cut = limit;
        }
        return content.substring(0, Math.max(0, cut)).trim() + TRUNCATION_MARKER;
    }

    /** 各入选块字数（按渲染顺序），供日志定位膨胀来源 */
    private String describe(List<Block> present, Map<String, Block> admittedByLabel) {
        StringBuilder sb = new StringBuilder();
        for (Block block : present) {
            Block hit = admittedByLabel.get(block.label());
            if (hit == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("｜");
            }
            sb.append(block.label()).append(hit.content().length());
        }
        return sb.toString();
    }

    /** 入选块的内容字数之和：与 {@link #describe} 的逐块清单同口径（两者相加必须相等） */
    private int contentChars(Map<String, Block> admittedByLabel) {
        int sum = 0;
        for (Block block : admittedByLabel.values()) {
            sum += block.content().length();
        }
        return sum;
    }

    /** 分隔符占用 = 渲染总长 − 块内容字之和（块数−1 个分隔符，恒为非负） */
    private int separatorChars(String rendered, Map<String, Block> admittedByLabel) {
        return rendered.length() - contentChars(admittedByLabel);
    }

    private String notice(List<String> truncated, List<Block> skipped) {
        StringBuilder sb = new StringBuilder();
        if (!truncated.isEmpty()) {
            sb.append("；截断[").append(String.join("、", truncated)).append("]");
        }
        if (!skipped.isEmpty()) {
            sb.append("；丢弃[").append(String.join("、",
                    skipped.stream().map(Block::label).toList())).append("]");
        }
        return sb.toString();
    }

}