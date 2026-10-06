package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.framework.StrategyHandler;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.AbstractArmorySupport;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 规则树根节点：入口，路由到第一个处理节点
 */
@Service
@Slf4j
public class RootNode extends AbstractArmorySupport {

    @Resource
    private ValidateUserInputNode validateUserInputNode;

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, StoryGenerateResultAggregate> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) {
        return validateUserInputNode;
    }

    @Override
    protected StoryGenerateResultAggregate doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("规则树开始执行，title: {}", requestParameter.getStoryContextEntity().getNovel_title());
        return router(requestParameter, dynamicContext);
    }

}
