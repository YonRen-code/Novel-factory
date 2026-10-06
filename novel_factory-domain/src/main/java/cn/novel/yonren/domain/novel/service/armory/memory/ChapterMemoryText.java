package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 单章「可核验文本」的**唯一构建处**（2026-09-22 提取）。
 *
 * <p>此前 {@code StageExitReviewService} 与 {@code FinaleReviewService} 各持一份
 * **逐字相同**的私有实现——两份实现必然分叉（这次加"可核验细节"就要同时改两处才不漏）。
 * 凡是"给核验模型看的单章文本"都应当走这里。
 *
 * <p><b>文本构成</b>：剧情摘要 + 三账本状态串 + **可核验细节**。
 *
 * <p>⚠️ 第三项是这次新增的，针对的是一个实测踩过的假阴性：
 * 出口条件常要求"某动作被文本明确描写"这类**细节证据**（如"许知意食指轻叩桌面两下"），
 * 而 {@code summary} 按设计**只记主干、明确排除动作细节**——
 * 于是正文明明写着「指尖在桌面上无意识地轻叩了两下」，核验却在摘要里找不到任何字样，
 * 只能判未达成。补上 {@code verifiableDetails} 之后，核验文本才真正覆盖了条件所要求的粒度。
 *
 * <p>注意：这只是**补粒度**，不是放宽门槛——证据仍必须在文本中逐字存在。
 */
public final class ChapterMemoryText {

    private ChapterMemoryText() {
    }

    /** 单章可核验文本：剧情摘要 + 三账本状态串 + 可核验细节 */
    public static String of(ChapterSummaryEntity summary) {
        if (summary == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(StringUtils.defaultString(summary.getSummary()));
        appendStates(sb, summary.getCharacterStates());
        appendStates(sb, summary.getItemStates());
        appendStates(sb, summary.getFactionStates());
        appendDetails(sb, summary.getVerifiableDetails());
        return sb.toString();
    }

    private static void appendStates(StringBuilder sb, List<ChapterSummaryEntity.StateEntry> states) {
        if (states == null) {
            return;
        }
        for (ChapterSummaryEntity.StateEntry state : states) {
            if (state != null && StringUtils.isNotBlank(state.getName())) {
                sb.append("（").append(state.getName()).append("：")
                        .append(StringUtils.defaultString(state.getStatus())).append("）");
            }
        }
    }

    private static void appendDetails(StringBuilder sb, List<String> details) {
        if (details == null || details.isEmpty()) {
            return;
        }
        sb.append("【可核验细节】");
        for (String detail : details) {
            if (StringUtils.isNotBlank(detail)) {
                sb.append("（").append(detail.trim()).append("）");
            }
        }
    }
}
