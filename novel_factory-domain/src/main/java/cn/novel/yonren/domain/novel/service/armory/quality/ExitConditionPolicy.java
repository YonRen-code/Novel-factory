package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 阶段退出条件的**原子化**判据：把复合谓词拆成可独立核验的分句。
 *
 * <p><b>为什么需要</b>（162 章实测，96 条退出条件）：
 * <ul>
 *   <li><b>75/96（78%）是复合谓词</b>——形如「文本中出现 X 的描写，<em>且</em>伴随 Y」。
 *       核验按整条判定 ⇒ 只要一个分句没落地，整条即"未达成"，<em>部分推进完全不可见</em>，
 *       于是同一条条件被反复结转，形成"单调不可达"。</li>
 *   <li>更糟的是核验结果只给"未达成"三个字，下游不知道该补哪个分句，
 *       模型只能把整条<em>重新表述并加长</em>（实测：第1阶段「敌宗老祖死亡的客观结果」
 *       → 第3阶段变成含 3 个分句的长句），信息只增不减。</li>
 * </ul>
 * 拆成原子后：核验逐分句给证据，「差哪个分句」精确可见，且分句粒度可判定"部分达成"。
 *
 * <p><b>切分口径</b>：只按<em>并列连接词</em>切（{@code 并且 / 以及 / 同时 / 且 / ；;}），
 * <b>不切顿号 {@code 、}</b>——顿号多用于同一分句内的列举（如「尸体、神魂消散、生机断绝」），
 * 切开会把一个语义单元碎成互不可核验的碎片。也不切裸 {@code 并}（会误伤「合并」「并列」）。
 * 单字符 {@code 且}放在备选最末，保证 {@code 并且}优先命中。
 */
public final class ExitConditionPolicy {

    /** 单条件原子数上限：超出时尾部合并，防长句切爆导致核验 prompt 与输出膨胀 */
    public static final int MAX_ATOMS_PER_CONDITION = 6;

    /** 并列连接词（长词在前，保证 并且 先于 且 命中） */
    private static final Pattern CONNECTOR = Pattern.compile("并且|以及|同时|且|；|;");

    /**
     * 分句首尾需剥掉的标点与空白。
     * 注意必须显式列出全角空格 {@code \u3000}——Java 默认正则的 {@code \s} 不覆盖它，
     * 模型输出里相当常见（实测"　出现A"这类前导全角空格会原样残留进核验 prompt）。
     */
    private static final Pattern EDGE_PUNCT =
            Pattern.compile("^[，,。.、；;：:！!？?\\s\\u3000]+|[，,。.、；;：:！!？?\\s\\u3000]+$");

    private ExitConditionPolicy() {
    }

    /**
     * 把一条退出条件拆成原子子句。
     * <p>单原子条件原样返回（保持与拆分前完全一致的判定粒度，
     * 使既有单句条件的行为零变化）；拆不出有效原子的同样原样返回，绝不返回空表。
     */
    public static List<String> splitAtoms(String condition) {
        if (StringUtils.isBlank(condition)) {
            return List.of();
        }
        String trimmed = condition.trim();
        String[] parts = CONNECTOR.split(trimmed);
        List<String> atoms = new ArrayList<>();
        for (String part : parts) {
            String atom = EDGE_PUNCT.matcher(part).replaceAll("");
            if (!atom.isEmpty()) {
                atoms.add(atom);
            }
        }
        if (atoms.size() <= 1) {
            return List.of(trimmed);
        }
        if (atoms.size() > MAX_ATOMS_PER_CONDITION) {
            return mergeTail(atoms);
        }
        return atoms;
    }

    /** 原子是否为"拆分产生"（用于判断是否需要给模型标注所属条件） */
    public static boolean isCompound(String condition) {
        return splitAtoms(condition).size() > 1;
    }

    /** 尾部合并：保留前 MAX-1 个原子，其余用「且」拼回一条（安全阀，极少数超长条件才会走到） */
    private static List<String> mergeTail(List<String> atoms) {
        List<String> merged = new ArrayList<>(atoms.subList(0, MAX_ATOMS_PER_CONDITION - 1));
        merged.add(String.join("且", atoms.subList(MAX_ATOMS_PER_CONDITION - 1, atoms.size())));
        return merged;
    }

    /** 渲染未达成原子清单（供核验注记："还差哪些分句"） */
    public static String renderAtoms(List<String> atoms) {
        if (atoms == null || atoms.isEmpty()) {
            return "";
        }
        return String.join("；", atoms);
    }

    /** 对比/并列形态：条件在描述两类表现之间的落差 */
    private static final Pattern CONTRAST =
            Pattern.compile("对比|反差|相反|两种.{0,6}(模式|状态|表现|形象|行为)");

    /** 二选一形态：用「或 / 任一项」把两个场景并列成一条 */
    private static final Pattern ALTERNATIVE =
            Pattern.compile("或|任一项|至少一[项种]");

    /** 跨章演化形态：状态迁移，起止两端往往落在不同章 */
    private static final Pattern EVOLUTION =
            Pattern.compile("从.{1,16}(转变为|变为|转为|演变为|发展到)");

    /**
     * 条件是否属「多点取证」形态；命中则返回形态名，否则返回 null。
     *
     * <p><b>为什么需要</b>：核验侧只接受「单个 chapterNo + 该章内一段连续原文」作证据，
     * 而这类条件的证据天然分散在多章/多场景——**结构性无法通过**，与写作质量无关。
     * 实测（100 条核验样本的失败项）全部落在这些形态上，而单章单事件型条件达成率正常。
     *
     * <p><b>只在条件已经判为未达成时用于归因</b>（不在判定路径上），所以判据宁可宽一点：
     * 「或」在条件文本里多为二选一，但也可能只是同一动作的两种描述（如"投入或推入阵眼"，
     * 这种证据仍集中在一处、能通过）。因此本方法**不得**被用作扣分或改写依据，
     * 只在核验注记里补一句"疑似结构问题，建议拆成单要素条件"供人工判断。
     * 治本手段在规划侧：见 {@code RollingOutlineService} 阶段蓝图 prompt 的 6.1 条。
     */
    public static String multiPointForm(String condition) {
        if (StringUtils.isBlank(condition)) {
            return null;
        }
        if (CONTRAST.matcher(condition).find()) {
            return "对比/并列型";
        }
        if (EVOLUTION.matcher(condition).find()) {
            return "跨章演化型";
        }
        if (ALTERNATIVE.matcher(condition).find()) {
            return "二选一型";
        }
        return null;
    }
}
