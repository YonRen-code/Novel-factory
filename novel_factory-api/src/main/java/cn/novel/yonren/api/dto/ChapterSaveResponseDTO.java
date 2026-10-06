package cn.novel.yonren.api.dto;

import lombok.Data;

@Data
public class ChapterSaveResponseDTO {
    private boolean saved;
    private boolean summaryUpdated;
    private boolean partial;
    private String warning;
}
