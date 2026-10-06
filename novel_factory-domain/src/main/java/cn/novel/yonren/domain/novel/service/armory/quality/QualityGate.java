package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.AuditResultEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterSummaryEntity;
import cn.novel.yonren.domain.novel.model.entity.StyleStatEntity;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.armory.audit.AuditSampleService;
import cn.novel.yonren.domain.novel.service.armory.audit.ChapterAuditService;
import cn.novel.yonren.domain.novel.service.armory.memory.ChapterMemoryService;
import cn.novel.yonren.domain.novel.service.armory.memory.ConsistencyIndexService;
import cn.novel.yonren.domain.novel.service.armory.revise.ChapterReviseService;
import cn.novel.yonren.types.enums.GenreTypeVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 单章质量门：审校 → 修订的决策链。
 * 审校是质量门：彻底失败抛异常终止本批（绝不等价于零问题）；
 * 修订失败则保留原稿并把 BLOCKING 问题作为质量债返回（审校结论不因修订失败而丢失）。
 * 从 GenerateChapterContentNode 自然拆出（D8），逐行等价
 */
@Service
@Slf4j
public class QualityGate {

    @Resource
    private ChapterAuditService chapterAuditService;

    @Resource
    private ChapterReviseService chapterReviseService;

    @Resource
    private ChapterMemoryService chapterMemoryService;

    @Resource
    private StoryProperties storyProperties;

    @Resource
    private AuditSampleService auditSampleService;

    @Resource
    private ConsistencyIndexService consistencyIndexService;

    public boolean auditEnabled() {
        return storyProperties != null && storyProperties.getAudit().isEnabled();
    }

    /**
     * 审校 → 修订（若启用）→ 修订稿复审的闭环自愈链（四期）：生成正文后、风格统计合并前调用。
     * 首审发现 BLOCKING 且修订启用时，逐轮"修订 → 采纳闸门 → 复审"，复审干净即闭环；
     * 轮次（story.revise.max-attempts）耗尽仍未闭环时，把最终 BLOCKING 记为质量债并落失败样本。
     * 审校/复审硬失败照旧抛 AppException 终止本批；修订调用异常保留审校结论（不重试省 token）。
     * 六期扩展：伏笔保密边界（禁泄关键词机械兜底 + 审校判变相泄露）、大纲偏离检测
     * （关键事件覆盖率过低时给审校注入加审预警），并通过 GateResult 暴露通过质量分级
     * （低置信通过供候选选优触发、CLEAN_PASS 供文风指纹库入库）。
     * 七期扩展：内容密度检测（有效对话句数<25时给审校注入加审预警，WARN 语义不直接 BLOCKING）。
     *
     * @return 质量门结论：未修复 BLOCKING 清单 + 通过质量分级 + MINOR 残留/修订轮次
     */
    public GateResult auditAndReviseIfEnabled(ArmoryCommandEntity requestParameter,
                                              ChapterPlanItemEntity item,
                                              ChapterContentEntity chapterContent,
                                              List<ChapterSummaryEntity> summaries,
                                              StyleStatEntity styleStat,
                                              int globalNo,
                                              Path storyDir) {
        // 段落结构兜底：写手偶尔把整章当成单个字符串吐出（实测 4/10 章换行数为 0，
        // 单章 3000~4600 字挤成一段）。必须在任何按行统计之前重排，否则对白行占比会被误算为 1.0
        // （ratioOf = 含引号行/非空行，整章仅 1 行且含引号 ⇒ 恒 1.0），段落密度审校也无从下手。
        // 重排是纯机械动作、不改一个字；异常信号另行记 MINOR 回灌规划层。
        List<ChapterIssueEntity> structureIssues = List.of();
        if (chapterContent != null) {
            String raw = chapterContent.getContent();
            structureIssues = ParagraphStructurePolicy.checkStructureCollapse(
                    raw, ParagraphStructurePolicy.paragraphLimit(raw));
            String reparagraphed = ParagraphStructurePolicy.reparagraph(raw);
            if (reparagraphed != raw) {
                log.warn("第 {} 章段落结构坍缩，已机械重排：{} 字 / {} 段 → {} 段（不改动任何文字）",
                        globalNo, raw.length(), raw.split("\n").length,
                        reparagraphed.split("\n\n", -1).length);
                chapterContent.setContent(reparagraphed);
            }
        }
        // 字数不足不再是 BLOCKING：拒收短章只会逼模型注水；改为仅告警，
        // 密度信号由 ChapterWorker 机械记入摘要（validChars/keyEventCount），回灌规划层治本
        if (storyProperties.getAudit().isEnforceMinimumChapterLength()
                && chapterContent != null && !ChapterLengthPolicy.meetsMinimum(chapterContent.getContent())) {
            log.warn("第 {} 章未达到 1500 字参考线，实际有效字数 {}（不阻塞，密度信号将回灌规划层）", globalNo,
                    ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent()));
        }
        // 上沿同口径告警：字数超标不是"写得多"，而是**关键事件数没变、水变多了**——
        // 实测第 20 章 4170 字 / 5 个关键事件，对照第 19 章 2205 字 / 5 个关键事件。
        // 与下沿一样只告警不阻塞，信号回灌规划层（见 ChapterPlanPromptService 的注水反馈）。
        // 刻意**不豁免过渡章**：下沿豁免过渡章是因为它本就该短；写得比常规章还长的过渡章
        // 恰恰是最典型的注水形态。
        if (chapterContent != null && ChapterLengthPolicy.exceedsReference(chapterContent.getContent())) {
            log.warn("第 {} 章超出 {} 字参考上沿，实际有效字数 {}（不阻塞，注水信号将回灌规划层）", globalNo,
                    ChapterLengthPolicy.MAXIMUM_REFERENCE_CHARACTERS,
                    ChapterLengthPolicy.effectiveCharacterCount(chapterContent.getContent()));
        }
        // 机械文风门禁（零 LLM 调用）：万能副词密度/眼神套话/身体套话/章末升华已降为 MINOR
        //（只记录 + 回灌规划层，不触发修订），章节编号元信息泄露仍为 BLOCKING 进修订闭环。
        // 分层依据见 StyleViolationPolicy 类注释（162 章实测：原口径 53 条 BLOCKING 人工核验后 0 条成立）
        List<ChapterIssueEntity> styleIssues = chapterContent == null ? List.of()
                : StyleViolationPolicy.check(chapterContent.getContent());
        if (!styleIssues.isEmpty()) {
            log.warn("第 {} 章机械文风门禁命中 {} 条（BLOCKING {} / MINOR {}）：{}", globalNo,
                    styleIssues.size(), blockingOf(styleIssues).size(), nonBlockingOf(styleIssues).size(),
                    styleIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        // 伏笔保密边界（机械兜底）：已埋未揭伏笔的谜底关键词逐字命中即 BLOCKING，
        // 揭示章（计划关键事件带回收/揭示意图）在清单构建侧豁免；prompt 块交审校判"变相泄露"
        ChapterMemoryService.SecrecyGuard secrecyGuard = chapterMemoryService.buildSecrecyGuard(summaries, item);
        List<ChapterIssueEntity> secrecyIssues = (chapterContent == null || secrecyGuard.isEmpty()) ? List.of()
                : SecrecyViolationPolicy.check(chapterContent.getContent(), secrecyGuard.keywords());
        if (!secrecyIssues.isEmpty()) {
            log.warn("第 {} 章禁泄清单命中 {} 条：{}", globalNo, secrecyIssues.size(),
                    secrecyIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        // 大纲偏离检测（机械预检）：关键事件词面覆盖率过低时给审校注入加审预警（WARN 语义，不直接 BLOCKING）
        String adherenceHint = chapterContent == null ? null
                : PlanAdherencePolicy.renderLowCoverageHint(item.getKeyEvents(), chapterContent.getContent());
        // 内容密度检测（机械预检）：有效对话句数<25时给审校注入加审预警（WARN 语义，不直接 BLOCKING）
        int dialogueCount = chapterContent == null ? 0 : ContentDensityPolicy.countDialogue(chapterContent.getContent());
        String densityHint = ContentDensityPolicy.renderLowDensityHint(dialogueCount);
        if (dialogueCount < ContentDensityPolicy.MIN_DIALOGUE_COUNT) {
            log.warn("第 {} 章内容密度预警：有效对话仅 {} 句，低于最低要求 {} 句", globalNo,
                    dialogueCount, ContentDensityPolicy.MIN_DIALOGUE_COUNT);
        }
        // 章节编号元信息泄露检测（机械预检）：正文中出现"第X章""本章/上一章"等作者层面元信息时给审校注入加审预警
        // 同时 StyleViolationPolicy.check 会将其标记为 BLOCKING issue 进入修订闭环，双重保险
        String chapterRefEvidence = chapterContent == null ? null
                : StyleViolationPolicy.checkChapterReference(chapterContent.getContent());
        String chapterRefHint = StyleViolationPolicy.renderChapterRefAuditHint(chapterRefEvidence);
        if (chapterRefEvidence != null) {
            log.warn("第 {} 章章节编号元信息泄露预警：检测到 {}，审校注入加审预警并进入修订闭环",
                    globalNo, chapterRefEvidence);
        }

        // 金手指机制门禁（机械兜底，由"只记债不阻塞"改为真 BLOCKING 进修订闭环）：
        // 超期未使用 / 机制原理重复描述。两条都是 (摘要历史 + 本章正文) 的纯函数 ⇒ 修订稿可重算，
        // 修订真的清掉问题即闭环，不会永远挂债。
        // 必须在摘要落盘之前调用：本章是否"已使用"靠回读正文判定（mentionsMechanism），否则会误判
        List<ChapterIssueEntity> mechanismIssues = chapterContent == null ? List.of()
                : consistencyIndexService.mechanismIssues(
                        requestParameter.getStoryVO(), summaries, globalNo, chapterContent.getContent());
        if (!mechanismIssues.isEmpty()) {
            log.warn("第 {} 章机制门禁命中 {} 条：{}", globalNo, mechanismIssues.size(),
                    mechanismIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        // 章节标题唯一性（机械，MINOR）：长篇里"新的开始""风暴前夕"这类模板标题会反复出现。
        // 刻意不进 BLOCKING——修订是整章重写，为改一个标题重写整章既不成比例也不保证会改名；
        // 根治在规划层（ChapterTitlePolicy.renderUsedTitles 已把已用标题注入规划 prompt）
        List<ChapterIssueEntity> titleIssues = (chapterContent == null
                || StringUtils.isBlank(chapterContent.getTitle()))
                ? List.of() : ChapterTitlePolicy.check(chapterContent.getTitle(), summaries);
        if (!titleIssues.isEmpty()) {
            log.warn("第 {} 章标题与既有章节重复：{}", globalNo, chapterContent.getTitle());
        }
        List<ChapterIssueEntity> mechanicalIssues = new java.util.ArrayList<>(styleIssues);
        mechanicalIssues.addAll(secrecyIssues);
        mechanicalIssues.addAll(mechanismIssues);
        mechanicalIssues.addAll(titleIssues);
        // 段落结构坍缩（机械，）：正文被写成一个巨型文本块。注意必须用**重排前**的原文
        // 做判定，重排后段落数已被补齐，再判就永远不命中。MINOR 落债回灌，不进修订
        // （根因在写手侧，为分段重写整章不成比例；兜底重排已在方法入口完成）
        if (!structureIssues.isEmpty()) {
            log.warn("第 {} 章段落结构坍缩：{}", globalNo,
                    structureIssues.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(structureIssues);
        // 对白格式坍缩检测（机械，P1）：整章零对白引号=读者无法区分叙述与发言（典型成因：
        // 写手把"纯文本禁令"过度泛化到对白，阅读实测整批丢引号）。MINOR 落债回灌，
        // 不进修订——根因已在生成 prompt 侧修复（对白引号豁免），此处是兜底观测与回灌
        List<ChapterIssueEntity> dialogueIssues = chapterContent == null ? List.of()
                : DialogueRatioPolicy.checkFormatCollapse(chapterContent.getContent(),
                        item.getChapterType() == null ? null : item.getChapterType().getCode());
        if (!dialogueIssues.isEmpty()) {
            log.warn("第 {} 章对白格式坍缩：{}", globalNo,
                    dialogueIssues.stream().map(ChapterIssueEntity::getDescription).toList());
        }
        mechanicalIssues.addAll(dialogueIssues);
        // 括号包对话检测（机械，P1，）：台词写成（……）而非「……」。
        // 它躲得过上面的坍缩检测（文里仍有其它引号），却会让对白占比与轮次密度双双虚高——
        // 被括号包裹的句子两个统计都不计入，等于把对话坍缩伪装成达标。MINOR 落债回灌
        List<ChapterIssueEntity> bracketIssues = chapterContent == null ? List.of()
                : DialogueRatioPolicy.checkBracketDialogue(chapterContent.getContent(),
                        item.getChapterType() == null ? null : item.getChapterType().getCode());
        if (!bracketIssues.isEmpty()) {
            log.warn("第 {} 章疑似括号包对话：{}", globalNo,
                    bracketIssues.stream().map(ChapterIssueEntity::getEvidence).toList());
        }
        mechanicalIssues.addAll(bracketIssues);
        // 严重度分层：机械问题按档分流——BLOCKING 进修订闭环，
        // MINOR（文风程度问题）只记录、不触发修订也不触发候选，见 GateResult 类注释
        List<ChapterIssueEntity> mechanicalMinor = nonBlockingOf(mechanicalIssues);
        List<ChapterIssueEntity> mechanicalBlocking = blockingOf(mechanicalIssues);
        if (!auditEnabled() && mechanicalBlocking.isEmpty()) {
            return GateResult.of(List.of(), 0, mechanicalMinor, 0);
        }

        String ledgerPrompt = chapterMemoryService.renderLedgerPrompt(summaries);
        // 账本末态红线置顶：位置/持有物/修为的对齐锚点，审校比对与修订改写方向共用
        //（治连续 9 章 consistency 债复发的"正文开头 vs 账本末态"衔接错位）
        String edgeState = chapterMemoryService.renderLedgerEdgeState(summaries);
        if (StringUtils.isNotBlank(edgeState)) {
            ledgerPrompt = edgeState + (ledgerPrompt == null ? "" : "\n\n" + ledgerPrompt);
        }
        // 审校比对清单：剔除已熔断冻结的未填伏笔——未填不再构成审校的回收义务，且封顶防百章后线性膨胀
        String foreshadowing = String.join("\n", chapterMemoryService.buildAuditForeshadowList(summaries));
        // 审校输入 guard：禁泄清单（LLM 判变相泄露）+ 计划覆盖预警 + 内容密度预警 + 章节编号元信息预警（加审）
        // + 时序锚：把主角年龄摆进审校视野——否则"婴儿写数论证明"这类
        // 能力越界在审校侧完全不可见（此前 26 章 10 条 issue 全是位置/持有物类琐碎项）
        String registerContract = renderRegisterContract(requestParameter);
        // 时序锚：摘要锚优先；新书首段（无摘要）回退设定兜底锚——否则审校年龄判据因 guard 无锚而不触发
        String timeAnchor = ConsistencyIndexService.renderTimeAnchor(summaries);
        if (StringUtils.isBlank(timeAnchor)) {
            var storyCtx = requestParameter == null ? null : requestParameter.getStoryContextEntity();
            if (storyCtx != null) {
                timeAnchor = ConsistencyIndexService.renderSettingsAgeAnchorIfNoSummaries(summaries,
                        storyCtx.getWorldSetting(), storyCtx.getProtagonist(), storyCtx.getOutline());
            }
        }
        String auditGuard = joinBlocks(
                joinBlocks(joinBlocks(joinBlocks(secrecyGuard.promptBlock(), adherenceHint), densityHint), chapterRefHint),
                joinBlocks(registerContract, timeAnchor));
        // 修订输入保留禁泄清单与语域契约（防修订环节修好事实、却引入时代错位或人物失语）
        String reviseForeshadowing = joinBlocks(joinBlocks(foreshadowing, secrecyGuard.promptBlock()), registerContract);

        // 审校调用在 try 之外：解析/重试均失败时 AppException 直接终止本批
        List<ChapterIssueEntity> currentBlocking = new java.util.ArrayList<>(mechanicalBlocking);
        int minorCount = 0;
        List<ChapterIssueEntity> semanticMinors = List.of();
        int reviseRounds = 0;
        // 修订验证是否"根本没跑成"：为真时 currentBlocking 的语义是
        // 【未验证】而非【确认未修复】——内容侧偏向不变（仍算未通过、不放行未验证的稿），
        // 但上层不得把它记入质量债，观测侧单独计数（见 FixVerification / BatchHealthService）
        boolean auditVerifyDegraded = false;
        if (auditEnabled()) {
            AuditResultEntity auditResult = chapterAuditService.audit(
                    requestParameter.getStoryVO(), item, chapterContent.getContent(),
                    ledgerPrompt, foreshadowing, styleStat, globalNo, auditGuard);
            currentBlocking.addAll(blockingOf(auditResult));
            minorCount = minorOf(auditResult);
            // P2 审校反馈回灌：语义 MINOR 清单随 GateResult 传出（验证化复审不产出新 MINOR，以首审为准）
            semanticMinors = semanticMinorsOf(auditResult);
        }
        if (currentBlocking.isEmpty()) {
            log.info("第 {} 章审校完成，仅 MINOR（叙事 {} 条 / 文风 {} 条）或不含 BLOCKING 问题，不触发修订",
                    globalNo, minorCount, mechanicalMinor.size());
            return GateResult.of(List.of(), minorCount, mechanicalMinor, 0, semanticMinors, auditVerifyDegraded);
        }

        log.warn("第 {} 章审校发现 BLOCKING 问题 {} 条，准备修订", globalNo, currentBlocking.size());

        if (storyProperties.getRevise() == null || !storyProperties.getRevise().isEnabled()) {
            log.info("第 {} 章修订未启用，保留原稿", globalNo);
            auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                    currentBlocking, 0, ledgerPrompt, foreshadowing);
            return GateResult.of(currentBlocking, minorCount, mechanicalMinor, 0, semanticMinors, auditVerifyDegraded);
        }

        int maxAttempts = Math.max(1, storyProperties.getRevise().getMaxAttempts());
        // 上一轮采纳闸门拒绝原因（方案2回传）：让次轮修订知道为何被拒，定向避免而非盲改
        String previousRejectionReason = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            reviseRounds = attempt;
            ChapterReviseService.ReviseResult reviseResult;
            try {
                reviseResult = chapterReviseService.revise(
                        requestParameter.getStoryVO(), item, chapterContent.getContent(),
                        currentBlocking, ledgerPrompt, reviseForeshadowing, styleStat, globalNo,
                        previousRejectionReason);
            } catch (Exception e) {
                // 修订失败保留审校结论：BLOCKING 记为质量债，随后续章节记忆回灌
                log.warn("第 {} 章第 {}/{} 轮修订流程异常，保留当前稿并将 BLOCKING 问题记为质量债",
                        globalNo, attempt, maxAttempts, e);
                auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                        currentBlocking, attempt, ledgerPrompt, foreshadowing);
                return GateResult.of(currentBlocking, minorCount, mechanicalMinor, attempt, semanticMinors, auditVerifyDegraded);
            }

            if (reviseResult.revisedContent() != null && reviseResult.decision().isAccepted()) {
                ChapterContentEntity revised = reviseResult.revisedContent();
                // 标题非空才覆盖：定向补丁刻意不带标题（它只动正文局部），
                // 无条件赋值会把原本完好的标题清成 null
                if (StringUtils.isNotBlank(revised.getTitle())) {
                    chapterContent.setTitle(revised.getTitle());
                }
                chapterContent.setContent(revised.getContent());
                log.info("第 {} 章第 {}/{} 轮修订已采纳，原因：{}", globalNo, attempt, maxAttempts,
                        reviseResult.decision().getReason());

                // 修订稿复审：本方法不吞异常，但 verifyFixes 自身对"验证没跑成"做降级标记而非抛出，
                // 故此处只需接住结果（原注释"硬失败照旧 AppException 终止本批"与实现不符，已修正）
                // 机械门禁重扫：修订稿带套话/引入泄底都不算闭环（字数不再进 BLOCKING，见首审处说明）
                List<ChapterIssueEntity> rescan = new java.util.ArrayList<>(
                        StyleViolationPolicy.check(chapterContent.getContent()));
                if (!secrecyGuard.isEmpty()) {
                    rescan.addAll(SecrecyViolationPolicy.check(chapterContent.getContent(), secrecyGuard.keywords()));
                }
                // 机制门禁重算：修订稿若真的用上了金手指 / 删掉了原理段，本次即闭环
                rescan.addAll(consistencyIndexService.mechanismIssues(
                        requestParameter.getStoryVO(), summaries, globalNo, chapterContent.getContent()));
                // 标题唯一性重算：修订可能改了标题，留旧结论会误导（改了名即闭环）
                if (StringUtils.isNotBlank(chapterContent.getTitle())) {
                    rescan.addAll(ChapterTitlePolicy.check(chapterContent.getTitle(), summaries));
                }
                // 降档项以最终稿重扫结果为准：修订可能清掉或改变文风问题，留旧版记录会误导观测层
                mechanicalMinor = nonBlockingOf(rescan);
                List<ChapterIssueEntity> newBlocking = new java.util.ArrayList<>(blockingOf(rescan));
                if (auditEnabled()) {
                    // A3 复审验证化（docs/enhancement-plan.md）：复审不再全章重审——开放任务方差大，
                    // 二轮翻案即"修订拉锯"；改为逐条验证上一轮 BLOCKING 是否已修复（未确认已修复=仍未修复）。
                    // 机械门禁重扫（上方 rescan）照旧兜住修订稿新引入的硬伤。
                    // 观测口径变化：叙事 MINOR 计数保留首审值——验证任务不做全章扫描，不产出新 MINOR
                    ChapterAuditService.FixVerification verification = chapterAuditService.verifyFixes(
                            requestParameter.getStoryVO(), item, chapterContent.getContent(),
                            currentBlocking, ledgerPrompt, styleStat, globalNo);
                    // 验证没跑成 ≠ 没修好：照旧保守对待（unfixed 原样带回），但标记为【未验证】，
                    // 由上层决定不落债、观测侧单独计数
                    auditVerifyDegraded = verification.degraded();
                    newBlocking.addAll(verification.unfixed());
                }
                if (newBlocking.isEmpty()) {
                    log.info("第 {} 章第 {}/{} 轮复审通过，闭环完成", globalNo, attempt, maxAttempts);
                    return GateResult.of(List.of(), minorCount, mechanicalMinor, attempt, semanticMinors, auditVerifyDegraded);
                }
                currentBlocking = newBlocking;
                log.warn("第 {} 章第 {}/{} 轮复审仍发现 BLOCKING 问题 {} 条，继续修订",
                        globalNo, attempt, maxAttempts, currentBlocking.size());
                previousRejectionReason = "修订稿已通过采纳闸门，但复审仍发现 BLOCKING 问题："
                        + reviseResult.decision().getReason();
            } else {
                log.info("第 {} 章第 {}/{} 轮修订未采纳，保留当前稿，原因：{}", globalNo, attempt, maxAttempts,
                        reviseResult.decision().getReason());
                previousRejectionReason = reviseResult.decision().getReason();
            }
        }

        log.warn("第 {} 章修订 {} 轮后仍有 BLOCKING 问题 {} 条（结论交由上层处置：主路径记为质量债，"
                        + "候选挑战者路径会连同候选稿一起回退丢弃）",
                globalNo, maxAttempts, currentBlocking.size());
        auditSampleService.record(storyDir, globalNo, item, chapterContent.getContent(),
                currentBlocking, maxAttempts, ledgerPrompt, foreshadowing);
        return GateResult.of(currentBlocking, minorCount, mechanicalMinor, maxAttempts, semanticMinors, auditVerifyDegraded);
    }

    /** 审校与修订共用的语域检查契约；只给 fantasy 附加仙侠词表，其他题材保持中性。 */
    private static String renderRegisterContract(ArmoryCommandEntity request) {
        if (request == null || request.getStoryContextEntity() == null) {
            return null;
        }
        var context = request.getStoryContextEntity();
        GenreTypeVO genre = GenreTypeVO.match(context.getTheme(), context.getStyle());
        StringBuilder contract = new StringBuilder("【语言与时代语域检查】检查叙述和对白是否符合故事时代、世界设定及人物的年龄、身份、教育、职业与认知边界；")
                .append("把无来源的学术报告腔、管理总结腔、互联网黑话、时代错位技术词或儿童不可能掌握的成熟抽象表达列为 aesthetic/character 问题。")
                .append("修订时必须改写为角色可观察的动作、物件、生活经验和口语，不得只替换同义词。");
        if (genre == GenreTypeVO.FANTASY) {
            contract.append("本题材为玄幻/仙侠：客观世界优先使用既有设定词；程序员术语只能在角色设定明确支持时作短促心理类比，不得充当世界事实。");
        }
        return contract.toString();
    }

    /** 拼接机械预警块（忽略空段，非空段以空行分隔）；全空返回 null */
    private static String joinBlocks(String first, String second) {
        boolean hasFirst = StringUtils.isNotBlank(first);
        boolean hasSecond = StringUtils.isNotBlank(second);
        if (!hasFirst && !hasSecond) {
            return null;
        }
        if (hasFirst && hasSecond) {
            return first + "\n\n" + second;
        }
        return hasFirst ? first : second;
    }

    /** 语义 MINOR 清单（P2 审校反馈回灌的数据源）：与 minorOf 同口径，但保留完整 issue 供渲染 */
    private List<ChapterIssueEntity> semanticMinorsOf(AuditResultEntity auditResult) {
        if (auditResult == null || auditResult.getIssues() == null) {
            return List.of();
        }
        return auditResult.getIssues().stream()
                .filter(issue -> issue != null && "MINOR".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }

    private int minorOf(AuditResultEntity auditResult) {
        if (auditResult == null || auditResult.getIssues() == null) {
            return 0;
        }
        return (int) auditResult.getIssues().stream()
                .filter(issue -> issue != null && "MINOR".equalsIgnoreCase(issue.getSeverity()))
                .count();
    }

    private List<ChapterIssueEntity> blockingOf(AuditResultEntity auditResult) {
        if (auditResult.getIssues() == null || auditResult.getIssues().isEmpty()) {
            return List.of();
        }
        return auditResult.getIssues().stream()
                .filter(issue -> "BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }

    /**
     * 按严重度筛选：BLOCKING 档（进修订闭环）。
     * 供审校结论与机械结论共用——机械门禁已分层，旧口径「一律 BLOCKING」不再成立。
     */
    private static List<ChapterIssueEntity> blockingOf(List<ChapterIssueEntity> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return issues.stream()
                .filter(issue -> issue != null && "BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }

    /**
     * 按严重度筛选：非 BLOCKING 档（机械文风程度问题）。
     * 这些只落质量债供观测统计，不进修订闭环、不计入 grade、不触发候选选优。
     */
    private static List<ChapterIssueEntity> nonBlockingOf(List<ChapterIssueEntity> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return issues.stream()
                .filter(issue -> issue != null && !"BLOCKING".equalsIgnoreCase(issue.getSeverity()))
                .toList();
    }
}
