package cn.novel.yonren.domain.novel.service.armory.prompt;

/**
 * 关键写作规则标记：其固定资产（rules/ 与 references/ 下的规则文件、标题锚点）是生成质量的硬前提——
 * 硬约束、反注水、去 AI 味、视角纪律、结构/钩子技法这类"红线级"规则。
 *
 * <p><b>为什么需要</b>（2026-09-28 核查）：{@link PromptRuleFileLoader} 对缺失文件返回 null（不抛异常），
 * 策略侧把 null 当"无此规则"返回空表——于是文件被改名、标题锚点被改写或打包遗漏时，
 * 对应红线会**从所有 prompt 里静默消失**，整批在缺约束状态下跑完，产出上几乎看不出来。
 * （{@code PromptBuilder} 原有的 catch 只兜异常，而加载器根本不抛异常，那条路径从未生效。）
 * 标记者由 {@link PromptBuilder} 强制：supports 命中却一条也加载不到、或加载抛异常，即终止作业。
 *
 * <p><b>不标记的</b>（题材资料、对照片例等）：允许"合法为空"——未命中题材、题材范例择一让位
 * 都返回空表，那是正常语义而非故障，不能误判为资产缺失
 */
public interface CriticalPromptRule {

    /** 关键资产说明（文件/小节），用于缺失时的失败信息定位 */
    String assetDescription();

}