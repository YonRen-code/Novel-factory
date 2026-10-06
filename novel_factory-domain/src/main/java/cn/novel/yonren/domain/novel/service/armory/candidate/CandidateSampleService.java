package cn.novel.yonren.domain.novel.service.armory.candidate;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterContentEntity;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选选优样本落盘（复盘数据源）：低置信通过章的候选对比全过程——
 * 触发分级、挑战者机械得分、评审胜者与理由、最终采纳结果、双稿全文
 * 以单行 JSON 追加到 memory/candidate-samples.jsonl。
 * 供离线 harness 复盘"人工盲选胜率 >60%"验收指标与评审 prompt 校准。
 * 观测性文件：全程自吞，绝不反噬生成流程
 */
@Service
@Slf4j
public class CandidateSampleService {

    @Resource
    private IStoryRepository storyRepository;

    public void record(Path storyDir, int chapterNo, String triggerGrade, String outcome,
                       ChapterContentEntity incumbent, ChapterContentEntity challenger,
                       String judgeWinner, String judgeReason, String judgeRaw) {
        if (storyDir == null) {
            return;
        }
        try {
            Map<String, Object> sample = new HashMap<>();
            sample.put("ts", LocalDateTime.now().toString());
            sample.put("chapterNo", chapterNo);
            sample.put("triggerGrade", triggerGrade);
            sample.put("outcome", outcome);
            sample.put("incumbent", incumbent);
            sample.put("challenger", challenger);
            sample.put("judgeWinner", judgeWinner);
            sample.put("judgeReason", judgeReason);
            // 评审原始输出（截断留痕）：invalid 轮（空响应/格式非法）也能复盘是模型格式问题还是真空响应
            sample.put("judgeRaw", judgeRaw);
            storyRepository.appendCandidateSample(storyDir, JSON.toJSONString(sample));
            log.info("第 {} 章候选选优样本已落盘（outcome={}，judge={}）", chapterNo, outcome, judgeWinner);
        } catch (Exception e) {
            log.warn("第 {} 章候选选优样本落盘失败，忽略", chapterNo, e);
        }
    }

    /**
     * 候选链路统计（观测层用）。判据（2026-09-16，三章实测后补）：
     * 触发率 = 触发次数/章数（历史基线 19%，三章实测 100%）；**采纳率 = 挑战者被采纳/触发**
     * （历史基线 48%，三章实测 67%）——判定"白烧"的标准是采纳率而不是触发率，
     * 只有"持续高触发 + 低采纳"同时出现才值得动触发开关
     *
     * <p>**按章去重（2026-10-03）**：重跑批次时同一章会在 jsonl 里追加多行，
     * 统计口径取**每章最后一行**（文件追加序 = 批次时序，最后一行即最新状态）——
     * 否则旧批次的行会把触发率推到 100% 以上（老书重跑实测 105%/133%）。
     */
    public CandidateStats readStats(Path storyDir) {
        try {
            Map<Integer, String> latestByChapter = new LinkedHashMap<>();
            int syntheticKey = Integer.MIN_VALUE;
            for (String line : storyRepository.readCandidateSampleLines(storyDir)) {
                try {
                    JSONObject row = JSON.parseObject(line);
                    String outcome = row.getString("outcome");
                    if (outcome == null) continue;
                    Integer chapterNo = row.getInteger("chapterNo");
                    // 无章号的旧格式行不参与去重（每行自成一条，保持旧行为）
                    latestByChapter.put(chapterNo != null ? chapterNo : syntheticKey++, outcome);
                } catch (RuntimeException ignored) {
                    // 允许尾部半行/旧格式，不影响其余统计（与 readCandidateOutcomes 同口径）
                }
            }
            long triggered = latestByChapter.values().stream().filter(o -> !"error".equals(o)).count();
            long adopted = latestByChapter.values().stream().filter(o -> "challenger-adopted".equals(o)).count();
            return new CandidateStats(triggered, adopted);
        } catch (Exception e) {
            log.warn("候选选优样本读取失败，观测层跳过该指标", e);
            return null;
        }
    }

    /**
     * @param triggered 触发次数（排除 error 行——那是一次都没真正跑起来的触发）
     * @param adopted   挑战者被采纳次数
     */
    public record CandidateStats(long triggered, long adopted) {
    }
}
