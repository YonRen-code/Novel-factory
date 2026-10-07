package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.List;


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
