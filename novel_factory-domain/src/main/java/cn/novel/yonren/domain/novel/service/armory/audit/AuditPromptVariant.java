package cn.novel.yonren.domain.novel.service.armory.audit;

/**
 * 审校 prompt 变体开关（A/B 校准骨架，见 docs/enhancement-plan.md A 系列）：
 * A = 现行判据（默认，生产行为与历史逐字一致）；B = 实验分支（A2 目录化判据落地处）。
 * 经 {@code -Daudit.prompt.variant=B} 切换，评测骨架（AuditEvalHarnessTest）据此跑双变体对照。
 * 生产路径不设置该属性时恒为 A——开关默认无害，不构成运行时行为分叉
 */
public final class AuditPromptVariant {

    public static final String SYSTEM_PROPERTY = "audit.prompt.variant";

    private AuditPromptVariant() {
    }

    public static String active() {
        return System.getProperty(SYSTEM_PROPERTY, "A");
    }

    public static boolean isB() {
        return "B".equalsIgnoreCase(active());
    }
}
