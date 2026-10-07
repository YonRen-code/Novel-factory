package cn.novel.yonren.domain.novel.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 跨章一致性索引，不替代角色/物品/势力三账本。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsistencyIndexEntity {
    /** 时间线：跨章事件及其发生章号/故事内时间（TIMELINE 事实投影） */
    @Builder.Default private List<TimelineEntry> timeline = new ArrayList<>();
    /** 伤情账：角色伤势按"角色+部位"去重，保留首次章号与最新状态（INJURY 事实投影） */
    @Builder.Default private List<InjuryEntry> injuries = new ArrayList<>();
    /** 术语表：专有名词的规范名、别名与首次出现章号（TERM 事实投影） */
    @Builder.Default private List<TermEntry> terms = new ArrayList<>();
    /** 数字账：需跨章保持一致的数值（NUMBER 事实投影，含主角年龄锚点） */
    @Builder.Default private List<NumberEntry> numbers = new ArrayList<>();
    /** 机制账：金手指/机制的启用状态、最近使用章号与允许使用间隔 */
    @Builder.Default private List<MechanismEntry> mechanisms = new ArrayList<>();
    /** 人际关系台账（2026-09-16 新增，type=RELATION 的一致性事实投影）：当前关系态按"双方"去重，
     *  保留最近一次更新——回答"A 与 B 现在什么关系"，而不只是某章发生过一次关系变化 */
    @Builder.Default private List<RelationEntry> relations = new ArrayList<>();

    /** 时间线条目：event=事件描述，chapterNo=发生章号，storyTime=故事内时间，evidence=正文证据 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TimelineEntry { private String event; private Integer chapterNo; private String storyTime; private String evidence; }
    /** 伤情条目：character=角色，location=受伤部位，severity=严重度，firstChapter=首次章号，lastStatus=当前伤情，evidence=正文证据 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class InjuryEntry { private String character; private String location; private String severity; private Integer firstChapter; private String lastStatus; private String evidence; }
    /** 术语条目：canonical=规范名，aliases=别名清单，firstChapter=首次出现章号 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class TermEntry { private String canonical; private List<String> aliases; private Integer firstChapter; }
    /** 数字条目：name=数字含义，value=数值，chapterNo=出现章号，scope=所属对象 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class NumberEntry { private String name; private String value; private Integer chapterNo; private String scope; }
    /** 机制条目：name=机制名，type=来源（故事设定/摘要物品），enabled=是否启用，lastUsedChapter=最近使用章号，usageInterval=允许使用间隔（章） */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MechanismEntry { private String name; private String type; private Boolean enabled; private Integer lastUsedChapter; private Integer usageInterval; }
    /** 单条关系：pair 为摘要侧给的双方标识（如「陆沉与墨璇玑」），relation 为当前关系态 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RelationEntry { private String pair; private String relation; private Integer firstChapter; private Integer lastChapter; private String evidence; }
}
