package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审校失败样本落盘（四期闭环自愈的"回流校准集"环节）：
 * 修订循环耗尽后仍未解决的 BLOCKING 问题，连同最终正文/计划/审校输入（账本与伏笔清单）
 * 以单行 JSON 追加到 memory/audit-runtime-samples.jsonl，供校准 harness 离线复检。
 * 观测性文件：全程自吞，绝不反噬生成流程
 */
@Service
@Slf4j
public class AuditSampleService {

    @Resource
    private IStoryRepository storyRepository;

    public void record(Path storyDir, int chapterNo, ChapterPlanItemEntity item, String finalContent,
                       List<ChapterIssueEntity> unresolved, int attemptCount,
                       String ledgerPrompt, String foreshadowing) {
        if (storyDir == null || unresolved == null || unresolved.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> sample = new HashMap<>();
            sample.put("ts", LocalDateTime.now().toString());
            sample.put("chapterNo", chapterNo);
            sample.put("attempts", attemptCount);
            sample.put("plan", item);
            sample.put("content", finalContent);
            sample.put("ledgerPrompt", ledgerPrompt);
            sample.put("foreshadowing", foreshadowing);
            sample.put("issues", unresolved);
            storyRepository.appendAuditSample(storyDir, JSON.toJSONString(sample));
            log.info("第 {} 章审校失败样本已落盘（{} 条 BLOCKING，修订 {} 轮）",
                    chapterNo, unresolved.size(), attemptCount);
        } catch (Exception e) {
            log.warn("第 {} 章审校失败样本落盘失败，忽略", chapterNo, e);
        }
    }
}
