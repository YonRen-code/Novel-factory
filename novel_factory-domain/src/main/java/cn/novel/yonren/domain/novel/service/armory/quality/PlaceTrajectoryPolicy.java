package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 地点轨迹判据：把「每章换场」这个静默劣化点变成规划层可见、可校准的信号。
 *
 * <p><b>问题</b>（162 章实测）：地点只存在于 {@code timePoint}（"时间与地点"混在一串自由文本），
 * 且<em>从不回灌规划层</em>——规划层看不到"最近都在哪儿、哪些地方已经建立"，于是每章都开新场景。
 * 实测：每章新地点率 <b>0.827</b>，10 章滑窗内「不同地点数」中位 <b>10</b>
 * （即中位窗口是<em>每一章一个新地点</em>），分布 min 6 / 中位 10 / max 10。
 *
 * <p><b>口径</b>：优先取模型新输出的 {@code placePoint} 字段（精确、机械可统计）；
 * 老数据回退为 {@code timePoint} 按「，,；;」切段取<em>最后一段</em>
 * （摘要 prompt 的样例是"时间，地点"，地点在后）。
 * 刻意<b>不做</b>地点后缀/时间词启发式——「前/后/上/下」既见于地点（后山、门口前）
 * 又见于时间（爆炸前、三日后），加启发式只会引入不可预测的新误判面。
 *
 * <p>本类只产<em>建议与警示</em>（WARN 语义），不进任何 BLOCKING 闸门：
 * 地点密度是创作取舍，机械层只负责把现状摆到规划层面前。
 */
public final class PlaceTrajectoryPolicy {

    /** 轨迹回看窗口（章）：与密度反馈的 DENSITY_LOOKBACK 同一量级，便于规划层横向对照 */
    public static final int TRAJECTORY_WINDOW = 10;

    /** 单个规划段建议新增地点上限：超出说明在"赶路"，应改用一两句过渡带过 */
    public static final int NEW_PLACE_BUDGET_PER_SEGMENT = 3;

    /** 同一地点建议连续承载的章数下限：低于此值即为"打点式换场" */
    public static final int MIN_CHAPTERS_PER_PLACE = 2;

    /** 地点与时间在 timePoint 里的常见分隔符 */
    private static final Pattern SEG_SPLIT = Pattern.compile("[，,；;]");

    private PlaceTrajectoryPolicy() {
    }

    /**
     * 取某章的地点：优先 {@code placePoint}，缺失时从 {@code timePoint} 回退提取。
     * 两者都取不到返回 null（不参与统计，宁可漏报不误伤）。
     */
    public static String placeOf(ChapterSummaryEntity summary) {
        if (summary == null) {
            return null;
        }
        if (StringUtils.isNotBlank(summary.getPlacePoint())) {
            return summary.getPlacePoint().trim();
        }
        return extractFromTimePoint(summary.getTimePoint());
    }

    /** 老数据回退口径：按分隔符切段取最后一段（长度 ≥2 才采用），两端口径见类注释 */
    static String extractFromTimePoint(String timePoint) {
        if (StringUtils.isBlank(timePoint)) {
            return null;
        }
        String trimmed = timePoint.trim();
        String[] segs = SEG_SPLIT.split(trimmed);
        String last = null;
        for (int i = segs.length - 1; i >= 0; i--) {
            String seg = segs[i].trim();
            if (!seg.isEmpty()) {
                last = seg;
                break;
            }
        }
        if (last == null || last.length() < 2) {
            return trimmed.length() >= 2 ? trimmed : null;
        }
        return last;
    }

    /**
     * 是否为"每章一个新地点"：窗口内不同地点数达到窗口长度本身。
     * 取这个阈值而非中位数之类的相对值——它是一个有明确语义的量：
     * 窗口长度 = 每章都换新场景，不再是"密度偏高"而是"完全没有复用"。
     */
    public static boolean isChurnAlarming(int distinctInWindow, int windowSize) {
        return windowSize > 0 && distinctInWindow >= windowSize;
    }

    /** 最近 window 章的 (章号, 地点) 序列，按章号升序；取不到地点的章被跳过 */
    public static List<Map.Entry<Integer, String>> recentTrajectory(List<ChapterSummaryEntity> summaries, int window) {
        List<Map.Entry<Integer, String>> rows = new ArrayList<>();
        if (summaries == null || summaries.isEmpty() || window <= 0) {
            return rows;
        }
        int from = Math.max(0, summaries.size() - window);
        for (int i = from; i < summaries.size(); i++) {
            ChapterSummaryEntity summary = summaries.get(i);
            if (summary == null || summary.getChapterNo() == null) {
                continue;
            }
            String place = placeOf(summary);
            if (StringUtils.isNotBlank(place)) {
                rows.add(Map.entry(summary.getChapterNo(), place));
            }
        }
        return rows;
    }

    /**
     * 渲染【地点轨迹】块（回灌规划层）；无任何地点信息返回 null（老数据没 timePoint 时不注入）。
     *
     * <p>无论是否超预算都会注入：规划层此前<em>完全看不到</em>地点信息，
     * 先把它摆出来才有"复用已建立地点"的可能；超预算时再追加一句警示。
     */
    public static String renderTrajectory(List<ChapterSummaryEntity> summaries) {
        List<Map.Entry<Integer, String>> rows = recentTrajectory(summaries, TRAJECTORY_WINDOW);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> row : rows) {
            freq.merge(row.getValue(), 1, Integer::sum);
        }
        int distinct = freq.size();
        int fromChapter = rows.get(0).getKey();
        int toChapter = rows.get(rows.size() - 1).getKey();

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【地点轨迹】最近 ").append(rows.size()).append(" 章的实际地点（第 ")
                .append(fromChapter).append("-").append(toChapter).append(" 章）：");
        for (Map.Entry<Integer, String> row : rows) {
            sb.append("\n- 第").append(row.getKey()).append("章：").append(row.getValue());
        }
        sb.append("\n可复用地点（出现 ≥2 章的，规划层优先在这里展开）：");
        StringBuilder reused = new StringBuilder();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() >= MIN_CHAPTERS_PER_PLACE) {
                reused.append(reused.length() == 0 ? "" : "、").append(e.getKey()).append("×").append(e.getValue());
            }
        }
        sb.append(reused.length() > 0 ? reused.toString() : "（无——窗口内每个地点都只出现过一次）");
        sb.append("\n本段规划的地点要求：");
        sb.append("\n1. 本段新增地点 ≤ ").append(NEW_PLACE_BUDGET_PER_SEGMENT)
                .append(" 个，其余章节在已建立的地点里展开；");
        sb.append("\n2. 同一地点连续承载至少 ").append(MIN_CHAPTERS_PER_PLACE)
                .append(" 章再迁移，严禁每章换新场景；");
        sb.append("\n3. 若剧情需要跨地移动，用一两句过渡带过，不要为每个途经点单独开一章。");
        if (isChurnAlarming(distinct, rows.size())) {
            sb.append("\n⚠ 警示：上述 ").append(rows.size()).append(" 章出现了 ").append(distinct)
                    .append(" 个不同地点——**每一章都在换新场景**，地点密度已远超合理范围，"
                            + "读者无法建立空间感。本段必须显著回收地点，优先复用上面列出的地点。");
        }
        // 命名一致性（2026-09-29）：本块按**字面**统计地点，模型为同一处另起叫法会被计成新地点。
        // **刻意不做机械归并**——这一条是实测换来的：在 162 章基线上，「九渊剑冢」这一处建筑群下的
        // 「外围冰瀑下 / 最高处葬剑台 / 核心虚空内 / 附近石屋及菜地 / 前往途中」等 33 个子区域，
        // 会被"公共子串"判据全部吞并成一个地点。"同一建筑群的不同子区域"与"同一处的不同叫法"
        // 在字面上不可分，强行归并会把真实换场抹平、让指标失真（类注释早已警告过启发式的代价）。
        // 故改为在源头约束叫法：统计口径保持诚实，由规划层要求统一命名。
        sb.append("\n命名要求：写同一处地点时必须沿用上面出现过的名字，不得另起叫法"
                + "（本块按字面统计，不同叫法会被计为不同地点）。");
        return sb.toString();
    }
}
