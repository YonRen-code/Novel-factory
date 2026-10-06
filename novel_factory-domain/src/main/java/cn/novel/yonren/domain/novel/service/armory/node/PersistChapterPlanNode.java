package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

import cn.novel.yonren.types.utils.SnowflakeIdWorker;

/**
 * 持久化节点：通过仓储端口把章节计划 + 每章正文写到磁盘
 */
@Service
@Slf4j
public class PersistChapterPlanNode extends AbstractArmorySupport {

    /**
     * 单机部署 workerId 固定为 1；storyId 由系统雪花算法生成，模型输出中已无该字段
     */
    private static final SnowflakeIdWorker SNOWFLAKE_ID_WORKER = new SnowflakeIdWorker(1);

    /**
     * storyId 兜底分配：缺失或格式非法时用雪花算法补齐。
     * 计划提前落盘检查点与树尾持久化共用，幂等（已合法则跳过）
     */
    static void ensureStoryId(ChapterPlanAggregate chapterPlanAggregate) {
        // 避免极端情况下生成位数小于最低位数13位，改最低限制为10位
        String storyId = chapterPlanAggregate.getStoryId();
        if (storyId == null || !storyId.matches("^story-\\d{10,19}$")) {
            chapterPlanAggregate.setStoryId("story-" + SNOWFLAKE_ID_WORKER.nextId());
        }
    }

    @Resource
    private IStoryRepository storyRepository;

    @Override
    protected StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 PersistChapterPlanNode - 持久化章节计划与正文");

        ChapterPlanAggregate chapterPlanAggregate = dynamicContext.getChapterPlanAggregate();
        ensureStoryId(chapterPlanAggregate);

        // 逐章检查点已创建并写过的目录直接复用（幂等覆盖写），未创建时（目录早期失败）兜底新建
        Path storyDir = dynamicContext.getStoryDir() != null
                ? dynamicContext.getStoryDir() : storyRepository.createStoryDirectory();
        storyRepository.writeStoryBible(storyDir, requestParameter);
        // 首次确立完结上限：sticky cap 幂等落盘（文件已存在时不覆盖，后续请求不可抬高）
        if (dynamicContext.getMaxChapterCount() != null) {
            storyRepository.writeStoryMeta(storyDir,
                    new IStoryRepository.StoryMeta(dynamicContext.getMaxChapterCount()));
        }
        // 本批 run 目录：章节计划与生成记录按批落盘，续写批次互不覆盖。
        // 计划检查点已创建的直接复用（创建非幂等：作业路径冲突即抛、同步路径序号递增）；
        // 仅兜底路径（检查点未执行/早期失败）时才在此创建。异步作业 runId 与 jobId 同源（run-job-<jobId>），
        // 同步调试路径沿用 run-XXXX 顺序编号
        Path runDir = dynamicContext.getRunDir() != null
                ? dynamicContext.getRunDir()
                : storyRepository.createRunDirectory(storyDir,
                        dynamicContext.getJob() == null ? null : dynamicContext.getJob().getJobId());
        if (dynamicContext.getJob() != null) {
            dynamicContext.getJob().setRunDirName(runDir.getFileName().toString());
        }
        storyRepository.writeChapterPlan(runDir, chapterPlanAggregate);
        storyRepository.writeChapters(storyDir, dynamicContext.getChapterContents());
        storyRepository.writeChapterSummaries(storyDir, dynamicContext.getChapterSummaries());
        storyRepository.writeStageBlueprints(storyDir, dynamicContext.getStageBlueprints());
        storyRepository.writeVolumes(storyDir, dynamicContext.getVolumes());
        storyRepository.writeQualityDebts(storyDir, dynamicContext.getQualityDebts());
        storyRepository.writeStyleStat(storyDir, dynamicContext.getStyleStat());
        storyRepository.writeStyleFingerprints(storyDir, dynamicContext.getStyleFingerprints());
        storyRepository.writeConsistencyIndex(storyDir, dynamicContext.getConsistencyIndex());
        // 伏笔排期表终态兜底（2026-10-03）：stamp() 的状态推进只改内存，蓝图节点仅在补采时写一次，
        // 逐章检查点负责每章同步——这里是批次结束时的最终兜底，三者缺一状态就会回退
        storyRepository.writeForeshadowSchedule(storyDir, dynamicContext.getForeshadowSchedules());
        storyRepository.writeGenerationRecord(runDir, requestParameter, dynamicContext);

        log.info("持久化完成，storyId: {}, path: {}", chapterPlanAggregate.getStoryId(), storyDir.toAbsolutePath());

        return StoryGenerateResultAggregate.builder()
                .chapterPlanAggregate(chapterPlanAggregate)
                .usedPromptMap(dynamicContext.getUsedPromptMap())
                .storyDirName(storyDir.getFileName().toString())
                .build();
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
