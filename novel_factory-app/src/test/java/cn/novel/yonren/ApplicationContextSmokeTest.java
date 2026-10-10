package cn.novel.yonren;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ResourceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配冒烟：上下文能起来，且 Web 工作台的三个静态文件确实在 classpath 上。
 * 静态资源是硬约束——一旦被挪出 resources/static 或被 Maven 过滤改写，
 * {@code http://localhost:8080} 会静默变成 Whitelabel 404，且任何纯后端测试都不会发现。
 */
@SpringBootTest
class ApplicationContextSmokeTest {

    @Autowired
    private ResourceLoader resourceLoader;

    @Test
    void contextLoads() {
    }

    @Test
    void workbenchAssetsAreOnClasspath() {
        for (String asset : new String[]{"index.html", "app.js", "style.css"}) {
            assertTrue(resourceLoader.getResource("classpath:static/" + asset).exists(),
                    "classpath:static/" + asset + " 缺失，浏览器将打不开工作台");
        }
    }
}
