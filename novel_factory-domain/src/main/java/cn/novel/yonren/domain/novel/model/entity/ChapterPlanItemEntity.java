package cn.novel.yonren.domain.novel.model.entity;

import cn.novel.yonren.types.enums.ChapterTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterPlanItemEntity {
    //章节号
    private Integer chapterNo;
    //标题
    private String title;
    //实现目标
    private String goal;
    //角色
    private List<String> characters;
    //关键事件
    private List<String> keyEvents;
    //结尾悬念
    private String endingHook;
    // 本章时间推进声明时序锚 v2）：唯一合法的年龄/时间推进通道——写手据此渲染锁定锚，
    // 审计据此判"推进与声明不符"。格式自由文本（如"推进 2 周，至 2003 年 10 月下旬"）
    private String timeAdvance;
    //章节类型：normal 普通 / climax 高潮 / finale 卷末
    private ChapterTypeVO chapterType;

    /**
     * **本章结束时核心悬念所处的档位**（2026-09-22 新增）：必须**逐字取自**阶段蓝图的
     * {@code suspenseLadder}（由蓝图针对本书生成，见 {@code StageBlueprintEntity.suspenseLadder}）。
     *
     * <p><b>为什么要这个字段</b>：此前的计划约束是"每章至少一处关系/信息/资源的变化"，
     * 而"内心评价悄然变化"也能满足它——实测出现过整批 6 章主线零推进，每章都是
     * "发现线索 → 自我否定 → 回到原点"，规划层甚至还把它写成了要"确立"的叙事范式。
     * 档位是**有序枚举**，机械比较就能判定"整批在原地打转"，这是结构的解法而非文风劝导。
     *
     * <p>校验见 {@code ChapterPlanChecks.validateSuspenseAdvance}：不得倒退；
     * 非过渡章不得连续 3 章停留同一档。
     */
    private String suspenseBeat;

    /**
     * **本章落地的主线推进**（2026-10-02 新增）：逐字取自阶段蓝图的
     * {@code StageBlueprintEntity.mainLineByChapter} 中对应章号那条。
     *
     * <p><b>与 {@link #suspenseBeat} 的分工</b>（两个维度，不可互相替代）：
     * <ul>
     *   <li>{@code suspenseBeat}：纵向——"本章结束时核心悬念走到第几格"，须逐字取自档位表
     *       （{@code SuspenseLadderPolicy.resolveIndex} 靠前缀匹配定位），故**不宜再往里塞附加文字**；</li>
     *   <li>{@code mainLineAdvance}：横向——"这一章主线做了什么"，逐字取自蓝图的章级推进。</li>
     * </ul>
     *
     * <p>存在的意义：档位表 3-6 档覆盖 5-80 章，多章共用同一档是常态，
     * 单靠档位无法区分相邻两章；本字段让"每章各自推进了什么"成为**可机械校验**的东西
     * （缺章 / 未落地 / 相邻雷同三类违规）。老数据为 null，校验跳过。
     */
    private String mainLineAdvance;
}
