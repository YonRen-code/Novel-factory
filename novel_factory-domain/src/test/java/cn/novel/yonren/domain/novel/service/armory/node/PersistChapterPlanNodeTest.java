package cn.novel.yonren.domain.novel.service.armory.node;

import cn.novel.yonren.domain.novel.adapter.repository.IStoryRepository;
import cn.novel.yonren.domain.novel.model.aggregate.ChapterPlanAggregate;
import cn.novel.yonren.domain.novel.model.aggregate.StoryGenerateResultAggregate;
import cn.novel.yonren.domain.novel.model.entity.ArmoryCommandEntity;
import cn.novel.yonren.domain.novel.model.entity.StoryContextEntity;
import cn.novel.yonren.domain.novel.service.armory.factory.DefaultArmoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 树尾持久化测试：复用计划检查点的 run 目录（创建非幂等，禁止重复创建）与兜底创建路径
 */
class PersistChapterPlanNodeTest {

    private static final Path STORY_DIR = Path.of("workspace", "stories", "20260905-story-0001");
    private static final Path RUN_DIR = STORY_DIR.resolve("generation-records").resolve("run-0001");

    @Mock
    private IStoryRepository storyRepository;

    @InjectMocks
    private PersistChapterPlanNode node;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void tailReusesRunDirFromPlanCheckpoint() throws Exception {
        when(storyRepository.createRunDirectory(any(), any())).thenReturn(RUN_DIR);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContextEntity(StoryContextEntity.builder().novel_title("回南天").build())
                .chapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1234567890").build())
                .storyDir(STORY_DIR)
                .runDir(RUN_DIR)
                .chapterContents(List.of())
                .chapterSummaries(List.of())
                .build();

        StoryGenerateResultAggregate aggregate = node.doApply(command(), ctx);

        // 计划检查点已创建 run 目录：树尾直接复用，禁止二次创建
        verify(storyRepository, never()).createRunDirectory(any(), any());
        verify(storyRepository).writeChapterPlan(eq(RUN_DIR), any());
        verify(storyRepository).writeStoryBible(STORY_DIR, command());
        assertEquals(STORY_DIR.getFileName().toString(), aggregate.getStoryDirName());
    }

    @Test
    void tailFallsBackToCreatingRunDirWhenCheckpointSkipped() throws Exception {
        when(storyRepository.createStoryDirectory()).thenReturn(STORY_DIR);
        when(storyRepository.createRunDirectory(any(), any())).thenReturn(RUN_DIR);
        DefaultArmoryFactory.DynamicContext ctx = DefaultArmoryFactory.DynamicContext.builder()
                .storyContextEntity(StoryContextEntity.builder().novel_title("回南天").build())
                .chapterPlanAggregate(ChapterPlanAggregate.builder().storyId("story-1234567890").build())
                .build();

        node.doApply(command(), ctx);

        // 兜底路径：检查点未执行时树尾创建 run 目录并落盘
        verify(storyRepository).createRunDirectory(STORY_DIR, null);
        verify(storyRepository).writeChapterPlan(RUN_DIR, ctx.getChapterPlanAggregate());
    }

    private ArmoryCommandEntity command() {
        return ArmoryCommandEntity.builder()
                .storyContextEntity(StoryContextEntity.builder().novel_title("回南天").build())
                .build();
    }
}
