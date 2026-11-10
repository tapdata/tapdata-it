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
 * kubectl —— 在 K8s 宿主机上执行一条 kubectl 命令，并支持 stdout 断言与轮询。
 *
 * <pre>
 * inputs:
 *   namespace: tapdata-ha
 *   command: get deploy tapdata-tapdata-server -o jsonpath='{.status.readyReplicas}'
 *   expect_stdout_equals: "2"          # 可选
 *   expect_stdout_contains: xxx        # 可选
 *   expect_stdout_not_contains: xxx    # 可选
 *   max_attempts: 40                   # 可选，断言不满足时轮询重试
 *   poll_interval_seconds: 3           # 可选
 *   command_timeout_seconds: 180       # 可选
 *   host: 113.98.206.142
 * </pre>
 */
public class KubectlAction extends BaseStep {

    private static final Logger log = LoggerFactory.getLogger(KubectlAction.class);

    @Override
    public String getActionName() {
        return "kubectl";
    }

    @Override
    protected StepResult doExecute(StepConfig config, ScenarioContext ctx) throws Exception {
        KubectlParameter p = config.getInputParameterConfig(KubectlParameter.class);

        if (p.getNamespace() == null || p.getNamespace().isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        if (p.getCommand() == null || p.getCommand().isBlank()) {
            throw new IllegalArgumentException("command is required");
        }

        K8sClient k8s = new K8sClient(p.getHost());
        String fullCmd = "kubectl -n " + p.getNamespace() + " " + p.getCommand();
        long timeoutMs = (p.getCommandTimeoutSeconds() == null ? 180 : p.getCommandTimeoutSeconds()) * 1000L;
        int maxAttempts = Math.max(1, p.getMaxAttempts() == null ? 1 : p.getMaxAttempts());
        long pollMs = Math.max(1, p.getPollIntervalSeconds() == null ? 3 : p.getPollIntervalSeconds()) * 1000L;

        log.info("[kubectl] ns={} cmd={}", p.getNamespace(), p.getCommand());
        K8sClient.ExecResult r = null;
        String error = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            r = k8s.exec(fullCmd, timeoutMs);
            error = checkAssertions(p, r);
            if (error == null) {
                break;
            }
            log.info("[kubectl] attempt {}/{} not satisfied: {}", attempt, maxAttempts, error);
            if (attempt < maxAttempts) {
                Thread.sleep(pollMs);
            }
        }
        log.info("[kubectl] exit={} stdout={}", r.exitCode, r.stdout.trim());

        if (error != null) {
            return failure(new RuntimeException("kubectl assertion failed after " + maxAttempts
                    + " attempt(s): " + error));
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("stdout", r.stdout);
        outputs.put("stdoutTrimmed", r.stdout.trim());
        outputs.put("stderr", r.stderr);
        outputs.put("exitCode", r.exitCode);
        return success(outputs);
    }

    /** 校验断言；全部通过返回 null，否则返回失败原因。 */
    private String checkAssertions(KubectlParameter p, K8sClient.ExecResult r) {
        if (r.exitCode != 0) {
            return "exit=" + r.exitCode + ": " + r.stderr.trim();
        }
        String stdout = r.stdout.trim();
        if (p.getExpectStdoutEquals() != null && !p.getExpectStdoutEquals().isBlank()
                && !stdout.equals(p.getExpectStdoutEquals())) {
            return "expected stdout '" + p.getExpectStdoutEquals() + "' but got '" + stdout + "'";
        }
        if (p.getExpectStdoutContains() != null && !p.getExpectStdoutContains().isBlank()
                && !r.stdout.contains(p.getExpectStdoutContains())) {
            return "expected stdout to contain '" + p.getExpectStdoutContains() + "' but got '" + stdout + "'";
        }
        if (p.getExpectStdoutNotContains() != null && !p.getExpectStdoutNotContains().isBlank()
                && r.stdout.contains(p.getExpectStdoutNotContains())) {
            return "expected stdout NOT to contain '" + p.getExpectStdoutNotContains() + "' but got '" + stdout + "'";
        }
        return null;
    }
}
