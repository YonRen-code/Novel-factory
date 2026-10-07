package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.regex.Pattern;


public final class ForeshadowSettlementPolicy {

    /** 静默兑现标记前缀：prompt 要求模型为"其实已兑现"的条目显式加此标记 */
    public static final String SILENT_PAYOFF_PREFIX = "【已兑现】";

    private static final Pattern SILENT_PAYOFF_PATTERN = Pattern.compile(
            "第\\s*\\d+.{0,40}(兑现|闭合|消化|覆盖|回收|解决|承接|收束|了结)");

    private ForeshadowSettlementPolicy() {
    }

    /**
     * 该裁决是否为"静默兑现"：被判 VOID，但理由表明它其实已在正文中兑现/承接。
     * 非 VOID 裁决恒为 false（RECOVER 本来就承认它还活着）。
     */
    public static boolean looksSilentlyResolved(ForeshadowSettlementEntity.SettlementDecision decision) {
        if (decision == null
                || !ForeshadowSettlementEntity.DECISION_VOID.equals(decision.getDecision())) {
            return false;
        }
        String reason = decision.getReason();
        if (StringUtils.isBlank(reason)) {
            return false;
        }
        return reason.contains(SILENT_PAYOFF_PREFIX)
                || SILENT_PAYOFF_PATTERN.matcher(reason).find();
    }


    public static long silentPayoffCount(List<ForeshadowSettlementEntity> settlements) {
        if (settlements == null || settlements.isEmpty()) {
            return 0L;
        }
        return settlements.stream()
                .filter(s -> s != null && s.getDecisions() != null)
                .flatMap(s -> s.getDecisions().stream())
                .filter(ForeshadowSettlementPolicy::looksSilentlyResolved)
                .count();
    }

    /** 全部结算里的 VOID 总数（供静默兑现占比归因） */
    public static long voidCount(List<ForeshadowSettlementEntity> settlements) {
        if (settlements == null || settlements.isEmpty()) {
            return 0L;
        }
        return settlements.stream()
                .filter(s -> s != null && s.getDecisions() != null)
                .flatMap(s -> s.getDecisions().stream())
                .filter(d -> d != null && ForeshadowSettlementEntity.DECISION_VOID.equals(d.getDecision()))
                .count();
    }
}
