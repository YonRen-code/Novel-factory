package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 章节计划裁决请求体：镜像 {@code ChapterPlanAggregate} 结构。
 *
 * <p>用法：把 {@code GET /api/jobs/{jobId}} 返回的 {@code pendingChapterPlan} 原样取回、
 * 按需修改后 POST 到 {@code /api/jobs/{jobId}/chapter-plan/approve}。
 *
 * <p>语义约定：
 * <ul>
 *   <li>{@code chapters} 为 null 或空 → 视为"认可 AI 原版"，后端采纳原计划；</li>
 *   <li>{@code chapters} 非空 → 视为人工修订稿，后端会重新做结构机械校验
 *       （章数、起始编号连续、编号唯一、title/goal/keyEvents 非空），校验不过返回 400 且保持挂起；</li>
 *   <li>{@code storyId} 可空，留空则沿用原计划的值。</li>
 * </ul>
 */
@Data
public class ChapterPlanApprovalRequestDTO {

    /** 计划 ID 可空：留空沿用原计划（不该要求人工维护系统生成的 ID） */
    private String storyId;

    /** 章节条目；null/空 = 采纳 AI 原版计划 */
    private List<Chapter> chapters;

    /** 规划风险自评（可空），透传落盘 */
    private List<String> risks;

    @Data
    public static class Chapter {
        /** 章节号（全书全局编号；连续、唯一为机械校验项，不过返回 400 且保持挂起） */
        private Integer chapterNo;
        /** 章节标题（非空校验项） */
        private String title;
        /** 本章实现目标（非空校验项）：本章要完成的事 */
        private String goal;
        /** 本章出场角色 */
        private List<String> characters;
        /** 本章关键事件（非空校验项） */
        private List<String> keyEvents;
        /** 结尾悬念（章末钩子） */
        private String endingHook;
        /** normal / transition / climax / finale；容错解析，未识别值按 normal（不因大小写或拼写中断裁决） */
        private String chapterType;
    }
}
