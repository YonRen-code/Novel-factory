package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * **悬念档位推进规则**（2026-09-22）——把"主线有没有在原地打转"变成可机械判定的序比较。
 *
 * <p><b>为什么要它</b>：此前的计划约束是"每章至少一处关系/信息/资源的变化"，
 * 而"内心评价悄然变化"也能满足它。实测出现过整批 6 章主线零推进：每章都是
 * "发现线索 → 自我否定 → 回到原点"，账本忠实地把每章都记成"怀疑但未行动"的伏笔，
 * 而规划层甚至把它写成了要"确立"的**叙事范式**。文风劝导管不住这种现象，只有把悬念推进
 * 做成**有序枚举**并逐章比下标才拦得住。
 *
 * <p><b>与题材无关</b>：档位表由蓝图针对本书生成（{@code StageBlueprintEntity.suspenseLadder}），
 * 本类只做下标的比较，不含任何本书特有名词——换任何题材、任何悬念都成立。
 *
 * <p><b>唯一实现</b>：章计划校验（{@code ValidateChapterPlanNode}）与体检观测
 * （{@code BatchHealthService}）都走本类，避免"两份判据各自演化"（项目里已发生过几次）。
 *
 * <p><b>规则</b>：
 * <ol>
 *   <li>每章须声明档位，且能在档位表中**唯一定位**；</li>
 *   <li>档位下标**不得倒退**（已推进的认知不可退回——"自我否定圆回"正是倒退）；</li>
 *   <li>**不得连续 3 章停留在同一档**（过渡章也计入停留链，避免用 transition 洗掉停滞；
 *       但**段末恰好只有 1 章过渡**的停留段算"蓄势"予以豁免——见 {@link #transitionExempt}，
 *       连续 ≥2 章过渡收尾不豁免：那是停摆不是蓄势，实测第 14/15/16 章正是借此通道连占三章同档）。</li>
 * </ol>
 *
 * <p>⚠️ 为什么是"连续 3 章"而不是"相邻章必须不同"：档位表只有 3-6 档，若要求相邻章必然不同，
 * 6 章批次会因**档位耗尽**而无法满足——约束必须先可满足才有意义。
 */
public final class SuspenseLadderPolicy {

    /** 停留链长度达到该值即视为违规（连续 3 章同一档） */
    public static final int HOLD_LIMIT = 3;

    /**
     * 过渡章豁免是否成立：**仅当停留段末尾恰好只有 1 章过渡**时才算"蓄势"。
     *
     * <p>原规则是"以过渡章收尾即豁免"。实测第 14/15/16 章连续三章同为档位4
     * （校长测试→破格跳级），其中 ch15、ch16 都是过渡章——停留段以过渡章收尾，于是被整段豁免，
     * 这正是"连续三章讲同一件事"绕过校验的通道。
     *
     * <p>豁免的本意是"过渡章替下一章蓄势"，那只能是**单章**过渡；连续 ≥2 章过渡已不是蓄势而是停摆
     * （体检的过渡章占比也就此逼近上限）。故收紧为：段末连续过渡章数 ≠ 1 都不豁免——
     * 收尾是常规章（0 章过渡）本就不豁免，连续 2 章以上过渡同样不豁免。
     */
    private static boolean transitionExempt(Beat beat, int trailingTransitionRun) {
        return beat.transition() && trailingTransitionRun == 1;
    }

    private SuspenseLadderPolicy() {
    }

    /**
     * 一章的档位观测点。
     *
     * @param chapterNo 章号（仅用于报错定位）
     * @param rawBeat   章计划回填的档位原文（可能带"1."前缀或补了说明）
     * @param transition 是否过渡章
     */
    public record Beat(int chapterNo, String rawBeat, boolean transition) {
    }

    /** 最长停留段：length 为连续同档的章数，起止章号用于定位 */
    public record HoldRun(int length, int startChapterNo, int endChapterNo) {
        public boolean violated() {
            return length >= HOLD_LIMIT;
        }
    }

    /** 档位表可用（至少 2 档）——不足时所有校验与统计一律跳过，不得凭空判定 */
    public static boolean usable(List<String> ladder) {
        return ladder != null && ladder.size() >= 2;
    }

    /**
     * 校验档位推进，返回违规描述清单（空 = 通过或跳过）。
     * 违规文本会**回注给规划模型**做重规划，因此要能指出"第几章、错在哪"。
     */
    public static List<String> violations(List<Beat> beats, List<String> ladder) {
        List<String> issues = new ArrayList<>();
        if (beats == null || beats.isEmpty() || !usable(ladder)) {
            return issues;
        }
        Integer prevIndex = null;
        int run = 0;
        int trailingTransitionRun = 0;
        boolean runFlagged = false;
        for (Beat beat : beats) {
            if (beat == null) {
                trailingTransitionRun = 0;
                continue;
            }
            int index = resolveIndex(beat.rawBeat(), ladder);
            if (index < 0) {
                issues.add("第 " + beat.chapterNo() + " 章未声明 suspenseBeat，或所填内容不是档位表原文");
                // 缺声明不计入停留链，避免把格式问题放大成推进问题；同时打断"连续过渡"计数
                trailingTransitionRun = 0;
                continue;
            }
            trailingTransitionRun = beat.transition() ? trailingTransitionRun + 1 : 0;
            if (prevIndex == null) {
                run = 1;
            } else {
                if (index < prevIndex) {
                    issues.add("第 " + beat.chapterNo() + " 章**档位倒退**：" + brief(ladder.get(index))
                            + " ← 上一档 " + brief(ladder.get(prevIndex))
                            + "（「产生怀疑又被自我否定圆回」属于倒退，不是推进）");
                }
                boolean sameRung = index == prevIndex;
                run = sameRung ? run + 1 : 1;
                if (!sameRung) {
                    // 换档即换停留段，重新获得一次告警机会
                    runFlagged = false;
                }
            }
            // 同一停留段只报一次：回注给规划模型的信息要保持可读，重复告警只会稀释重点
            // 过渡章豁免已收紧为"段末恰好 1 章过渡"（见 transitionExempt）：连续 ≥2 章过渡照样报违规
            if (run >= HOLD_LIMIT && !transitionExempt(beat, trailingTransitionRun) && !runFlagged) {
                issues.add("第 " + beat.chapterNo() + " 章起**主线原地**：已连续 " + run
                        + " 章停留在同一档位「" + brief(ladder.get(index)) + "」，悬念没有推进"
                        + (beat.transition() ? "（且末尾连续 " + trailingTransitionRun
                        + " 章为过渡章，不是单章蓄势）" : ""));
                runFlagged = true;
            }
            prevIndex = index;
        }
        return issues;
    }

    /**
     * 最长停留段（连续同档且**未被过渡章豁免**的段，豁免口径见 {@link #transitionExempt}）——
     * 与 {@link #violations} 的判据一致，供体检统计"这批在原地停了多久"。无数据时返回 length=0。
     */
    public static HoldRun maxHold(List<Beat> beats, List<String> ladder) {
        HoldRun longest = new HoldRun(0, 0, 0);
        if (beats == null || beats.isEmpty() || !usable(ladder)) {
            return longest;
        }
        Integer prevIndex = null;
        int run = 0;
        int runStart = 0;
        int trailingTransitionRun = 0;
        for (Beat beat : beats) {
            if (beat == null) {
                trailingTransitionRun = 0;
                continue;
            }
            int index = resolveIndex(beat.rawBeat(), ladder);
            if (index < 0) {
                trailingTransitionRun = 0;
                continue;
            }
            trailingTransitionRun = beat.transition() ? trailingTransitionRun + 1 : 0;
            if (prevIndex != null && index == prevIndex) {
                run++;
            } else {
                run = 1;
                runStart = beat.chapterNo();
            }
            // 与 violations 同口径：单章过渡收尾的段不算停留；连续 ≥2 章过渡收尾照样计入
            if (!transitionExempt(beat, trailingTransitionRun) && run > longest.length()) {
                longest = new HoldRun(run, runStart, beat.chapterNo());
            }
            prevIndex = index;
        }
        return longest;
    }

    /**
     * 把章计划回填的档位解析成档位表下标；无法**唯一定位**时返回 -1。
     * 容忍模型常见的"1. 档位原文""档位原文（补一句解释）"，但出现歧义宁可判不通过——
     * 静默归错档会让校验失去意义。
     */
    public static int resolveIndex(String rawBeat, List<String> ladder) {
        if (StringUtils.isBlank(rawBeat) || ladder == null) {
            return -1;
        }
        String normalized = rawBeat.trim().replaceAll("^[0-9]+\\s*[.、)）:：]?\\s*", "");
        if (normalized.isEmpty()) {
            return -1;
        }
        // 精确 / 前缀命中优先
        for (int i = 0; i < ladder.size(); i++) {
            String entry = trimmed(ladder.get(i));
            if (!entry.isEmpty() && (normalized.equals(entry) || normalized.startsWith(entry))) {
                return i;
            }
        }
        // 退一步：唯一包含关系
        int hit = -1;
        for (int i = 0; i < ladder.size(); i++) {
            String entry = trimmed(ladder.get(i));
            if (entry.isEmpty()) {
                continue;
            }
            if (normalized.contains(entry) || entry.contains(normalized)) {
                if (hit >= 0) {
                    return -1;
                }
                hit = i;
            }
        }
        return hit;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    // ==================== 相邻章档位描述去重 ====================

    /**
     * **相邻章档位描述重复**校验：两章回填的 suspenseBeat **逐字相同**即违规。
     *
     * <p><b>为什么需要它</b>：{@link #violations} 只比较**档位下标**，判定"是否连续停在同一档"。
     * 实测第 11–15 章暴露出它的盲区——规划层把整段当一个档位，五章回填的 suspenseBeat
     * <b>逐字完全相同</b>（连"（第11-15章）"这个尾巴都一样），而下标比较只看到"连续 3 章同档"，
     * 报告一次后就因 {@code runFlagged} 去重而沉默。更致命的是：**重规划一次后输出仍然逐字相同**，
     * 因为旧反馈只说了"第 N 章起主线原地"，没有告诉模型"你写的是同一句话"。
     *
     * <p>本校验补上这个盲区，且用的是**最便宜、最不会误伤**的判据：逐字相等（归一化后）。
     * 模型只要换一处措辞就能通过——这正是我们要的：它必须真正区分每章讲的是什么。
     * 若日后发现模型靠无意义改写绕过，再升级到 n-gram 相似度（阈值 0.8~0.9）。
     *
     * <p>归一化只做三件事：去空白、去行首的"1."序号、去含"章"的括注（如"（第11-15章）"）——
     * 这三样都是格式噪声，不构成"描述不同"。**不做**同义词替换等语义归一，那是 LLM 审校的活。
     *
     * @return 违规描述清单（空 = 通过或跳过）
     */
    public static List<String> duplicateBeatViolations(List<Beat> beats) {
        List<String> issues = new ArrayList<>();
        if (beats == null || beats.size() < 2) {
            return issues;
        }
        String prev = null;
        int prevNo = 0;
        int run = 1;
        for (Beat beat : beats) {
            if (beat == null) {
                continue;
            }
            String current = normalizeForDuplicate(beat.rawBeat());
            if (current.isEmpty()) {
                prev = null;
                prevNo = 0;
                run = 1;
                continue;
            }
            if (current.equals(prev)) {
                run++;
                // 同一重复段只报一次：回注信息要可读，重复告警只会稀释重点
                if (run == 2) {
                    issues.add("第 " + prevNo + "–" + beat.chapterNo() + " 章的 suspenseBeat **逐字相同**"
                            + "（「" + brief(current) + "」）——这不是「推进档位」，而是把整段当成了一个档位。"
                            + "相邻两章必须写出**本章独有**的推进内容，不能复用同一句描述");
                }
            } else {
                prev = current;
                prevNo = beat.chapterNo();
                run = 1;
            }
        }
        return issues;
    }

    /** 去格式噪声后用于逐字比较：空白 / 行首序号 / 含"章"的括注都不算描述差异 */
    private static String normalizeForDuplicate(String rawBeat) {
        String text = trimmed(rawBeat);
        if (text.isEmpty()) {
            return "";
        }
        return text
                .replaceAll("[\\s\u3000]", "")
                .replaceAll("^[0-9]+\\s*[.、)）:：]?", "")
                .replaceAll("[（(][^）)]*章[）)]", "");
    }

    /** 档位条目是"可观察表述"，可能较长，违规信息里截断以便阅读 */
    private static String brief(String value) {
        String text = trimmed(value);
        return text.length() <= 28 ? text : text.substring(0, 28) + "…";
    }
}
