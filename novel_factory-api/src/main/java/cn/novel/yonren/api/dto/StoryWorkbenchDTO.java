package cn.novel.yonren.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单体工作台的只读聚合视图。字段保持为现有文件数据的投影，避免引入新的存储模型。
 */
@Data
public class StoryWorkbenchDTO {

    private String storyDirName;

    private String novelTitle;

    private String storyOverview;

    private int chapterCount;

    private String storyPhase;

    private boolean finalVolumeDeclared;

    private Integer estimatedTotalChapters;

    private Integer estimatedRemainingChaptersMin;

    private Integer estimatedRemainingChaptersMax;

    private List<String> remainingFinaleBeats = new ArrayList<>();

    private List<String> completedFinaleBeats = new ArrayList<>();

    private boolean activeJob;

    private Integer latestChapterNo;

    private int latestChapterLength;

    private List<LedgerDTO> characters = new ArrayList<>();

    private List<LedgerDTO> items = new ArrayList<>();

    private List<LedgerDTO> factions = new ArrayList<>();

    private List<ForeshadowDTO> foreshadowing = new ArrayList<>();

    private List<PendingFactDTO> pendingFacts = new ArrayList<>();

    private List<QualityDebtDTO> qualityDebts = new ArrayList<>();

    private WorkbenchMetrics metrics = new WorkbenchMetrics();

    @Data
    public static class ForeshadowDTO {
        private int chapterNo;
        private String content;
        private String status;
        private Integer importance;
    }

    @Data
    public static class LedgerDTO {
        private String name;
        private String status;
        private Integer firstChapterNo;
        private Integer lastChapterNo;
        private String prevStatus;
        private String evidence;
        private boolean reversalSuspect;
    }

    @Data
    public static class QualityDebtDTO {
        private Integer chapterNo;
        private Object issues;
        private boolean resolved;
        private int cleanStreak;
    }

    @Data
    public static class WorkbenchMetrics {
        private int totalCharacters;
        private int totalWords;
        private int averageChapterWords;
        private int continuityConflictCount;
        private int pendingFactCount;
        private int unresolvedQualityDebtCount;
        private int unresolvedForeshadowCount;
    }
}
