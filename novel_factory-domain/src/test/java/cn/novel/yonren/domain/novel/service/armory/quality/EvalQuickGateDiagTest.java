package cn.novel.yonren.domain.novel.service.armory.quality;

import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 评测诊断（纯机械、零 LLM、可重复跑）：对盲评两侧稿件跑机械文风门禁，
 * 输出违规数与有效字数，供跨系统对比报告引用。产物路径为一次性评测目录
 */
class EvalQuickGateDiagTest {

    private static final Path BASE = Path.of(
            "D:", "Java_Project", "Novel_Assembly_Line", "novel_factory", "docs", "workspace", "eval", "round1");

    @Test
    void runMechanicalGateOnBothSides() throws Exception {
        for (String side : List.of("A", "B")) {
            Path dir = BASE.resolve(side);
            if (!Files.isDirectory(dir)) {
                System.out.println("[跳过] 目录不存在: " + dir);
                continue;
            }
            System.out.println("==== " + side + " 侧 ====");
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(file -> {
                    try {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        int chars = ChapterLengthPolicy.effectiveCharacterCount(content);
                        List<ChapterIssueEntity> issues = StyleViolationPolicy.check(content);
                        System.out.printf("%s | 有效字 %d | 机械违规 %d 条%n",
                                file.getFileName(), chars, issues.size());
                        for (ChapterIssueEntity issue : issues) {
                            System.out.println("    - " + issue.getDescription() + "【" + issue.getEvidence() + "】");
                        }
                    } catch (Exception e) {
                        System.out.println(file.getFileName() + " 读取失败: " + e.getMessage());
                    }
                });
            }
        }
    }
}
