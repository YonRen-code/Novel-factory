package cn.novel.yonren.domain.novel.service.job;

import org.springframework.stereotype.Service;
import cn.novel.yonren.types.enums.JobStatus;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存作业注册表（单进程个人使用，无 DB 队列）。
 * 进程重启后注册表清空：job-status.json 仅供人工查看崩溃前进度，不回填、不自动恢复执行
 */
@Service
public class JobRegistry {

    private final ConcurrentHashMap<String, GenerationJob> jobs = new ConcurrentHashMap<>();

    public void register(GenerationJob job) {
        jobs.put(job.getJobId(), job);
    }

    /** 未知 jobId 返回 null */
    public GenerationJob get(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * 指定故事是否有运行中的作业（CREATED/RUNNING/CANCELLING/AWAITING_APPROVAL）：
     * 编辑端点据此拒绝修改正在生成的故事，防止正文与记忆不一致。
     * AWAITING_APPROVAL 必须计入——审批门挂起的作业尚未定稿本批，此时改正文会与即将写入的计划冲突
     */
    public boolean isActiveForStory(String storyDirName) {
        if (storyDirName == null) {
            return false;
        }
        for (GenerationJob job : jobs.values()) {
            JobStatus s = job.getStatus();
            if ((s == JobStatus.CREATED || s == JobStatus.RUNNING || s == JobStatus.CANCELLING
                    || s == JobStatus.AWAITING_APPROVAL)
                    && storyDirName.equals(job.getStoryDirName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 协作取消：CREATED → 直接 CANCELLED（未启动）；RUNNING → CANCELLING（当前章完成后停）；
     * AWAITING_APPROVAL → 直接 CANCELLED（审批门已释放线程，无在途工作，无需等待当前章）；
     * CANCELLING 幂等；终态不动。未知 jobId 返回 null
     */
    public GenerationJob cancel(String jobId) {
        GenerationJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        if (!job.requestCancel()) {
            return job;
        }
        if (job.getStatus() == JobStatus.CREATED || job.getStatus() == JobStatus.AWAITING_APPROVAL) {
            job.markCancelled();
        } else if (job.getStatus() == JobStatus.RUNNING) {
            job.markCancelling();
        }
        return job;
    }
}
