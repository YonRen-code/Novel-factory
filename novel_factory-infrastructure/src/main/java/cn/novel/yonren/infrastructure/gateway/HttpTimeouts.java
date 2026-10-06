package cn.novel.yonren.infrastructure.gateway;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * LLM/Embedding 出网客户端的超时工厂。
 *
 * <p>背景：Spring 的 {@code RestClient.builder()} 与 JDK 的 {@code HttpClient} 默认都<b>不设超时</b>，
 * 即连接与读取均可无限期等待。供应商（DashScope 等）在网关侧静默丢包/不回包时，
 * 调用线程会永久阻塞在 {@code CompletableFuture.get()}，表现为"作业卡住不动"——
 * 既不报错也不重试（无异常 → 传输层重试模板不会触发），整个 job 就此僵死。
 *
 * <p>因此这里显式给出两个界限：
 * <ul>
 *   <li><b>连接超时</b>：TCP/TLS 建连阶段，短时限（默认 20s）快速暴露网络不通；</li>
 *   <li><b>读取超时</b>：单次请求的整体响应时限（JDK HttpClient 的 request timeout），
 *       超时后抛出读超时异常，被 {@code ResourceAccessException} 包装，
 *       从而落入网关既有的瞬时失败重试分支，做到"要么成功、要么快速失败重试"。</li>
 * </ul>
 *
 * <p>读取默认值 600s 是取实测安全余量的结果：历史最慢一次 LLM 调用约 199s
 * （glm-5.2 章节规划/审校），中位数约 17s；600s 约为实测峰值的 3 倍，避免误杀正常长调用。
 */
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
