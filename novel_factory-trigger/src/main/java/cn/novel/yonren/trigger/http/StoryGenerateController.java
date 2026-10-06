package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.api.dto.StoryGenerateResponseDTO;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.service.armory.StoryGenerateService;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.trigger.http.StoryCommandAssembler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 触发层：只负责触发与 DTO 适配，不承载业务逻辑。
 * 同步调试端点：阻塞直到全批生成结束；异步作业走 /api/jobs
 */
@Slf4j
@RestController
@RequestMapping("/api/story")
public class StoryGenerateController {

    @Resource
    private StoryGenerateService storyGenerateService;

    @Resource
    private StoryProperties storyProperties;

    @PostMapping("/generate-chapter")
    public StoryGenerateResponseDTO chapterGenerate(@RequestBody StoryGenerateRequestDTO request) throws Exception {
        ArmoryCommandEntity command = StoryCommandAssembler.toCommand(request, storyProperties);

        StoryGenerateResultAggregate result = storyGenerateService.generate(command);

        // 领域结果 -> DTO
        StoryGenerateResponseDTO response = new StoryGenerateResponseDTO();
        response.setChapterPlan(result.getChapterPlanAggregate());
        response.setUsedPromptMap(result.getUsedPromptMap());
        response.setStoryDirName(result.getStoryDirName());

        return response;
    }
}
