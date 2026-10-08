package cn.novel.yonren.domain.novel.service.armory.beats;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.entity.ChapterBeatsEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.types.enums.ModelScene;
import cn.novel.yonren.types.utils.JsonParseFallback;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 场景节拍服务：章节计划 → 正文之间的轻量中间层（每次正文生成前调用一次，小输出任务）。
 * 把本章计划拆解为 3-5 个有序节拍（地点/在场角色/核心冲突/信息增量/篇幅占比），
 * 注入正文 prompt 要求逐拍扩写——治"计划太粗导致模型即兴注水"的根因。
 *
 * 失败 fail-soft：任何异常/解析失败返回 null，调用方降级为无节拍直写，不阻塞生成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterBeatsService {

    private static final BeanOutputConverter<ChapterBeatsEntity> CONVERTER =
            new BeanOutputConverter<>(ChapterBeatsEntity.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /** 节拍数下限/上限：过少失去约束意义，过多则束缚创作 */
    private static final int MIN_BEATS = 2;
    private static final int MAX_BEATS = 6;

    private final LlmGateway llmGateway;

    /**
     * 为本章生成场景节拍表
     *
     * @return 节拍表；生成失败/解析失败/节拍数越界时返回 null（调用方降级为无节拍直写）
     */
    public ChapterBeatsEntity buildBeats(StoryVO storyVO, ChapterPlanItemEntity item, int globalNo,
                                         String contextText) {
        try {
            // 独立 chapter-beats 场景：每章一次的高频调用留在 flash 控成本，不跟随 chapter-plan 升 max
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .userPrompt(buildPrompt(item, globalNo, contextText))
                    .label("beats-第" + globalNo + "章")
                    .scene(ModelScene.CHAPTER_BEATS)
                    .build());

            ChapterBeatsEntity beats = tryConvert(raw);
            if (beats == null) {
                beats = JsonParseFallback.parse(raw, this::tryConvert);
            }
            if (beats == null || beats.getBeats() == null
                    || beats.getBeats().size() < MIN_BEATS || beats.getBeats().size() > MAX_BEATS) {
                log.warn("第 {} 章场景节拍生成失败或节拍数越界，降级为无节拍直写", globalNo);
                return null;
            }
            log.info("第 {} 章场景节拍生成完成，共 {} 拍", globalNo, beats.getBeats().size());
            // 信息增量机械检查：节拍 prompt 里早有"每拍必须兑现信息增量"的纪律，
            // 但此前**没有任何机械校验**——无状态变化的拍会诱导模型"原地扩写"凑篇幅。
            // 判据与 renderBeatsPrompt 的纪律同源（infoGain 承载 新信息/状态变化/关系变化）。
            long stagnant = beats.getBeats().stream().filter(b -> !hasStateChange(b)).count();
            if (stagnant > 0) {
                log.warn("第 {} 章有 {}/{} 拍未声明信息增量（无状态变化），已在正文 prompt 中标注"
                                + "「并入相邻拍、严禁原地扩写」", globalNo, stagnant, beats.getBeats().size());
            }
            return beats;
        } catch (Exception e) {
            log.warn("第 {} 章场景节拍生成异常，降级为无节拍直写：{}", globalNo, e.getMessage());
            return null;
        }
    }


    public static boolean hasStateChange(ChapterBeatsEntity.Beat beat) {
        return beat != null && StringUtils.isNotBlank(beat.getInfoGain());
    }

    /**
     * 把节拍表渲染进正文 prompt：逐拍扩写 + 每拍必须兑现信息增量。
     * 这是正文侧唯一的"防注水供给"——模型按拍执行，没有自由虚构填充的空间
     */
    public String renderBeatsPrompt(ChapterBeatsEntity beats) {
        if (beats == null || beats.getBeats() == null || beats.getBeats().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【本章节拍表】本章已拆为以下有序节拍，请逐拍扩写，不要跳过或合并：");
        int idx = 1;
        for (ChapterBeatsEntity.Beat beat : beats.getBeats()) {
            sb.append("\n节拍 ").append(idx++);
            if (StringUtils.isNotBlank(beat.getLocation())) {
                sb.append("｜地点：").append(beat.getLocation());
            }
            if (beat.getCharacters() != null && !beat.getCharacters().isEmpty()) {
                sb.append("｜在场：").append(String.join("、", beat.getCharacters()));
            }
            if (StringUtils.isNotBlank(beat.getConflict())) {
                sb.append("｜冲突：").append(beat.getConflict());
            }
            if (StringUtils.isNotBlank(beat.getInfoGain())) {
                sb.append("｜信息增量：").append(beat.getInfoGain());
            } else {
                // 机械检查发现的"无状态变化拍"：标注为可合并，堵住"原地扩写凑篇幅"这条路
                sb.append("｜⚠️本拍未声明信息增量——若无法产生新信息/关系变化/资源变化/风险变化/"
                        + "不可逆决定，请并入相邻拍，严禁原地扩写凑篇幅");
            }
            if (StringUtils.isNotBlank(beat.getWeight())) {
                sb.append("｜篇幅占比：约").append(beat.getWeight());
            }
        }
        sb.append("\n扩写纪律：每一拍必须兑现其【信息增量】（新信息/状态变化/关系变化至少其一）；")
                .append("禁止在一拍内部原地循环同一情绪或同一动作；上一拍未完成的动作必须在下一拍延续或交代中断原因。");
        return sb.toString();
    }


    private String buildPrompt(ChapterPlanItemEntity item, int globalNo, String contextText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是小说场景调度师。把以下章节计划拆解为 3-5 个有序的场景节拍，供写手逐拍扩写。")
                .append("\n要求：")
                .append("\n1. 每拍四要素：location（地点）、characters（在场角色）、conflict（本拍核心冲突或关键动作）、")
                .append("infoGain（本拍必须产生的信息增量：新信息/状态变化/关系变化至少其一）、weight（篇幅占比，合计 100%）；")
                .append("\n   ⚠️ characters 必须是**纯字符串数组**，元素就是角色名本身，"
                        + "不得写成对象、不得夹带身份说明——身份信息不属于本字段。")
                .append("✅ [\"角色A\",\"角色B\"]　❌ [{\"角色A\":\"婴儿\"}]　❌ [\"角色A（婴儿）\"]")
                .append("\n2. 节拍按叙事顺序排列，合起来必须完整覆盖本章目标与全部关键事件；")
                .append("\n3. 第一拍必须无缝承接上一章结尾的现场/悬念，最后一拍必须落在本章计划的结尾悬念上；")
                .append("\n4. 每拍的 conflict 必须具体（谁对谁的什么行动/阻碍），严禁‘推进剧情’‘发展关系’这类空话；")
                .append("\n5. 不输出 Markdown，不输出解释。")
                .append(StringUtils.isBlank(contextText) ? "" : "\n\n【前情上下文】以下是紧邻本章之前的章节摘要——"
                        + "**要求 3 的「承接上一章结尾」只能依据这里，不要凭空想象上一章发生了什么**：\n"
                        + contextText)
                .append("\n\n【本章计划】")
                .append("\n标题：").append(item.getTitle() == null ? "" : item.getTitle())
                .append("\n目标：").append(item.getGoal() == null ? "" : item.getGoal())
                .append("\n关键事件：")
                .append(item.getKeyEvents() == null ? "" : String.join("、", item.getKeyEvents()))
                .append("\n结尾悬念：").append(item.getEndingHook() == null ? "" : item.getEndingHook())
                .append("\n\n请严格按照以下 JSON 格式输出：")
                .append("\n{\"beats\":[{\"location\":\"地点\",\"characters\":[\"角色A\"],")
                .append("\"conflict\":\"核心冲突\",\"infoGain\":\"信息增量\",\"weight\":\"30%\"}]}");
        return sb.toString();
    }

    private ChapterBeatsEntity tryConvert(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return CONVERTER.convert(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
