package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 校验用户输入节点
 */
@Service
@Slf4j
public class ValidateUserInputNode extends AbstractArmorySupport {

    @Resource
    private BuildStoryContextNode buildStoryContextNode;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return buildStoryContextNode;
    }

    @Override
    public StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("装配 ValidateUserInputNode - 校验");
        StoryContextEntity storyContextEntity = requestParameter.getStoryContextEntity();
        if (storyContextEntity == null ||
            StringUtils.isBlank(storyContextEntity.getNovel_title()) || StringUtils.isBlank(storyContextEntity.getTheme()) ||
            StringUtils.isBlank(storyContextEntity.getStyle()) || StringUtils.isBlank(storyContextEntity.getWorldSetting()) ||
            StringUtils.isBlank(storyContextEntity.getPerspective()) || StringUtils.isBlank(storyContextEntity.getTargetAudience()) ||
            StringUtils.isBlank(storyContextEntity.getTone()) || StringUtils.isBlank(storyContextEntity.getProtagonist()) ||
            StringUtils.isBlank(storyContextEntity.getOutline()) || StringUtils.isBlank(storyContextEntity.getChapterGoal()) ||
            storyContextEntity.getChapterCount() == null || storyContextEntity.getChapterCount() <= 0 ) {
            throw new AppException(ResponseCode.NULL_EXCEPTION.getCode(),ResponseCode.NULL_EXCEPTION.getInfo());
        }

        return router(requestParameter, dynamicContext);
    }
}
