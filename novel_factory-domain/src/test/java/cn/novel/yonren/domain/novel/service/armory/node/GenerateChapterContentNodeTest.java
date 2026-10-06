package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import cn.novel.yonren.domain.novel.service.armory.worker.ChapterWorker;
import cn.novel.yonren.domain.novel.service.job.GenerationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划检查点测试：storyId 兜底分配、目录/run 目录创建与复用、计划提前落盘、失败不阻塞
 */
class GenerateChapterContentNodeTest {

    private static final Path STORY_DIR = Path.of("workspace", "stories", "20260905-story-0001");
    private static final Path RUN_DIR = STORY_DIR.resolve("generation-records").resolve("run-0001");

    @Mock
    private IStoryRepository storyRepository;

    @Mock
    private ChapterWorker chapterWorker;

    @Mock
    private PersistChapterPlanNode persistChapterPlanNode;

    @InjectMocks
    private GenerateChapterContentNode node;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void preparePlanCheckpoint_assignsStoryIdCreatesDirsAndWritesPlan() throws Exception {
        when(storyRepository.createStoryDirectory()).thenReturn(STORY_DIR);
        when(storyRepository.createRunDirectory(any(), isNull())).thenReturn(RUN_DIR);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(ChapterPlanAggregate.builder().build())
                .build();

        node.preparePlanCheckpoint(ctx);

        // storyId 兜底分配、目录/run 目录创建、计划写入 run 目录并回存上下文
        assertTrue(ctx.getChapterPlanAggregate().getStoryId().matches("^story-\\d{10,19}$"));
        verify(storyRepository).createStoryDirectory();
        verify(storyRepository).createRunDirectory(STORY_DIR, null);
        verify(storyRepository).writeChapterPlan(eq(RUN_DIR), any());
        assertEquals(RUN_DIR, ctx.getRunDir());
    }

    @Test
    void preparePlanCheckpoint_reusesExistingStoryDirAndValidStoryId() throws Exception {
        when(storyRepository.createRunDirectory(any(), any())).thenReturn(RUN_DIR);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1234567890").build())
                .storyDir(STORY_DIR)
                .build();

        node.preparePlanCheckpoint(ctx);

        // 目录已预解析（续写）且 storyId 已合法：不再创建，仅写计划
        verify(storyRepository, never()).createStoryDirectory();
        verify(storyRepository).createRunDirectory(STORY_DIR, null);
        verify(storyRepository).writeChapterPlan(RUN_DIR, ctx.getChapterPlanAggregate());
        assertEquals("story-1234567890", ctx.getChapterPlanAggregate().getStoryId());
    }

    @Test
    void preparePlanCheckpoint_passesJobIdAndSetsRunDirName() throws Exception {
        when(storyRepository.createStoryDirectory()).thenReturn(STORY_DIR);
        when(storyRepository.createRunDirectory(any(), eq("job-1"))).thenReturn(RUN_DIR);
        GenerationJob job = new GenerationJob("job-1");
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(ChapterPlanAggregate.builder().build())
                .job(job)
                .build();

        node.preparePlanCheckpoint(ctx);

        verify(storyRepository).createRunDirectory(STORY_DIR, "job-1");
        assertEquals("run-0001", job.getRunDirName());
    }

    @Test
    void preparePlanCheckpoint_failureIsFailSoft() throws Exception {
        when(storyRepository.createStoryDirectory()).thenReturn(STORY_DIR);
        when(storyRepository.createRunDirectory(any(), any()))
                .thenThrow(new IllegalStateException("disk full"));
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .chapterPlanAggregate(ChapterPlanAggregate.builder().build())
                .build();

        // 检查点是增强件：落盘失败不阻塞正文生成（树尾持久化兜底）
        assertDoesNotThrow(() -> node.preparePlanCheckpoint(ctx));
        assertNull(ctx.getRunDir());
        verify(storyRepository, never()).writeChapterPlan(any(), any());
    }

    @Test
    void preparePlanCheckpoint_withoutPlanDoesNothing() throws Exception {
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder().build();

        node.preparePlanCheckpoint(ctx);

        try {
            verify(storyRepository, never()).createStoryDirectory();
            verify(storyRepository, never()).createRunDirectory(any(), any());
            verify(storyRepository, never()).writeChapterPlan(any(), any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertNull(ctx.getRunDir());
    }
}
