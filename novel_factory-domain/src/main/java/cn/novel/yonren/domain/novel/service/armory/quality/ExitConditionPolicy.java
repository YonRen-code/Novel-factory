package cn.novel.yonren.domain.novel.service.armory.quality;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ExitConditionPolicy {

    /** 单条件原子数上限：超出时尾部合并，防长句切爆导致核验 prompt 与输出膨胀 */
    public static final int MAX_ATOMS_PER_CONDITION = 6;

    /** 并列连接词（长词在前，保证 并且 先于 且 命中） */
    private static final Pattern CONNECTOR = Pattern.compile("并且|以及|同时|且|；|;");

    private static final Pattern EDGE_PUNCT =
            Pattern.compile("^[，,。.、；;：:！!？?\\s\\u3000]+|[，,。.、；;：:！!？?\\s\\u3000]+$");

    private ExitConditionPolicy() {
    }


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
