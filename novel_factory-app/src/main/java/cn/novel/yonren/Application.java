package cn.novel.yonren;

import cn.novel.yonren.domain.novel.model.valobj.properties.StoryProperties;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration"
})
@Configurable
@EnableConfigurationProperties(StoryProperties.class)
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
    }

}
