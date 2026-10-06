package cn.novel.yonren.domain.novel.service.armory.quality;

import java.util.List;

/**
 * 批末体检报告：把阶段一~四建立的各项机械指标聚合成一张可分级、可行动的健康清单。
 *
 * <p><b>为什么需要</b>：全自动化写一部长篇，最大的风险不是"某一章写得差"，而是
 * <em>在无人监守下静默劣化却没人知道</em>。此前各指标散落在不同产物里
 *（摘要 / 质量债 / 阶段蓝图），没有任何一处把它们横着看一遍：
 * 工作台只统字数与冲突数，阶段报告只看单阶段内部，离线归因脚本要人工跑。
 * 本报告把"这一批写下来，系统是变健康还是变糟了"变成一个可以直接读的结论。
 *
 * <p><b>分级语义</b>（阶段六的执执行器据此决定续批 / 降级 / 停机）：
 * <ul>
 *   <li>{@link Level#OK} 各指标都在健康线内</li>
 *   <li>{@link Level#WATCH} 有指标越线但未到劣化线，记录下来继续跑</li>
 *   <li>{@link Level#DEGRADED} 有指标越过劣化线，需要人工看一眼</li>
 *   <li>{@link Level#CRITICAL} 多个指标同时劣化（≥{@code CRITICAL_METRIC_COUNT} 项）——
 *       通常意味着某一层的改造没生效或被绕过，继续跑只会放大问题</li>
 * </ul>
 *
 * <p>本报告<b>只给结论与建议，不执行任何动作</b>——动作属于执行器层（阶段六），
 * 观测层擅自停机或降级会让"为什么停"变得不可归因。
 *
 * @param chapterCount    参与体检的章节数（低于 {@code MIN_SAMPLE_CHAPTERS} 时结论不可信）
 * @param metrics         逐项指标
 * @param overall         总体分级（各指标取最差；多项劣化升级为 CRITICAL）
 * @param recommendations 针对非 OK 指标的行动建议（可直接作为人工处置清单）
 */
public record BatchHealthReport(int chapterCount,
                                List<Metric> metrics,
                                Level overall,
                                List<String> recommendations) {

    /** 总体分级：单项越线记录、多项劣化升级 */
    public enum Level {
        OK, WATCH, DEGRADED, CRITICAL;

        /** 取更差的一档 */
        public Level worse(Level other) {
            return this.ordinal() >= other.ordinal() ? this : other;
        }
    }

    /** 指标方向：决定"越大越好"还是"越小越好"，避免每条阈值都手写两遍 */
    public enum Direction {
        HIGHER_IS_BETTER, LOWER_IS_BETTER
    }

    /**
     * 单项指标。
     *
     * @param key        稳定标识（供前端与自动化脚本按 key 取值，不要随文案改）
     * @param label      中文名
     * @param value      实测值
     * @param unit       单位（"%" / "个/章" / "条" / ""）
     * @param healthyLine 健康线的人类可读描述（含实测基线对照，便于人工判断阈值是否合理）
     * @param level      本项分级
     * @param detail     补充说明（分布、来源、口径）
     */
    public record Metric(String key, String label, double value, String unit,
                         String healthyLine, Direction direction, Level level, String detail) {

        /** 渲染为一行文本 */
        public String render() {
            return "%-6s %-14s %s%s（目标 %s）%s".formatted(
                    level, label, format(value), unit, healthyLine,
                    detail == null || detail.isBlank() ? "" : " ｜ " + detail);
        }
    }

    /** 分级是否为"需要人工过问" */
    public boolean needsAttention() {
        return overall == Level.DEGRADED || overall == Level.CRITICAL;
    }

    /** 渲染整份报告（供日志与接口文本展示） */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("批末体检：").append(overall).append("（样本 ").append(chapterCount).append(" 章）");
        if (overall == Level.OK) {
            sb.append("——各指标均在健康线内");
        }
        for (Metric metric : metrics) {
            sb.append("\n  ").append(metric.render());
        }
        if (!recommendations.isEmpty()) {
            sb.append("\n  建议：");
            for (String rec : recommendations) {
                sb.append("\n  - ").append(rec);
            }
        }
        return sb.toString();
    }

    /** 数值格式化：整数不带小数点，小数保留两位 */
    static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }
}
