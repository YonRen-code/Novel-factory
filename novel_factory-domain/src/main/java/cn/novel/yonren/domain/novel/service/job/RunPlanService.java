package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.quality.BatchHealthReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 无人值守续写计划（RunPlan）：决定"这批写完了要不要接着写下一批，为什么"。
 *
 * <p><b>为什么需要</b>：此前作业终态只有 COMPLETED / FAILED / CANCELLED，
 * <em>没有批次概念</em>——每批跑完必须人工再点一次提交。实测 26 次提交 / 20 个唯一批次区间，
 * 其中 4 个区间被重复提交（第 74-90 章提交 4 次），这就是"人工监守"的真实成本。
 * 自续批把这段人工动作变成机械规则。
 *
 * <p><b>停机条件是本类的全部价值</b>：挂机最怕的不是停不下来，而是<em>该停的时候不停</em>。
 * 因此每一条续批都要求"四个条件全部通过"：未达标、未超续批上限、批末体检未达停线、未取消/未熔断
 * （后两条由作业层在提交前把守）。任何一条不满足都返回 {@code continueNext=false} 并带上人类可读理由，
 * 落到作业状态里——这样"为什么停了"永远可归因，不需要翻日志。
 *
 * <p>本类不做 IO、不调模型：输入是已算好的进度与体检结论，输出是一个决策 + 一条可复用的命令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunPlanService {

    private final StoryProperties storyProperties;

    /**
     * 续批决策。
     *
     * @param continueNext 是否继续下一批
     * @param reason       人类可读理由（写入日志与作业状态，供"为什么停/为什么续"归因）
     */
    public record Decision(boolean continueNext, String reason) {
    }

    /** 是否开启自动续批（yml: story.run-plan.enabled，默认 false——它会自动连续花 token） */
    public boolean enabled() {
        StoryProperties.RunPlanProperties plan = plan();
        return plan != null && plan.isEnabled();
    }

    /**
     * 续批决策。批次进度与体检结论都由调用方给出（作业层已持有），本方法保持纯函数。
     *
     * @param chaptersDone 当前已完成章数（含本批）
     * @param batchesDone  已完成批次数（含本批）
     * @param health       本批批末体检结论，可为 null（样本不足/读取失败时不做健康判停）
     */
    public Decision decide(int chaptersDone, int batchesDone, BatchHealthReport health) {
        if (!enabled()) {
            return new Decision(false, "未开启自动续批（story.run-plan.enabled=false）");
        }
        Integer target = targetChapters();
        if (target != null && chaptersDone >= target) {
            return new Decision(false, "已达标：" + chaptersDone + "/" + target + " 章");
        }
        StoryProperties.RunPlanProperties plan = plan();
        if (plan.getMaxBatches() > 0 && batchesDone >= plan.getMaxBatches()) {
            return new Decision(false, "已达续批上限 " + plan.getMaxBatches() + " 批（已完成 " + chaptersDone + " 章）");
        }
        BatchHealthReport.Level stopLevel = parseStopLevel(plan.getStopOnHealthLevel());
        if (health != null && health.overall().ordinal() >= stopLevel.ordinal()) {
            return new Decision(false, "批末体检 " + health.overall() + " 达到停线 " + stopLevel
                    + "，停止自动续批交人工过问（详情见批末体检日志或 /health 端点）");
        }
        String progress = target == null ? chaptersDone + " 章（未设目标）" : chaptersDone + "/" + target + " 章";
        return new Decision(true, "继续自动续批：已完成 " + progress + "，即将开始第 " + (batchesDone + 1) + " 批");
    }

    /**
     * 目标总章数：run-plan.target-chapters 优先；未配置时用 constraints（仅在 enforce-chapter-limit 开启时生效）。
     * 都取不到返回 null —— 此时只有续批上限与体检判停，没有"达标"判停。
     */
    public Integer targetChapters() {
        StoryProperties.RunPlanProperties plan = plan();
        if (plan != null && plan.getTargetChapters() != null && plan.getTargetChapters() > 0) {
            return plan.getTargetChapters();
        }
        StoryVO.Constraints constraints = storyProperties.getConstraints();
        if (constraints != null && Boolean.TRUE.equals(constraints.getEnforceChapterLimit())
                && constraints.getMaxChapterCount() != null && constraints.getMaxChapterCount() > 0) {
            return constraints.getMaxChapterCount();
        }
        return null;
    }

    /**
     * 生成下一批的命令：克隆原请求并指向已完成的故事目录。
     *
     * <p>批大小取 run-plan.batch-size（未配置则沿用原请求的 chapterCount），并按剩余章数收敛——
     * 不依赖"到顶保护"来兜最后一批：让请求本身就精确，日志与批次区间才可读。
     */
    public ArmoryCommandEntity nextBatchCommand(ArmoryCommandEntity current, String storyDirName, int chaptersDone) {
        if (current == null || StringUtils.isBlank(storyDirName)) {
            return null;
        }
        ArmoryCommandEntity next = new ArmoryCommandEntity();
        next.setStoryVO(current.getStoryVO());
        next.setMaxChapterCount(current.getMaxChapterCount());
        next.setResumeStoryDir(storyDirName);
        // 导演通道：创作要点随批传递（自续批保持同一创意方向）
        next.setCreativeNotes(current.getCreativeNotes());

        StoryContextEntity context = new StoryContextEntity();
        if (current.getStoryContextEntity() != null) {
            BeanUtils.copyProperties(current.getStoryContextEntity(), context);
        }
        StoryProperties.RunPlanProperties plan = plan();
        int size = batchSize(current, plan);
        Integer target = targetChapters();
        if (target != null) {
            int remaining = target - chaptersDone;
            if (remaining > 0) {
                size = Math.min(size, remaining);
            }
        }
        context.setChapterCount(Math.max(1, size));
        next.setStoryContextEntity(context);
        return next;
    }

    /** 批大小：run-plan.batch-size 优先，未配置沿用原请求（chapterCount 即批次大小） */
    private int batchSize(ArmoryCommandEntity current, StoryProperties.RunPlanProperties plan) {
        if (plan != null && plan.getBatchSize() != null && plan.getBatchSize() > 0) {
            return plan.getBatchSize();
        }
        Integer requested = current.getStoryContextEntity() == null
                ? null : current.getStoryContextEntity().getChapterCount();
        return requested == null || requested <= 0 ? 1 : requested;
    }

    /** 停线解析：无法识别的值一律取最保守的 CRITICAL（宁可能多跑，不误停） */
    static BatchHealthReport.Level parseStopLevel(String configured) {
        if (StringUtils.isBlank(configured)) {
            return BatchHealthReport.Level.CRITICAL;
        }
        for (BatchHealthReport.Level level : BatchHealthReport.Level.values()) {
            if (level.name().equalsIgnoreCase(configured.trim())) {
                return level;
            }
        }
        log.warn("run-plan.stop-on-health-level 配置值无法识别：{}，按 CRITICAL 处理", configured);
        return BatchHealthReport.Level.CRITICAL;
    }

    private StoryProperties.RunPlanProperties plan() {
        return storyProperties == null ? null : storyProperties.getRunPlan();
    }
}
