package cn.novel.yonren;

import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// 应用按场景（ModelScene）手动构建 OpenAI 客户端，chat/embedding 各场景可指向不同 base-url 与模型；
// Spring AI 的 OpenAI 自动装配会在启动期强求单一全局 api-key 并注册冲突的默认客户端，故全部排除。
@SpringBootApplication(exclude = {
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class
})
@Configurable
@EnableConfigurationProperties(StoryProperties.class)
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
    }

}
