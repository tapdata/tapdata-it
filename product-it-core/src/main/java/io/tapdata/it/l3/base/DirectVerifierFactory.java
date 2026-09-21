package io.tapdata.it.l3.base;

import io.tapdata.entity.utils.DataMap;
import io.tapdata.it.verifier.ConnectorVerifier;
import io.tapdata.it.verifier.JdbcVerifier;
import io.tapdata.it.verifier.MongoVerifier;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoClient;

/**
 * 直连验证器工厂（L3 专用）：与 {@code VerifierFactory}（从 connector 实例反射装配）不同，
 * L3 面对的是<b>已部署的完整产品</b>，测试进程内没有引擎的 connector 实例，也无引擎生命周期竞态，
 * 因此直接以连接配置（{@link DataMap}）建立旁路 JDBC/Mongo 验证器，用于：
 * <ul>
 *   <li>任务下发<b>前</b>建源表 / 造数（快照读必读到完整数据）；</li>
 *   <li>任务完成<b>后</b>旁路核对目标数据（不依赖任何引擎内资源，符合"禁止用 read 验 write"）。</li>
 * </ul>
 * 复用 {@code tapdata-it-common} 的 {@link JdbcVerifier}/{@link MongoVerifier}，仅替换其装配来源。
 */
public final class DirectVerifierFactory {

    private DirectVerifierFactory() {
    }

    /**
     * 依据连接配置自动选择验证器实现：
     * <ul>
     *   <li>含 {@code jdbcUrl}（或 {@code url}）→ JDBC（MySQL/Postgres 等）；</li>
     *   <li>含 {@code uri}（{@code mongodb://} 或 {@code mongodb+srv://}）→ MongoDB。</li>
     * </ul>
     */
    public static ConnectorVerifier create(DataMap connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection DataMap must not be null");
        }
        String uri = connection.getString("uri");
        if (uri != null && (uri.startsWith("mongodb://") || uri.startsWith("mongodb+srv://"))) {
            return createMongo(uri, firstNonNull(connection.getString("database"), connection.getString("db")));
        }
        String jdbcUrl = firstNonNull(connection.getString("jdbcUrl"), connection.getString("url"));
        if (jdbcUrl != null) {
            return createJdbc(jdbcUrl,
                    firstNonNull(connection.getString("user"), connection.getString("username")),
                    connection.getString("password"));
        }
        throw new IllegalArgumentException(
                "Unrecognized connection config: expect 'jdbcUrl'/'url' (JDBC) or 'uri' (mongodb)");
    }

    /** 直连 JDBC 验证器（复用 {@link JdbcVerifier} 的 {@link javax.sql.DataSource} 构造通道）。 */
    public static ConnectorVerifier createJdbc(String jdbcUrl, String user, String password) {
        return new JdbcVerifier(new JdbcVerifier.DriverManagerDataSource(jdbcUrl, user, password));
    }

    /** 直连 MongoDB 验证器（自建 {@link MongoClient}，独立于引擎生命周期）。 */
    public static ConnectorVerifier createMongo(String uri, String database) {
        MongoClient client = MongoClients.create(uri);
        return new MongoVerifier(client, database);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
