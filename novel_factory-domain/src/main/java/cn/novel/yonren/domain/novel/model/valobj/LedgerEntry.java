package cn.novel.yonren.domain.novel.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 账本条目值对象：角色/物品/势力三类账本共用的通用条目。
 * 由章节摘要的状态条目按章序滚动合并而成——后章覆盖前章同名条目的状态，
 * 首次出现章保留，供记忆组装与连续性校验使用。
 * 证据链：evidence 为当前状态最新一次的正文原文引用（经摘要侧 indexOf 机械校验），
 * 与 lastChapterNo 一起构成"状态出自第几章、哪段正文"的追溯链
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {

    // 名称（角色名/物品名/势力名）
    private String name;
    // 当前状态（伤势/修为/位置/持有物/动向等）
    private String status;
    // 首次出现章节号
    private Integer firstChapterNo;
    // 最近一次被提及的章节号（**每次提及都会刷新**，不限于状态发生变化——
    // 故 firstChapterNo 与之相等即"该条目全篇只出现过一次"，账本渲染据此把一次性条目降为只列名）
    private Integer lastChapterNo;
    // 最近一次真实状态变化前的状态（同值重复更新不记录；首次登场为 null）
    private String prevStatus;
    // 当前状态最新一次的正文证据原文引用（摘要侧已过机械校验；缺省路径为 null）
    private String evidence;
    // 死亡→非死亡且无明确复活描写的反转存疑标记（角色账本语义）：
    // 置位时账本渲染强制追加"【复活存疑·待人工确认】"，事实变更规则表之一
    private boolean reversalSuspect;

}
