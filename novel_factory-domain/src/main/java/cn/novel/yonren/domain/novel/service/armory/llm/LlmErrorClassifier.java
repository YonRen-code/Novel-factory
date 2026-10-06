package cn.novel.yonren.domain.novel.service.armory.llm;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * LLM 调用失败的**错误分类**：把"整批终止"这个唯一出口，拆成"该重试 / 该换模型 / 该跳过 / 该停"。
 *
 * <p><b>为什么需要</b>（2026-09-15 实测）：全自动化下最贵的失败不是写错一章，而是
 * <em>失败语义太粗</em>——403 {@code AllocationQuota.FreeTierOnly}（额度耗尽）、
 * 400 {@code invalid_request_error}（参数被拒）、404 {@code model_not_found}（模型名写错）、
 * 超时，全部落到同一个"整批终止"或 fail-soft 静默降级。而这四类的正确处置完全不同：
 * <ul>
 *   <li>超时/网络/5xx → <b>原样重试</b>（换模型毫无意义）</li>
 *   <li>额度耗尽 → 同 key 重试<b>没有意义</b>；但百炼的免费额度<em>按模型 endpoint 分别计量</em>
 *       （实测快照名 {@code deepseek-v4-pro-0813} 37/37 成功、裸别名 {@code deepseek-v4-pro} 却报 FreeTierOnly），
 *       所以<b>换模型有效</b></li>
 *   <li>模型不存在 / 参数被拒 → 重试永远失败，必须换模型（或改配置）</li>
 *   <li>内容审计命中 → 与模型无关，换模型也可能不过；属内容问题，不该让整批陪葬</li>
 * </ul>
 *
 * <p>因此分类结果要回答两个问题：<b>重试有没有用</b>、<b>换模型有没有用</b>。
 * 前者决定是否原样重试，后者在语义上表示"这个错误的成因是否与所选模型绑定"。
 *
 * <p><b>⚠️ 但"换模型有没有用"不等于"网关该不该换模型"</b>（2026-09-30 澄清）：
 * 网关的实际口径更宽——<b>除内容审计外一律换模型</b>（见 {@code SpringAiLlmGateway#shouldFallback}）。
 * 原因是瞬时抖动虽在语义上与模型无关，但传输层只重试 2 次、间隔极短，
 * 而"换个端点重跑"对上游掐断/网关抖动往往一次就过，且整批终止的代价远高于一次多余调用。
 * 故 {@link #shouldFallbackToAnotherModel} 保留为"成因与模型绑定"的语义判据，
 * 但<b>不再是网关的开关</b>——改网关行为请改 {@code shouldFallback}。
 *
 * <p>实现刻意只做<b>纯文本匹配</b>（遍历异常 cause 链取 message）而不依赖具体 SDK 异常类型：
 * Spring AI 把 HTTP 错误统一包成 {@code TransientAiException} / {@code NonTransientAiException}，
 * 真正的错误码（FreeTierOnly / model_not_found / content_policy_violation）只出现在 message 里；
 * 且换供应商时不必改判据。
 */
public final class LlmErrorClassifier {

    /** 判定"该不该重试 / 该不该换模型"的失败类别 */
    public enum Kind {

        /** 瞬时故障：超时、网络中断、5xx、线程取消——原样重试即可，换模型无意义 */
        TRANSIENT("瞬时故障（可原样重试）", true, false),

        /** 额度耗尽 / 仅免费额度：同 key 重试无意义，但额度按模型分别计量 ⇒ 换模型有效 */
        QUOTA_EXHAUSTED("额度耗尽（同模型重试无效，可换模型）", false, true),

        /** 模型不存在（名字写错或该账号无权）：重试永远失败，必须换模型 */
        MODEL_NOT_FOUND("模型不存在（必须换模型）", false, true),

        /** 请求参数被拒（如强制思考模型被要求关思考）：重试永远失败，须换模型或改参数 */
        INVALID_REQUEST("请求参数被拒（须换模型或改参数）", false, true),

        /**
         * 供应商侧内部故障（500 / engine abort / InternalError.Algo）：
         * 重试同一模型大概率再撞一次（长时间思考跑到一半被上游掐断），<b>换模型更有效</b>。
         *
         * <p>2026-09-30 新增：此前 500 落到 {@link #UNKNOWN}（modelLevel=false），
         * 于是"思考模型被上游 abort"永远不触发降级链——而这恰恰是思考模型最常见的失败。
         */
        UPSTREAM_INTERNAL("供应商内部故障（换模型比原样重试更有效）", true, true),

        /** 内容审计命中：与模型无关，换模型也可能不过；属内容问题，不该让整批陪葬 */
        CONTENT_POLICY("内容审计命中（模型无关，需人工/跳过）", false, false),

        /** 无法归类：保守按"可重试"处理，交给传输层重试策略兜底 */
        UNKNOWN("无法归类（保守按可重试处理）", true, false);

        private final String desc;
        private final boolean retryable;
        private final boolean modelLevel;

        Kind(String desc, boolean retryable, boolean modelLevel) {
            this.desc = desc;
            this.retryable = retryable;
            this.modelLevel = modelLevel;
        }

        public String getDesc() {
            return desc;
        }

        /** 原样重试（同一模型）是否有意义 */
        public boolean isRetryable() {
            return retryable;
        }

        /** 是否属"模型层"错误——换模型比原样重试更有意义（场景级降级链的适用条件） */
        public boolean isModelLevel() {
            return modelLevel;
        }
    }

    /**
     * 匹配规则：按顺序命中即返回。
     * 顺序敏感——先判更具体的，例如 {@code content_policy_violation} 必须先于任何宽泛的 403 判断，
     * 否则会被误归为额度问题而白走一轮降级。
     */
    private static final List<Rule> RULES = List.of(
            // 内容审计：百炼报 content_policy_violation / data_inspection_failed，OpenAI 报 content_filter
            new Rule(Kind.CONTENT_POLICY, "content_policy_violation", "content_policy", "content_filter",
                    "data_inspection", "内容审计", "风险规则"),
            // 额度：百炼报 AllocationQuota.FreeTierOnly / insufficient_quota
            new Rule(Kind.QUOTA_EXHAUSTED, "freetieronly", "allocationquota", "insufficient_quota",
                    "exceeded your current quota", "quota"),
            // 模型不存在
            new Rule(Kind.MODEL_NOT_FOUND, "model_not_found", "model not found", "no such model",
                    "does not exist", "unknown model"),
            // 请求非法
            new Rule(Kind.INVALID_REQUEST, "invalid_request_error", "invalidparameter", "invalid_parameter",
                    "invalid_value", "invalid value", "unsupported value"),
            // 供应商侧内部故障（2026-09-30 补）：思考模型跑久了上游会直接 abort，
            // 回报 500 InternalError.Algo / engine abort / 引擎异常 —— 这类错误**换模型有效**
            //（同一份请求换一个供应商端点重跑通常就过了），而原样重试同一模型大概率再撞一次。
            // 实测：kimi-k3 跑卷蓝图 795s 后报 500 engine abort，若不归此类则落到 UNKNOWN
            //（modelLevel=false，不换模型）+ 传输层把 500 当服务端错误重试 → 白烧两轮长调用后整批终止。
            new Rule(Kind.UPSTREAM_INTERNAL, "internalerror.algo", "internal_error.algo", "engine abort",
                    "engine_abort", "engineerror", "internal server error", "server_error",
                    "service internal error", " 500", "上游异常", "引擎异常"),
            // 瞬时故障
            new Rule(Kind.TRANSIENT, "timeout", "timed out", "connection reset", "connection refused",
                    "temporarily unavailable", "service unavailable", "bad gateway", "gateway timeout",
                    " 502", " 503", " 504", "rate limit", "too many requests", "overloaded")
    );

    private LlmErrorClassifier() {
    }

    /**
     * 分类一次失败。遍历 cause 链取全部 message 片段做匹配（HTTP 错误码常埋在内层 cause）。
     * 入参为 null 返回 {@link Kind#UNKNOWN}。
     */
    public static Kind classify(Throwable error) {
        if (error == null) {
            return Kind.UNKNOWN;
        }
        String text = collectMessages(error).toLowerCase(Locale.ROOT);
        // 异常类型优先：Spring AI 的瞬时异常是被设计用来重试的，且它的 message 可能不含关键词
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof CancellationException) {
                return Kind.TRANSIENT;
            }
        }
        for (Rule rule : RULES) {
            for (String token : rule.tokens) {
                if (text.contains(token)) {
                    return rule.kind;
                }
            }
        }
        return Kind.UNKNOWN;
    }

    /** 收集异常链上的全部 message（上限 8 层，防自引用死循环） */
    private static String collectMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            sb.append(current.getClass().getSimpleName()).append(' ');
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return sb.toString();
    }

    /**
     * 判断该失败的<b>成因是否与所选模型绑定</b>（即"换模型在语义上是否比原样重试更有意义"）。
     * 等价于 {@code classify(error).isModelLevel()}，此方法存在是为了让调用点读起来是意图而非实现。
     *
     * <p>⚠️ <b>网关的降级开关不是它</b>：网关自 2026-09-30 起用"除内容审计外一律换模型"的更宽口径
     * （见 {@code SpringAiLlmGateway#shouldFallback}）。本方法保留给"需要严格区分瞬时/模型层"的调用点，
     * 例如成本归因与测试断言——那里只想知道错误的成因，不关心兜底策略。
     */
    public static boolean shouldFallbackToAnotherModel(Throwable error) {
        return classify(error).isModelLevel();
    }

    /** 规则载体：命中任一 token 即归入该类 */
    private record Rule(Kind kind, String... tokens) {
    }
}
