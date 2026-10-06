package cn.novel.yonren.domain.novel.service.armory.memory;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.QualityDebtEntity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 质量债结算服务：验证式自动核销。
 * 每章审校结束后，以本章未修复 BLOCKING 问题与旧债按 dimension 比对——
 * 同维度复发则清零计数（债自然续期），连续 CLEAN_STREAK_TO_RESOLVE 章无复发即核销。
 * 债的回灌窗口只有最近 1~2 章，且仅未核销债参与边界计算：无核销时低产债区间的
 * 旧债会永久驻留前缀，核销为债补上生命周期终点；核销后同类问题若复发，
 * 会随新章债务再次进入回灌，系统自愈。审校关闭时不结算，无验证信号不伪造结论
 */
@Service
@Slf4j
public class QualityDebtService {

    /** 连续无同维度复发的审校章数达到该值即核销；与回灌窗口 1~2 章对齐，一笔债最多被注入 2 次 */
    public static final int CLEAN_STREAK_TO_RESOLVE = 2;

    /**
     * 逐笔结算未核销债。必须在记入本章新债之前调用，避免本章问题与自身比对。
     * dimension 缺失的债无法验证是否复发，保持未核销并停止累计 cleanStreak，避免无证据自动消债
     *
     * @param debts                现有质量债清单（批内滚动 + 续写预载），原地更新
     * @param newUnresolvedIssues  本章审校后仍未修复的 BLOCKING 问题，可为空
     */
    public void settle(List<QualityDebtEntity> debts, List<ChapterIssueEntity> newUnresolvedIssues) {
        if (debts == null || debts.isEmpty()) {
            return;
        }
        Set<String> newDims = dimensionsOf(newUnresolvedIssues);
        for (QualityDebtEntity debt : debts) {
            if (debt.isResolved() || debt.getChapterNo() == null) {
                continue;
            }
            Set<String> debtDims = dimensionsOf(debt.getIssues());
            if (debtDims.isEmpty()) {
                log.warn("第 {} 章质量债缺少 dimension，无法验证复发，保持未核销", debt.getChapterNo());
                continue;
            }
            boolean recurred = debtDims.stream().anyMatch(newDims::contains);
            if (recurred) {
                debt.setCleanStreak(0);
                continue;
            }
            int streak = debt.getCleanStreak() + 1;
            debt.setCleanStreak(streak);
            if (streak >= CLEAN_STREAK_TO_RESOLVE) {
                debt.setResolved(true);
                log.info("第 {} 章质量债（{} 条）连续 {} 章同维度无复发，自动核销",
                        debt.getChapterNo(), debt.getIssues() == null ? 0 : debt.getIssues().size(), streak);
            }
        }
    }

    /** 维度归一化集合：trim + 小写（受控七维度，容忍模型输出的大小写出入），空维度剔除 */
    private Set<String> dimensionsOf(List<ChapterIssueEntity> issues) {
        if (issues == null || issues.isEmpty()) {
            return Set.of();
        }
        return issues.stream()
                .filter(issue -> issue != null && StringUtils.isNotBlank(issue.getDimension()))
                .map(issue -> issue.getDimension().trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

}
