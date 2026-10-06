package cn.novel.yonren.domain.novel.service.armory.llm;

import cn.novel.yonren.domain.novel.adapter.llm.LlmGateway;
import cn.novel.yonren.domain.novel.model.valobj.LlmCall;
import cn.novel.yonren.domain.novel.model.valobj.StoryVO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.plan.ChapterPlanPromptService;
import cn.novel.yonren.domain.novel.service.armory.prompt.valobj.PromptContext;
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
import java.util.Map;

/**
 * 批次计划分支推演（计划级选优）：同一规划 prompt 派生"稳健线/进取线"两个变体，各输出
 * 【方向建议】小 JSON（structure/direction/risks，几百 token，不会截断）；双稿均可解析时
 * 由第二模型族（chapter-judge 场景）对比择优，调用方按胜出方向再生成正式章节计划。
 * 任一稿失败回退另一稿，全败返回 null（调用方降级走单稿规划）。
 * 成本：每段 +2 次小方向调用 + 1 次评审调用，频率低（批/段边界才发生）。
 * story.plan.branches=1（默认）时整体关闭；评审 prompt 明令不评长短。
 * 所有规划路径（树内链式 / worker 惰性分段）统一经 ChapterPlanSegmentPlanner 走本服务，能力一致。
 * 分支调用前剥离规划 prompt 尾部的 chapters schema 指令块：该显式 schema 是最强格式信号，
 * 不剥离时模型必然无视"只输出方向"而输出完整章节计划
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlanBranchService {

    /** 稳健线变体：因果链稳妥、连续性优先；只输出方向建议（小 JSON），不输出完整章节计划 */
    private static final String VARIANT_A = "\n\n【规划视角A·稳健线】本稿按最稳妥的因果链推进："
            + "优先连续性与账本一致，冲突升级克制，不引入额外结构冒险。"
            + "基于以上视角，只输出【方向建议】JSON（严禁输出完整章节计划，只给方向）："
            + "{\"structure\":\"本段建议的叙事结构（如起承转合/多线并进）\","
            + "\"direction\":\"2-3 句方向概述：本段关键冲突如何推进、节奏如何安排\","
            + "\"risks\":[\"2-3 条本方案最大风险\"]}";

    /** 进取线变体：更早引爆冲突、允许非线性结构；同样只输出方向建议 */
    private static final String VARIANT_B = "\n\n【规划视角B·进取线】本稿允许更激进的编排：更早引爆核心冲突、"
            + "允许非线性结构（倒叙/双线/信息差前置），优先戏剧张力。"
            + "基于以上视角，只输出【方向建议】JSON（严禁输出完整章节计划，只给方向）："
            + "{\"structure\":\"本段建议的叙事结构（如倒叙收束/双线并进）\","
            + "\"direction\":\"2-3 句方向概述：本段关键冲突如何推进、节奏如何安排\","
            + "\"risks\":[\"2-3 条本方案最大风险（如连续性压力、跟读成本）\"]}";

    private static final BeanOutputConverter<BranchVerdict> VERDICT_CONVERTER =
            new BeanOutputConverter<>(BranchVerdict.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /** 方向建议结构：分支稿只输出这个（小 JSON，几百 token），完整章节计划由胜出方向再生成一次 */
    public record BranchDirection(String structure, String direction, List<String> risks) {
    }

    private static final BeanOutputConverter<BranchDirection> DIRECTION_CONVERTER =
            new BeanOutputConverter<>(BranchDirection.class,
                    JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build());

    /** 评审输出：winner=A|B（A=稳健线，B=进取线） */
    public record BranchVerdict(String winner, String reason) {
    }

    private final LlmInvokeService llmInvokeService;
    private final LlmGateway llmGateway;
    private final StoryProperties storyProperties;

    /** 分支推演是否启用（story.plan.branches >= 2） */
    public boolean enabled() {
        StoryProperties.PlanProperties plan = storyProperties.getPlan();
        return plan != null && plan.getBranches() >= 2;
    }

    /**
     * 两版方向推演并择优。返回胜者【方向建议】的原始 JSON（调用方据此提示词再生成正式章节计划）；
     * 任一稿可解析即保底返回，全败返回 null
     *
     * @param prompt 调用方装配好的规划 prompt（单段或整批；尾部 chapters schema 指令块会被剥离）
     * @param label  usage 记账 label 前缀（如 "chapter-plan"），分支稿追加 -branchA/-branchB
     */
    public String exploreAndPick(StoryVO storyVO, PromptContext ctx, String prompt,
                                 String label, Map<String, String> usedPromptMap) {
        String branchPrompt = stripPlanSchema(prompt);
        String rawA = tryInvokeDirection(storyVO, ctx, branchPrompt + VARIANT_A, label + "-branchA", usedPromptMap);
        String rawB = tryInvokeDirection(storyVO, ctx, branchPrompt + VARIANT_B, label + "-branchB", usedPromptMap);
        if (rawA == null && rawB == null) {
            return null;
        }
        if (rawA == null) {
            log.info("分支推演：稳健线解析失败，采用进取线方向");
            return rawB;
        }
        if (rawB == null) {
            log.info("分支推演：进取线解析失败，采用稳健线方向");
            return rawA;
        }
        String winner = pick(storyVO, rawA, rawB);
        log.info("分支推演：双稿在册，评审选择 {} 线方向", "B".equals(winner) ? "进取" : "稳健");
        return "B".equals(winner) ? rawB : rawA;
    }

    /**
     * 剥离规划 prompt 中**所有"规划专属"的格式/逐章指令**：显式 schema 是 prompt 中最强的格式信号，
     * 带着它追加"只输出方向"等于给模型两个互相矛盾的输出指令，模型必然选 schema；
     * 无标记时（如单测的简化 prompt）原样返回。
     *
     * <p><b>2026-10-02 修正</b>：原先只从 {@code PLAN_SCHEMA_MARKER} 截尾，但
     * 「8.2 章级主线推进要求」与「【章级主线推进】块」都在 schema **之前**，因此会残留。
     * 残留后果实测很直接——那一块是"第16章：… / 第17章：…"的**逐章清单**，
     * 比 schema 更具体，模型于是交回**章节计划数组**而不是分支推演要的方向对象：
     * 日志里 4 条 {@code BeanOutputConverter} 解析失败的原始输出正是带 mainLineAdvance 的章节计划。
     *
     * <p><b>为什么逐段精确删除、而不是"取最早标记一截了之"</b>：8.2 位于编号要求列表里，
     * 若从它截尾，会连它之后的【悬念推进锚】【密度/伏笔/地点反馈】等**仍然有用的块**一起切掉，
     * 反而削弱分支方向建议的信息量。故只精确移除这两段，其余原样保留。
     */
    static String stripPlanSchema(String prompt) {
        if (prompt == null) {
            return null;
        }
        String out = removeLine(prompt, ChapterPlanPromptService.MAINLINE_REQUIREMENT_MARKER);
        out = removeSection(out, ChapterPlanPromptService.MAINLINE_BLOCK_MARKER);
        int idx = out.lastIndexOf(ChapterPlanPromptService.PLAN_SCHEMA_MARKER);
        return idx > 0 ? out.substring(0, idx) : out;
    }

    /** 删除标记所在的**整行**（自标记起到行尾）：用于编号要求里的单行指令 */
    private static String removeLine(String text, String marker) {
        int idx = text.lastIndexOf(marker);
        if (idx <= 0) {
            return text;
        }
        int end = text.indexOf('\n', idx);
        return text.substring(0, idx) + (end < 0 ? "" : text.substring(end + 1));
    }

    /** 删除以标记开头的**整节**（自标记起到下一个空行分段）：用于「【…】」形式的注入块 */
    private static String removeSection(String text, String marker) {
        int idx = text.lastIndexOf(marker);
        if (idx <= 0) {
            return text;
        }
        int end = text.indexOf("\n\n", idx);
        return text.substring(0, idx) + (end < 0 ? "" : text.substring(end + 2));
    }

    /** 单稿方向调用 + 可解析性校验（只要求 direction/structure/risks 字段可解析，多为小 JSON 不会截断）；失败返回 null */
    private String tryInvokeDirection(StoryVO storyVO, PromptContext ctx, String prompt,
                                      String label, Map<String, String> usedPromptMap) {
        try {
            String raw = llmInvokeService.invoke(storyVO, cn.novel.yonren.types.enums.PromptScene.CHAPTER_PLAN,
                    ctx, prompt, usedPromptMap);
            BranchDirection direction = JsonParseFallback.parse(raw, DIRECTION_CONVERTER::convert);
            return direction == null ? null : raw;
        } catch (Exception e) {
            log.warn("分支方向调用失败（{}）：{}", label, e.getMessage());
            return null;
        }
    }

    /** 双稿评审择优：只评方向可行性/节奏与风险自评可信度，不评长短；评审失败默认稳健线 A */
    private String pick(StoryVO storyVO, String rawA, String rawB) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("你是小说策划主编。下面是同一批章节的两版【方向建议】（JSON，各自含 structure/direction/risks），判定哪版更好。")
                    .append("只依据：1) 方向可行性（推剧情是否成立、与前后卷衔接压力）；2) 节奏编排（是否连续高潮、是否注水、")
                    .append("过渡与爆发交替）；3) risks 风险自评的可信度与严重度。")
                    .append("严禁以篇幅长短、细节多少作为评判依据。只输出 JSON。")
                    .append("\n\n【方向A（稳健线）】\n").append(rawA)
                    .append("\n\n【方向B（进取线）】\n").append(rawB)
                    .append("\n\n请严格按照以下 JSON 格式输出：\n").append(VERDICT_CONVERTER.getFormat());
            String raw = llmGateway.complete(storyVO.getModule(), LlmCall.builder()
                    .userPrompt(sb.toString())
                    .label("chapter-judge-plan-branch")
                    .scene(ModelScene.CHAPTER_JUDGE)
                    // 评审输出上限走 scene-models.chapter-judge.max-tokens（与章节评审同场景同配置，单一配置源）
                    .build());
            BranchVerdict verdict = VERDICT_CONVERTER.convert(raw);
            if (verdict == null || StringUtils.isBlank(verdict.winner())) {
                log.warn("分支评审输出非法（输出 {} 字），默认采用稳健线 A：{}",
                        org.apache.commons.lang3.StringUtils.length(raw),
                        org.apache.commons.lang3.StringUtils.abbreviate(raw, 120));
                return "A";
            }
            if ("B".equalsIgnoreCase(verdict.winner())) {
                return "B";
            }
            return "A";
        } catch (Exception e) {
            log.warn("分支评审失败，默认采用稳健线 A：{}", e.getMessage());
            return "A";
        }
    }
}
