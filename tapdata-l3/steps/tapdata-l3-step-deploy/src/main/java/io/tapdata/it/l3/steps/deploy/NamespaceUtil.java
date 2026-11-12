package io.tapdata.it.l3.steps.deploy;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 生成不重复的 K8s namespace —— 格式 {@code tapdata-YYYYMMDD-HHmmss-xxxxxx}。
 * 如：{@code tapdata-20261106-143022-a7f3k9}。
 * <p>
 * 用户通过 DSL 显式传入 {@code namespace} 时优先用用户值，不调用本类。
 */
public final class NamespaceUtil {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final SecureRandom RND = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private NamespaceUtil() {}

    public static String generate() {
        String ts = LocalDateTime.now().format(FMT);
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RND.nextInt(ALPHABET.length())));
        }
        return "tapdata-" + ts + "-" + sb;
    }

    /**
     * 如果用户传了 namespace 直接返回；否则生成新的。
     */
    public static String resolve(String userProvided) {
        if (userProvided != null && !userProvided.isBlank()) {
            return userProvided.trim();
        }
        return generate();
    }
}
