package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ScenarioContext;
import io.tapdata.it.l3.common.StepConfig;
import io.tapdata.it.l3.plugin.step.BaseStep;
import io.tapdata.it.l3.plugin.step.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * exec —— 在指定 K8s Pod 内执行一条 shell 命令。
 *
 * <pre>
 * inputs:
 *   namespace: tapdata-xxx
 *   pod: tapdata-server       # 支持前缀匹配，会自动找第一个 tapdata-server-xxx Pod
 *   command: ps -ef | grep java
 *   container:                # 可选
 *   host: 113.98.206.142
 * </pre>
 */
public class ExecAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(ExecAction.class);

    @Override
    public String getActionName() {
        return "exec";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        ExecParameter p = config.getInputParameterConfig(ExecParameter.class);

        if (p.getNamespace() == null || p.getNamespace().isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        if (p.getPod() == null || p.getPod().isBlank()) {
            throw new IllegalArgumentException("pod is required");
        }
        if (p.getCommand() == null || p.getCommand().isBlank()) {
            throw new IllegalArgumentException("command is required");
        }

        K8sClient k8s = new K8sClient(p.getHost());
        String pod = resolvePodName(k8s, p.getNamespace(), p.getPod());
        log.info("[exec] ns={} pod={} cmd={}", p.getNamespace(), pod, p.getCommand());

        // 构造 kubectl exec 命令
        StringBuilder cmd = new StringBuilder("kubectl exec -n ")
                .append(p.getNamespace())
                .append(' ').append(pod);
        if (p.getContainer() != null && !p.getContainer().isBlank()) {
            cmd.append(" -c ").append(p.getContainer());
        }
        cmd.append(" -- sh -c ").append(quote(p.getCommand()));

        // 进程级超时跟随 step timeout_seconds（留 10s 余量），兜底 120s —
        // 之前硬编码 120s 会在长命令（如 connector 注册）还没跑完时被强杀。
        long procTimeoutMs = Math.max(120_000L,
                config.getTimeoutSeconds() > 0 ? config.getTimeoutSeconds() * 1000L - 10_000L : 120_000L);
        K8sClient.ExecResult r = k8s.exec(cmd.toString(), procTimeoutMs);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("stdout", r.stdout);
        outputs.put("stderr", r.stderr);
        outputs.put("exitCode", r.exitCode);
        outputs.put("pod", pod);

        if (r.exitCode != 0) {
            // 返回 failure 而不是抛异常 —— 因为有些场景下 stderr / exitCode 是业务断言的一部分
            return failure(new RuntimeException("exec exit=" + r.exitCode + ": " + r.stderr));
        }
        if (p.getExpectStdoutContains() != null && !p.getExpectStdoutContains().isBlank()
                && !r.stdout.contains(p.getExpectStdoutContains())) {
            return failure(new RuntimeException(
                    "exec stdout assertion failed, expected to contain '" + p.getExpectStdoutContains()
                            + "' but got: " + r.stdout.trim()));
        }
        return success(outputs);
    }

    /**
     * 解析 pod 名：总是先列出 Pod —— 精确匹配优先（任意 phase，兼容 StatefulSet
     * 的 lease-xxx-0 形式），否则取第一个以它为前缀的 Running Pod。
     * 不再用正则猜 "是否已是完整 pod 名"（`-engine` 这类后缀会被误判成 pod hash）。
     */
    private String resolvePodName(K8sClient k8s, String namespace, String podArg) {
        K8sClient.ExecResult r = k8s.exec(
                "kubectl get pods -n " + namespace + " " +
                "-o jsonpath='{range .items[*]}{.metadata.name}{\" \"}{.status.phase}{\"\\n\"}{end}'");
        if (!r.isSuccess()) {
            throw new RuntimeException("kubectl get pods failed: " + r.stderr);
        }
        String runningPrefix = null;
        StringBuilder available = new StringBuilder();
        for (String line : r.stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            available.append(trimmed).append("; ");
            String name = trimmed.split("\\s+")[0];
            if (name.equals(podArg)) {
                return name; // 精确匹配 — 直接使用（包括非 Running，错误留给 kubectl exec 报）
            }
            if (runningPrefix == null && name.startsWith(podArg) && trimmed.endsWith("Running")) {
                runningPrefix = name;
            }
        }
        if (runningPrefix != null) {
            return runningPrefix;
        }
        throw new RuntimeException("No pod found matching '" + podArg + "' in namespace " + namespace
                + ". Available: " + available);
    }

    /** 用单引号包住命令，内部单引号替换为 '\'' 以便远端 bash 正确解析。 */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
