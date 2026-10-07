package cn.novel.yonren.domain.novel.service.job;

import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Service
@Slf4j
public class LlmBudgetFuse {

    private final StoryProperties storyProperties;
    private final JobRegistry jobRegistry;

    /** jobId → 累计 token（merge 原子累加） */
    private final ConcurrentHashMap<String, Long> jobTokens = new ConcurrentHashMap<>();
    /** 已打 WARN 的作业（每作业至多一次，防日志刷屏） */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public LlmBudgetFuse(StoryProperties storyProperties, JobRegistry jobRegistry) {
        this.storyProperties = storyProperties;
        this.jobRegistry = jobRegistry;
    }

    /**
     * 网关逐调用上报（成功与失败路径均可调用）：jobId 缺失（同步调试路径无 MDC）或 token 缺失时忽略；
     * 任何异常自吞，绝不反噬调用链
     */
    public void record(String jobId, Long totalTokens) {
        try {
            if (jobId == null || totalTokens == null || totalTokens <= 0) {
                return;
            }
            // 用量观测与预算开关解耦：即使未配置熔断阈值，也必须累计真实 token。
            long total = jobTokens.merge(jobId, totalTokens, Long::sum);
            GenerationJob job = jobRegistry.get(jobId);
            if (job != null) {
                job.setTokensUsed(total);
            }

            StoryProperties.BudgetProperties budget = storyProperties.getBudget();
            if (budget == null
                    || (budget.getWarnTotalTokens() == null && budget.getHardTotalTokens() == null)) {
                return;
            }
            long hard = budget.getHardTotalTokens() == null ? Long.MAX_VALUE : budget.getHardTotalTokens();
            long warn = budget.getWarnTotalTokens() == null ? Long.MAX_VALUE : budget.getWarnTotalTokens();
            if (total >= hard) {
                if (job != null && job.requestBudgetExceeded()) {
                    log.error("作业 {} 预算熔断：累计 token {} 达硬上限 {}，当前章完成后停止"
                            + "（已完成章节均已通过逐章检查点落盘，可 resume 续写）",
                            jobId, total, budget.getHardTotalTokens());
                }
            } else if (total >= warn && warned.add(jobId)) {
                log.warn("作业 {} token 用量预警：累计 {} 已达预警线 {}（硬上限 {}）",
                        jobId, total, budget.getWarnTotalTokens(), budget.getHardTotalTokens());
            }
        } catch (Exception e) {
            log.warn("预算熔断记账失败（不影响生成）：{}", e.getMessage());
        }
    }
}
