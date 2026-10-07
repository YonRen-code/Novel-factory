package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SecrecyViolationPolicy {

    /** 扫描正文，对禁泄关键词逐字计数；任一命中即产出一条 BLOCKING issue（evidence 为"关键词×次数"清单） */
    public static List<ChapterIssueEntity> check(String content, List<String> keywords) {
        if (StringUtils.isBlank(content) || keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String keyword : keywords) {
            if (StringUtils.isBlank(keyword)) {
                continue;
            }
            int count = 0;
            int idx = 0;
            while ((idx = content.indexOf(keyword, idx)) >= 0) {
                count++;
                idx += keyword.length();
            }
            if (count > 0) {
                hits.put(keyword, count);
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        String evidence = hits.entrySet().stream()
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining("、"));
        List<ChapterIssueEntity> issues = new ArrayList<>();
        ChapterIssueEntity issue = new ChapterIssueEntity();
        issue.setDimension("foreshadow");
        issue.setSeverity("BLOCKING");
        issue.setDescription("本章禁泄清单命中 " + hits.size() + " 个谜底关键词（伏笔答案提前泄露）");
        issue.setEvidence(evidence);
        issue.setSuggestion("删除或改写相关段落：只强化悬念本身，严禁写出/暗示/改述透露谜底；"
                + "若本章计划正是揭示章，核对计划关键事件是否包含该伏笔的回收");
        issues.add(issue);
        return issues;
    }

    private SecrecyViolationPolicy() {
    }
}
