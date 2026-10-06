package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class StorySummaryDTO {
    private String storyDirName;
    private String novelTitle;
    private int chapterCount;
    private long lastModifiedMs;
    private boolean activeJob;
}
