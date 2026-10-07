package cn.novel.yonren.domain.novel.service.armory.prompt;


public interface CriticalPromptRule {

    /** 关键资产说明（文件/小节），用于缺失时的失败信息定位 */
    String assetDescription();

}