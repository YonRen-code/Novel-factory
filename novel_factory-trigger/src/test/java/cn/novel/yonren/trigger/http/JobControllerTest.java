package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.JobStatusResponseDTO;
import cn.novel.yonren.api.dto.StoryGenerateRequestDTO;
import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import cn.novel.yonren.types.enums.JobStatus;
import cn.novel.yonren.domain.novel.service.job.StoryJobService;
import cn.novel.yonren.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作业端点测试：提交/查询/取消与队列满 503、未知作业 404
 */
class JobControllerTest {

    @Mock
    private StoryJobService storyJobService;

    @Mock
    private StoryProperties storyProperties;

    @InjectMocks
    private JobController jobController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(storyProperties.toStoryVO()).thenReturn(null);
    }

    @Test
    void submit_returnsJobIdAndCreatedStatus() {
        StoryGenerateRequestDTO request = new StoryGenerateRequestDTO();
        request.setNovel_title("灵气复苏后的外卖员");
        request.setResumeStoryDir("20260902-story-0001");
        when(storyJobService.submit(any())).thenReturn(new GenerationJob("job-7"));

        JobStatusResponseDTO dto = jobController.submit(request);

        assertEquals("job-7", dto.getJobId());
        assertEquals("CREATED", dto.getStatus());

        ArgumentCaptor<cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity> captor =
                ArgumentCaptor.forClass(cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity.class);
        verify(storyJobService).submit(captor.capture());
        assertEquals("20260902-story-0001", captor.getValue().getResumeStoryDir());
    }

    @Test
    void submit_queueFull_throws503() {
        when(storyJobService.submit(any()))
                .thenThrow(new AppException("500", "作业队列已满，请稍后重试"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.submit(new StoryGenerateRequestDTO()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    @Test
    void status_knownJob_mapsAllFields() {
        GenerationJob job = new GenerationJob("job-8");
        job.markRunning();
        job.updateProgress("CHAPTER_GENERATION", 5, 24, "20260903-story-0001");
        job.recordChapterDuration(5, 60000L);
        when(storyJobService.get("job-8")).thenReturn(job);

        JobStatusResponseDTO dto = jobController.status("job-8");

        assertEquals("job-8", dto.getJobId());
        assertEquals("RUNNING", dto.getStatus());
        assertEquals("CHAPTER_GENERATION", dto.getCurrentStage());
        assertEquals(5, dto.getCurrentChapter());
        assertEquals(24, dto.getTotalChapters());
        assertEquals("20260903-story-0001", dto.getStoryDirName());
        assertEquals(60000L, dto.getChapterDurations().get("5"));
    }

    @Test
    void status_unknownJob_throws404() {
        when(storyJobService.get("nope")).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.status("nope"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void cancel_knownJob_returnsJob() {
        GenerationJob job = new GenerationJob("job-9");
        job.markRunning();
        job.requestCancel();
        job.markCancelling();
        when(storyJobService.cancel("job-9")).thenReturn(job);

        JobStatusResponseDTO dto = jobController.cancel("job-9");

        assertEquals("job-9", dto.getJobId());
        assertEquals(JobStatus.CANCELLING.name(), dto.getStatus());
    }

    @Test
    void cancel_unknownJob_throws404() {
        when(storyJobService.cancel("nope")).thenReturn(null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> jobController.cancel("nope"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
