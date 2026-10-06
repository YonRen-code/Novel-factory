package cn.novel.yonren.domain.novel.service.armory.audit;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.entity.ChapterIssueEntity;
import cn.novel.yonren.domain.novel.model.entity.ChapterPlanItemEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditSampleServiceTest {

    @Mock
    private IStoryRepository storyRepository;

    @InjectMocks
    private AuditSampleService service;

    private final Path storyDir = Path.of("stories", "20260905-story-0001");

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void record_nullStoryDir_skips() throws Exception {
        service.record(null, 3, new ChapterPlanItemEntity(), "正文",
                List.of(issue()), 2, "账本", "伏笔");
        verify(storyRepository, never()).appendAuditSample(any(), anyString());
    }

    @Test
    void record_emptyIssues_skips() throws Exception {
        service.record(storyDir, 3, new ChapterPlanItemEntity(), "正文",
                List.of(), 2, "账本", "伏笔");
        verify(storyRepository, never()).appendAuditSample(any(), anyString());
    }

    @Test
    void record_appendsJsonlLine_withSampleContent() throws Exception {
        service.record(storyDir, 3, new ChapterPlanItemEntity(), "最终正文",
                List.of(issue()), 2, "账本内容", "伏笔内容");

        ArgumentCaptor<String> lineCaptor = ArgumentCaptor.forClass(String.class);
        verify(storyRepository).appendAuditSample(eq(storyDir), lineCaptor.capture());
        String line = lineCaptor.getValue();
        assertTrue(line.contains("\"chapterNo\":3"));
        assertTrue(line.contains("\"attempts\":2"));
        assertTrue(line.contains("最终正文"));
        assertTrue(line.contains("连续性断裂"));
    }

    @Test
    void record_repositoryThrows_swallowsException() throws Exception {
        doThrow(new RuntimeException("磁盘炸了")).when(storyRepository).appendAuditSample(any(), anyString());

        assertDoesNotThrow(() -> service.record(storyDir, 3, new ChapterPlanItemEntity(), "正文",
                List.of(issue()), 1, "账本", "伏笔"));
    }

    private ChapterIssueEntity issue() {
        return ChapterIssueEntity.builder().severity("BLOCKING").description("连续性断裂").build();
    }
}
