package io.tapdata.it.l3.steps.deploy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 远程 K8s 客户端 —— 本地 ProcessBuilder 封装 ssh / scp，
 * 复用用户已配置的 ~/.ssh/config，直接在远程服务器上调用 kubectl / helm / docker。
 * <p>
 * 默认目标 host 为 {@code 113.98.206.142}，可通过 {@code K8S_HOST} 环境变量或构造器覆盖。
 *
 * <h3>命令模板</h3>
 * <pre>
 *   ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 {host} "{remoteCmd}"
 *   scp -o StrictHostKeyChecking=no {localPath} {host}:{remotePath}
 * </pre>
 */
public class K8sClient {

    private static final Logger log = LoggerFactory.getLogger(K8sClient.class);

    /** 远程 K8s 服务器地址。 */
    private final String host;

    /** 是否指向本机 —— 远程执行时 fat jar 和 k8s 在同一台机器。 */
    private final boolean local;

    /** ssh 连接超时（秒）。 */
    private static final int CONNECT_TIMEOUT = 10;

    /** 单次 exec 的默认超时（毫秒），SSH 命令本身的执行超时。 */
    private static final long DEFAULT_EXEC_TIMEOUT_MS = 5 * 60 * 1000L;

    public K8sClient() {
        this(System.getenv().getOrDefault("K8S_HOST", "113.98.206.142"));
    }

    public K8sClient(String host) {
        this.host = host;
        this.local = detectLocal(host);
        log.info("[K8sClient] host={}, local={}", host, local);
    }

    /** 检测 host 是否指向本机。 */
    private static boolean detectLocal(String host) {
        if (host == null) return true;
        String h = host.trim().toLowerCase();
        if ("localhost".equals(h) || "127.0.0.1".equals(h) || "::1".equals(h)) return true;
        try {
            InetAddress localMachine = InetAddress.getLocalHost();
            if (localMachine.getHostAddress().equals(host)) return true;
            // 也检查所有网卡地址
            for (InetAddress addr : InetAddress.getAllByName(localMachine.getHostName())) {
                if (addr.getHostAddress().equals(host)) return true;
            }
        } catch (Exception ignore) {}
        // 环境变量强制本地模式
        return "true".equalsIgnoreCase(System.getenv("K8S_LOCAL"));
    }

    public String getHost() {
        return host;
    }

    // ========================================================================
    // 核心执行方法
    // ========================================================================

    /**
     * 在远程服务器执行一条命令，返回 stdout / stderr / exitCode。
     * 非零退出码不会抛异常 —— 调用方通过 {@link ExecResult#isSuccess()} 判断。
     */
    public ExecResult exec(String remoteCmd) {
        return exec(remoteCmd, DEFAULT_EXEC_TIMEOUT_MS);
    }

    public ExecResult exec(String remoteCmd, long timeoutMs) {
        String[] cmd;
        if (local) {
            // 本地模式 —— 直接 bash -c
            cmd = new String[]{"bash", "-c", remoteCmd};
            log.debug("[local] {}", remoteCmd);
        } else {
            cmd = new String[]{
                    "ssh",
                    "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null",
                    "-o", "ConnectTimeout=" + CONNECT_TIMEOUT,
                    host,
                    remoteCmd
            };
            log.debug("[ssh {}] {}", host, remoteCmd);
        }
        return runProcess(cmd, timeoutMs);
    }

    /** 快捷方法：kubectl -n {namespace} {args}。 */
    public ExecResult kubectl(String namespace, String args) {
        String cmd = "kubectl -n " + namespace + " " + args;
        return exec(cmd);
    }

    /** 快捷方法：helm {args} -n {namespace}。 */
    public ExecResult helm(String namespace, String args) {
        String cmd = "helm " + args + " -n " + namespace;
        return exec(cmd);
    }

    /**
     * 把本地文件/目录 scp 到远程服务器。
     * 如果 localPath 是目录，会递归复制（scp -r）。
     */
    public void scpTo(String localPath, String remotePath) {
        File f = new File(localPath);
        boolean isDir = f.isDirectory();

        // 远程路径里的目录不存在时先 mkdir -p
        String remoteDir = remotePath.contains("/")
                ? remotePath.substring(0, remotePath.lastIndexOf('/'))
                : "";
        if (!remoteDir.isEmpty()) {
            exec("mkdir -p " + remoteDir);
        }

        String[] cmd;
        if (local) {
            // 本地模式 —— 直接 cp
            if (isDir) {
                cmd = new String[]{"cp", "-r", localPath, remotePath};
            } else {
                cmd = new String[]{"cp", localPath, remotePath};
            }
            log.debug("[cp {} -> {}]", localPath, remotePath);
        } else {
            cmd = new String[isDir ? 9 : 8];
            int i = 0;
            cmd[i++] = "scp";
            cmd[i++] = "-o";
            cmd[i++] = "StrictHostKeyChecking=no";
            cmd[i++] = "-o";
            cmd[i++] = "UserKnownHostsFile=/dev/null";
            if (isDir) cmd[i++] = "-r";
            cmd[i++] = localPath;
            cmd[i++] = host + ":" + remotePath;
            log.debug("[scp {} -> {}:{}]", localPath, host, remotePath);
        }

        ExecResult r = runProcess(cmd, DEFAULT_EXEC_TIMEOUT_MS);
        if (!r.isSuccess()) {
            throw new RuntimeException(local ? "cp failed: " : "scp failed: " + r.stderr);
        }
    }

    /**
     * 在远程执行一段 shell 脚本内容。
     * 实现：先把脚本写到远程一个临时文件，chmod +x 执行，结束后清理。
     * 这样避免 ssh 嵌套引号的麻烦。
     */
    public ExecResult execScript(String scriptContent) {
        try {
            Path tmpScript = Files.createTempFile("tapdata-deploy-", ".sh");
            Files.writeString(tmpScript, scriptContent);

            String remoteScript = "/tmp/" + tmpScript.getFileName();
            scpTo(tmpScript.toString(), remoteScript);

            try {
                return exec("bash " + remoteScript);
            } finally {
                exec("rm -f " + remoteScript);
                Files.deleteIfExists(tmpScript);
            }
        } catch (Exception e) {
            throw new RuntimeException("execScript failed", e);
        }
    }

    // ========================================================================
    // 底层 ProcessBuilder
    // ========================================================================

    private ExecResult runProcess(String[] cmd, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            return ExecResult.failure(-1, e.getMessage(), "");
        }

        // 非阻塞读 stdout / stderr
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread tOut = new Thread(() -> readStream(p.getInputStream(), stdout), "ssh-stdout");
        Thread tErr = new Thread(() -> readStream(p.getErrorStream(), stderr), "ssh-stderr");
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        boolean finished;
        try {
            finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return ExecResult.failure(-1, "interrupted", stdout.toString());
        }

        if (!finished) {
            p.destroyForcibly();
            return ExecResult.failure(-1, "timeout after " + timeoutMs + "ms", stdout.toString());
        }

        try { tOut.join(1000); tErr.join(1000); } catch (InterruptedException ignore) {}

        int code = p.exitValue();
        if (code != 0) {
            log.warn("[ssh {}] exitCode={} stderr={}", host, code, truncate(stderr.toString(), 500));
        } else {
            log.debug("[ssh {}] exitCode=0", host);
        }
        return new ExecResult(code, stdout.toString(), stderr.toString());
    }

    private static void readStream(java.io.InputStream is, StringBuilder out) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (Exception ignored) {}
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ========================================================================
    // 辅助：用 ProcessBuilder 定位 classpath 里的资源
    // ========================================================================

    /**
     * 从 module classpath 中把 resources 目录下的指定资源解压/拷贝出来，
     * 返回一个临时目录的绝对路径。如果资源是 jar 内的目录，需要先解包。
     */
    public static String extractResourceToTempdir(String resourcePath) {
        try {
            var resource = K8sClient.class.getClassLoader().getResource(resourcePath);
            if (resource == null) {
                throw new RuntimeException("Resource not found on classpath: " + resourcePath);
            }
            // 如果是 jar 内资源，URL 会带 "jar:file:!" —— 这种情况需要手动解包
            Path target = Files.createTempDirectory("tapdata-deploy-resource-");
            try (var stream = K8sClient.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    throw new RuntimeException("Cannot open stream for resource: " + resourcePath);
                }
                // 对于目录资源（如 helm/），Resource URL 无法直接返回 InputStream
                // 这里简化处理 —— 让调用方直接用本地文件系统路径（开发期），或在打包时用 unpack
                log.warn("extractResourceToTempdir called on directory resource '{}' — " +
                        "打包到 jar 后需要改为 spring boot 的 resource loader 或 maven-dependency-plugin unpack。" +
                        "开发阶段可直接返回 classpath 绝对路径: {}", resourcePath, resource);
                return target.toString();
            }
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            throw new RuntimeException("Failed to extract resource: " + resourcePath, e);
        }
    }

    /**
     * 获取一个目录型资源在 classpath 中对应的文件系统路径（开发模式下）。
     * 打包成 jar 后返回 null，调用方应走 scp + jar 解压或提前 unpack。
     */
    public static Path getClasspathDir(String resourcePath) {
        try {
            var url = K8sClient.class.getClassLoader().getResource(resourcePath);
            if (url == null) return null;
            if ("file".equals(url.getProtocol())) {
                return Paths.get(url.toURI());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // ExecResult
    // ========================================================================

    public static class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public static ExecResult failure(int code, String errMsg, String stdout) {
            return new ExecResult(code, stdout == null ? "" : stdout, errMsg == null ? "" : errMsg);
        }

        /** 如果失败，抛异常把 stderr 带出去。 */
        public void assertSuccess(String context) {
            if (!isSuccess()) {
                throw new RuntimeException(context + " failed (exit=" + exitCode + "): " + stderr);
            }
        }

        public String getStdoutTrimmed() {
            return stdout == null ? "" : stdout.trim();
        }
    }
}
