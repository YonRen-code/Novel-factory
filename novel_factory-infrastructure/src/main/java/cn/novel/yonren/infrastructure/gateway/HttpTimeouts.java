package cn.novel.yonren.infrastructure.gateway;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;


public final class HttpTimeouts {

    private HttpTimeouts() {
    }

    /**
     * 构建带连接/读取超时的请求工厂（JDK HttpClient 实现，与 Spring AI 默认选型一致）。
     *
     * @param connectTimeoutSeconds 连接超时（秒），<=0 时回落 20s
     * @param readTimeoutSeconds    单次请求整体响应超时（秒），<=0 时回落 600s
     */
    public static ClientHttpRequestFactory requestFactory(int connectTimeoutSeconds, int readTimeoutSeconds) {
        int connect = connectTimeoutSeconds > 0 ? connectTimeoutSeconds : 20;
        int read = readTimeoutSeconds > 0 ? readTimeoutSeconds : 600;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connect))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(read));
        return factory;
    }
}
