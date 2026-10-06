package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.SettingDraftRequestDTO;
import cn.novel.yonren.api.dto.SettingDraftResponseDTO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.draft.SettingDraftService;
import cn.novel.yonren.types.enums.ResponseCode;
import cn.novel.yonren.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 设定集草稿端点：一键生成整套设定（尤其故事概述），或只重生成其中某几个字段。
 *
 * <p>刻意做成**同步端点且不进作业队列**：它是一次 LLM 调用、零落盘副作用（结果只回填前端表单），
 * 与 {@code /api/story/generate-chapter} 那种"阻塞到全批结束"完全不是一个量级，
 * 也不会去碰单线程的 {@code jobExecutor} 而冻住排队中的生成作业。
 *
 * <p>产出仍是"草稿"：由人看过、改过（或要求重新生成）之后再走 {@code /api/jobs} 提交，
 * 因此这里不做任何闸门与校验——校验留给生成链既有的 {@code ValidateUserInputNode}。
 */
@Slf4j
@RestController
@RequestMapping("/api/setting-draft")
public class SettingDraftController {

    @Resource
    private SettingDraftService settingDraftService;

    @Resource
    private StoryProperties storyProperties;

    @PostMapping
    public SettingDraftResponseDTO draft(@RequestBody SettingDraftRequestDTO request) {
        SettingDraftService.DraftRequest domainRequest = new SettingDraftService.DraftRequest(
                request.getTheme(),
                request.getStyle(),
                request.getTargetAudience(),
                request.getTone(),
                request.getPerspective(),
                request.getExtraHints(),
                request.getTargets(),
                request.getPrevious());

        SettingDraftService.Result result;
        try {
            result = settingDraftService.draft(storyProperties.toStoryVO(), domainRequest);
        } catch (AppException e) {
            // 题材为空 / targets 写了未知字段 → 400（前端能自行改正）
            // 两次尝试都没产出可用 JSON → 502（上游模型没给出可用结果，不是请求的问题）
            HttpStatus status = ResponseCode.ILLEGAL_PARAMETER.getCode().equals(e.getCode())
                    ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, e.getInfo());
        }

        log.info("设定集草稿已产出，题材: {}，重生成字段: {}", request.getTheme(), result.regenerated());
        return toDTO(request.getTheme(), result);
    }

    private SettingDraftResponseDTO toDTO(String theme, SettingDraftService.Result result) {
        SettingDraftService.DraftOutput fields = result.fields();
        SettingDraftResponseDTO dto = new SettingDraftResponseDTO();
        dto.setTheme(theme);
        dto.setNovelTitle(fields.getNovelTitle());
        dto.setStyle(fields.getStyle());
        dto.setWorldSetting(fields.getWorldSetting());
        dto.setPerspective(fields.getPerspective());
        dto.setTargetAudience(fields.getTargetAudience());
        dto.setTone(fields.getTone());
        dto.setProtagonist(fields.getProtagonist());
        dto.setOutline(fields.getOutline());
        dto.setChapterGoal(fields.getChapterGoal());
        dto.setTotalChapters(fields.getTotalChapters());
        dto.setRationale(fields.getRationale());
        dto.setRegenerated(result.regenerated());
        return dto;
    }
}
