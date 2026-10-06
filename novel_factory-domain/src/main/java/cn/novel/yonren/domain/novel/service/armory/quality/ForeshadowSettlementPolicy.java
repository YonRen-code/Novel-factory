package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ForeshadowSettlementEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.regex.Pattern;


public final class ForeshadowSettlementPolicy {

    /** 静默兑现标记前缀：prompt 要求模型为"其实已兑现"的条目显式加此标记 */
    public static final String SILENT_PAYOFF_PREFIX = "【已兑现】";

    /**
     * 兜底模式（模型未加前缀时按此识别）。
     *
     * <p>⚠️ **不能用"已兑现"这类连续子串**——实测真实措辞是
     * 「**已在第24章**新华书店挑书情节中**兑现**」「**已在第16章**顾老头测试中自然**承接**」，
     * 关键词与"已"之间隔着一整段章号与情节描述，连续子串会**大面积漏判**
     * （第一版手写子串表时真实样本只认出 3/5）。
     *
     * <p>⚠️ **必须要求显式章号引用**（`第N章`）。实测假阳性：ch8「已融入日常，
     * **无独立回收必要**」——句尾的"回收"是**否定用法**（不打算回收），
     * 却被无章号的宽松模式匹配成"静默兑现"。加上"必须写明第几章"这一约束后，
     * 真实 19 条 VOID 的识别结果为 **9 条静默兑现 / 0 假阳性**（原宽松版为 10 条含 1 假阳）。
     *
     * <p>这个约束同时**语义自洽**：一条"其实已兑现"的线，理应说得出是在**哪一章**兑现的
     * ——这正是 prompt 已要求模型写明的内容（`【已兑现】` 前缀 + 章号）。
     *
     * <p>40 字上限取自实测最长措辞（「第17章已确认陆建国在第二批下岗名单中并决定买断工龄，
     * 车间主任的暗示已闭合」≈ 33 字）。
     */
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

    /**
     * 全部结算里的**静默兑现条数**——即"账记为未填、实际已被剧情消化"的规模。
     *
     * <p>这个数字直接度量**摘要层漏记 `foreshadowingResolved`** 的问题有多严重，
     * 是后续强化摘要 prompt 的依据；也解释了"清账为什么几乎只在弃置"（弃置的大头不是杀长线，
     * 而是给漏记的闭环补一个了断）。
     */
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
