package io.tapdata.it.config;

import io.tapdata.dbforge.sdk.CreateLeaseRequest;
import io.tapdata.dbforge.sdk.DbForgeClient;
import io.tapdata.dbforge.sdk.model.Connection;
import io.tapdata.dbforge.sdk.model.DbType;
import io.tapdata.dbforge.sdk.model.Endpoints;
import io.tapdata.dbforge.sdk.model.Lease;
import io.tapdata.dbforge.sdk.model.Mode;
import io.tapdata.entity.utils.DataMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 连接器集成测试的 DBForge 按需供应器：经 DBForge 控制面申请数据库租约，直接把租约暴露的
 * <b>{@code external_host:nodePort}</b> 回注为连接器连接配置（与静态 {@code *-connection.json}
 * 同构），连接器与旁路验证器按普通地址直连即可。本类<b>不内建任何端口转发 / 代理</b>。
 *
 * <p><b>四类运行场景。</b> 本类只管“申请租约 → 回注连接配置 → 续租 / 释放”，连接地址按场景适配：
 * <ol>
 *   <li><b>CI（自建 Runner 在公司内网）</b>：直达 k3s 集群，直连租约返回的内网 {@code external_host:nodePort}，无需转换。</li>
 *   <li><b>开发者在公司内网</b>：同场景 1，直连内网地址。</li>
 *   <li><b>开发者在公网外</b>：内网 IP 不可达，用 {@link #ENV_HOST_MAPPING} 启用 <b>host 映射表</b>，
 *       把租约返回的内网 host（如 {@code 192.168.1.193}）转换为可达的公网 host（如 {@code 113.98.206.138}），
 *       端口（NodePort）不变。</li>
 *   <li><b>开发者用本地数据库</b>：不设 {@link #ENV_ENDPOINT}，{@link #enabled()} 为 false，调用方回退静态
 *       {@code config/<db>-connection.json}，本类不参与。</li>
 * </ol>
 *
 * <p><b>启用与门控。</b> 仅当环境变量 {@link #ENV_ENDPOINT} 非空时启用（{@link #enabled()}）。
 * 凭证、host 覆盖、TTL 等均可用环境变量覆盖（沿用 dbforge SDK / 引擎 IT 既有 {@code DBF_IT_*} 约定）：
 * <ul>
 *   <li>{@link #ENV_ENDPOINT} —— 控制面地址，如 {@code http://localhost:30880}（设置即启用；该地址本身
 *       如需隧道由使用者预先建好，与本类无关）</li>
 *   <li>{@link #ENV_ADMIN_KEY} / {@link #ENV_TOKEN} —— 凭证，二选一（Admin Key 优先）</li>
 *   <li>{@link #ENV_HOST_MAPPING} —— 可选（场景 3），启用内网→公网 host 映射：值为 {@code true} 用内置默认
 *       {@value #DEFAULT_HOST_MAPPING}，或指定其它 {@code .properties} 路径；不设则不转换（场景 1/2/4）</li>
 *   <li>{@link #ENV_MODE} —— 可选，指定申请模式（{@code group=mode[,...]} 或裸模式通配）；内置默认
 *       {@code mongodb=dedicated}（本环境无 mongodb resident，shared 会 503）</li>
 *   <li>{@link #ENV_NODE_HOST} —— 可选，单值强制覆盖租约 {@code external_host}（优先级高于映射，调试用）</li>
 *   <li>{@link #ENV_TTL_MINUTES} —— 租约 TTL 分钟（默认 {@value #DEFAULT_TTL_MINUTES}，受服务端 MaxTTL 上限约束）</li>
 * </ul>
 *
 * <p><b>按连接器形态定制回注。</b> 与引擎 IT 不同，连接器 IT 的配置直接喂给连接器的连接表单
 * （DataMap → Config 类），故回注的键集合须与各 {@code *-connection.json} 对齐：
 * <ul>
 *   <li>JDBC 类（MySQL 等）：{@code host/port/user/password/database}，移除连接串（{@code jdbc_url}/{@code jdbcUrl}），
 *       与静态配置同构；MySQL 额外补 {@code highPerformance=true}（对齐静态配置，走 MysqlReaderV2 路径）；</li>
 *   <li>MongoDB：连接器 {@code MongodbConfig.isUri} 默认 true，只用连接串建连、忽略单独 user/password 字段，
 *       故必须用解析出的外部端点<b>重建内嵌凭证的 uri</b>——{@code authSource=库名}（专属实例的租户账号建在
 *       租户库而非 admin）+ {@code directConnection=true}（副本集成员登记为集群内地址，外部直连须跳过拓扑发现）；</li>
 *   <li>AS400/DB2i：补 {@code journalLibraryName}（缺省取库名）与 {@code journalName}（缺省 {@value #DEFAULT_JOURNAL_NAME}），
 *       与 db2i-connection.json 字段一致。</li>
 * </ul>
 *
 * <p><b>生命周期。</b> JVM 级单例：首个调用方触发初始化（建客户端 + 续租守护 + 关闭钩子）；每种数据库
 * <b>按类型幂等供应</b>——同一 JVM 内重复 {@link #provision(DbType)} 直接复用已申请的租约，不重复申请。
 * 关闭钩子在 JVM 退出时释放全部租约；守护线程按 {@value #RENEW_INTERVAL_MINUTES} 分钟续租，避免长测试
 * 超过单次 TTL 而中途失联。即使清理失败，服务端 TTL GC 也会兜底回收。
 *
 * <p>运行示例（控制面 {@code localhost:30880} 已可达）。场景 1/2（内网直连，无需转换）：
 * <pre>{@code
 * DBF_IT_ENDPOINT=http://localhost:30880 \
 * DBF_IT_TOKEN=dbf_live_xxx \
 * mvn verify -pl connectors/mysql-connector -DskipITs=false
 * }</pre>
 * 场景 3（公网外）：上面命令再加 {@code DBF_IT_HOST_MAPPING=true}（用内置映射 {@code 192.168.1.193 -> 113.98.206.138}）。
 *
 * <p>使用方：{@code ConnectorIT.readConnectionConfig(DbType, String)}——启用时走本类，否则回退
 * {@link ConnectionConfigLoader} 静态 JSON 链路。
 */
public final class DbForgeProvisioner implements AutoCloseable {

	// ===================== 环境变量（沿用 dbforge SDK / 引擎 IT 的 DBF_IT_* 约定） =====================

	/** 控制面地址；非空即启用 dbforge 动态供应（如 {@code http://localhost:30880}）。 */
	public static final String ENV_ENDPOINT = "DBF_IT_ENDPOINT";
	/** Admin Key 凭证（{@code X-DBForge-Admin-Key}）；与 {@link #ENV_TOKEN} 二选一，优先本项。 */
	public static final String ENV_ADMIN_KEY = "DBF_IT_ADMIN_KEY";
	/** 租户 Bearer Token 凭证；无 {@link #ENV_ADMIN_KEY} 时使用。 */
	public static final String ENV_TOKEN = "DBF_IT_TOKEN";
	/** 可选：覆盖租约 external_host（advertise VIP 从测试运行处不可达时，改指某个可达节点 IP）。 */
	public static final String ENV_NODE_HOST = "DBF_IT_NODE_HOST";
	/** 租约 TTL（分钟），受服务端 MaxTTL 上限约束。 */
	public static final String ENV_TTL_MINUTES = "DBF_IT_TTL_MINUTES";
	/**
	 * 可选（场景 3：公网外运行）：启用内网→公网 host 映射。值为 {@code true}/{@code 1}/{@code yes}/{@code on} 用内置
	 * 默认 {@value #DEFAULT_HOST_MAPPING}，或指定其它 {@code .properties} 路径；不设则不转换。
	 */
	public static final String ENV_HOST_MAPPING = "DBF_IT_HOST_MAPPING";
	/**
	 * 可选：指定申请模式（{@code mode}）。格式 {@code group=mode[,group=mode...]}（组名 = {@code dbType.value()}，
	 * 如 {@code mongodb=dedicated}），或裸模式名（如 {@code dedicated}，通配所有未单独指定的库）。不设则用
	 * {@link #DEFAULT_MODES}，仍未覆盖的库省略 mode 交由服务端取能力首位。
	 */
	public static final String ENV_MODE = "DBF_IT_MODE";

	/** 默认租约 TTL（分钟）：等于服务端默认 MaxTTL=60，长测试由续租守护线程兜住。 */
	private static final int DEFAULT_TTL_MINUTES = 60;
	/** 内置 host 映射文件（classpath 路径，随本模块 jar 发布）；{@link #ENV_HOST_MAPPING} 置 {@code true} 时使用。 */
	private static final String DEFAULT_HOST_MAPPING = "config/dbforge-host-mapping.properties";
	/**
	 * 内置供应模式默认：{@code mongodb → dedicated}。连接器 IT 的 Mongo 库需物理隔离的独立实例；而服务端能力清单
	 * 对 mongodb 首位是 {@code shared}（逻辑隔离、依赖 resident 实例），本环境未注册 mongodb resident，
	 * shared 会返回 {@code 503 CAPACITY_EXCEEDED}。故显式要 dedicated。可被 {@link #ENV_MODE} 覆盖。
	 */
	private static final Map<String, Mode> DEFAULT_MODES =
			Collections.singletonMap(DbType.MONGODB.value(), Mode.DEDICATED);
	/** {@link #ENV_MODE} 裸模式写法对应的通配键。 */
	private static final String MODE_WILDCARD = "*";
	/** AS400 缺省 journal 名（db2i 连接器默认约定，租约未返回 journalName 时兜底）。 */
	private static final String DEFAULT_JOURNAL_NAME = "QSQJRN";
	/** 续租间隔（分钟）：小于单次 TTL，保证过期前必有续租把 expires_at 推到后面。 */
	private static final int RENEW_INTERVAL_MINUTES = 15;
	/** 每次续租延长的分钟数（≤ 服务端 MaxRenewMinutes=60）。 */
	private static final int RENEW_EXTEND_MINUTES = 30;

	/** JVM 级单例（首个调用方触发创建，随 JVM 退出经关闭钩子清理）。 */
	private static volatile DbForgeProvisioner instance;

	/** 已申请租约，按组名（= {@code dbType.value()}）索引；用于续租与退出释放。 */
	private final Map<String, Lease> leasesByGroup = new LinkedHashMap<>();
	/**
	 * 供应结果：组名（= {@code dbType.value()}）→ 单库连接配置（与静态 {@code *-connection.json} 同构）。
	 * 某组存在即代表该库已<b>完整</b>供应成功（租约 active + 配置写好），故兼作按组幂等复用的标记。
	 */
	private final Map<String, DataMap> configByGroup = new LinkedHashMap<>();

	private DbForgeClient client;
	private String nodeHostOverride;
	/** 内网 host → 可达 host 映射（场景 3）；未启用时为空表，{@link #resolveExternalHost} 原样返回。 */
	private Map<String, String> hostMapping = Collections.emptyMap();
	/** 供应模式覆盖（组名→mode，含 {@code "*"} 通配）；由 {@link #DEFAULT_MODES} 与 {@link #ENV_MODE} 合并而来。 */
	private Map<String, Mode> modeByGroup = Collections.emptyMap();
	private int ttlMinutes;
	private Thread renewer;
	private boolean closed;

	private DbForgeProvisioner() {
	}

	/** dbforge 动态供应是否启用：{@link #ENV_ENDPOINT} 非空即启用，否则调用方回退静态 JSON 配置。 */
	public static boolean enabled() {
		String endpoint = System.getenv(ENV_ENDPOINT);
		return endpoint != null && !endpoint.isEmpty();
	}

	/**
	 * 按需供应一种数据库，返回与静态 {@code *-connection.json} 同构的连接配置
	 * （JDBC 类：host/port/user/password/database；MongoDB：uri/database；AS400：另含 journal*）。
	 * <p>
	 * 首次调用初始化单例（建客户端 + 续租守护 + 关闭钩子）；同组<b>幂等</b>——同一 JVM 内重复调用
	 * （或多个测试类依赖同类型库）直接复用同一租约，不重复申请。
	 *
	 * @param dbType 本测试依赖的数据库类型（不可为 null）
	 * @return 该库的连接配置（内部 key 无组名包装，可直接用作连接器 connectionConfig）
	 * @throws IllegalArgumentException dbType 为 null
	 * @throws IllegalStateException    启用前提缺失（凭证未配置）或申请租约失败
	 */
	public static DataMap provision(DbType dbType) {
		if (dbType == null) {
			throw new IllegalArgumentException("provision 需要指定 DbType（如 DbType.MYSQL）");
		}
		DbForgeProvisioner p = instance();
		// 实例锁串行化"申请租约 + 写配置"，与续租守护/关闭钩子共用同一把锁。
		synchronized (p) {
			p.provisionIfAbsent(dbType);
			return p.configByGroup.get(dbType.value());
		}
	}

	/** 取（并在首次调用时初始化）JVM 级单例；初始化在类锁内完成，发布前已建好客户端与守护线程。 */
	private static DbForgeProvisioner instance() {
		DbForgeProvisioner p = instance;
		if (p == null) {
			synchronized (DbForgeProvisioner.class) {
				p = instance;
				if (p == null) {
					p = new DbForgeProvisioner();
					p.init();
					// 初始化成功后再发布，避免其他线程看到半成品单例。
					instance = p;
					// 发布后注册清理钩子：即便后续供应中途失败，已申请的租约仍能在 JVM 退出时回收。
					Runtime.getRuntime().addShutdownHook(new Thread(p::close, "dbforge-it-cleanup"));
				}
			}
		}
		return p;
	}

	// ===================== 初始化 =====================

	private void init() {
		nodeHostOverride = System.getenv(ENV_NODE_HOST);
		hostMapping = loadHostMapping();
		modeByGroup = loadModeOverrides();
		ttlMinutes = Integer.parseInt(env(ENV_TTL_MINUTES, String.valueOf(DEFAULT_TTL_MINUTES)));
		client = buildClient();
		startRenewer();
	}

	private DbForgeClient buildClient() {
		DbForgeClient.Builder b = DbForgeClient.builder()
				.endpoint(System.getenv(ENV_ENDPOINT))
				// 申请为阻塞式（服务端供应至 READY 才返回），给足预算。
				.provisionTimeout(Duration.ofMinutes(15));
		String adminKey = System.getenv(ENV_ADMIN_KEY);
		String token = System.getenv(ENV_TOKEN);
		if (adminKey != null && !adminKey.isEmpty()) {
			b.adminKey(adminKey);
		} else if (token != null && !token.isEmpty()) {
			b.token(token);
		} else {
			throw new IllegalStateException("dbforge 已启用（" + ENV_ENDPOINT + " 非空）但缺少凭证："
					+ "请设置 " + ENV_ADMIN_KEY + " 或 " + ENV_TOKEN);
		}
		return b.build();
	}

	// ===================== 供应主流程（按组幂等） =====================

	/**
	 * 供应一种数据库（组名 = {@code dbType.value()}）：已完整供应过则复用；否则申请租约 → 解析 external
	 * 端点 → 写入连接配置。mode 经 {@link #resolveMode} 解析（mongodb 默认 dedicated，其余可经 {@link #ENV_MODE}
	 * 指定；都未覆盖则留空由服务端取能力首位）；topology 一律留空由服务端从能力矩阵推断。调用方须持有本实例锁。
	 */
	private void provisionIfAbsent(DbType dbType) {
		String group = dbType.value();
		// configByGroup 含该组即代表已完整供应成功（见字段注释），直接复用，不再申请。
		if (configByGroup.containsKey(group)) {
			return;
		}
		Mode mode = resolveMode(dbType);
		CreateLeaseRequest.Builder rb = CreateLeaseRequest.builder()
				.dbType(dbType)
				.ttlMinutes(ttlMinutes)
				.owner("connector-it")
				.idempotencyKey("connector-it-" + group + "-" + Long.toHexString(System.nanoTime()));
		if (mode != null) {
			rb.mode(mode);
		}
		Lease lease = client.createLease(rb.build());
		if (lease == null || !lease.isActive()) {
			throw new IllegalStateException("dbforge 申请 " + group + " 租约未进入 active："
					+ (lease == null ? "null" : lease.getStatus()));
		}
		// 登记租约（供续租 / 退出释放）。
		leasesByGroup.put(group, lease);

		Connection conn = lease.requireConnection();
		String host = resolveExternalHost(conn);
		int port = resolveExternalPort(conn);

		// 写入配置：此步完成即标记该组"完整可用"（按组幂等复用的依据）。
		DataMap config = DataMap.create();
		config.putAll(buildGroupConfig(dbType, conn, host, port));
		configByGroup.put(group, config);

		System.out.println("[dbforge-it] " + group + " lease=" + lease.getLeaseId()
				+ " mode=" + (mode == null ? "<server-default>" : mode.value())
				+ " endpoint=" + host + ":" + port + " database=" + conn.getDatabase());
	}

	/**
	 * 由租约连接描述构造单库连接配置，基底为 SDK {@link Connection#toMap()}（按 db 类型自动带出
	 * {@code timezone}/{@code ssl}/{@code journal*} 等字段），再按连接器形态定制：
	 * <ul>
	 *   <li>{@code host} / {@code port} 取解析出的租约端点（{@code external_host:nodePort}，host 可经
	 *       {@link #ENV_HOST_MAPPING} 映射或 {@link #ENV_NODE_HOST} 覆盖）；</li>
	 *   <li>核心五键 {@code host/port/user/password/database} 归一（缺失补空串），与静态配置取值行为一致；</li>
	 *   <li>移除 JDBC 连接串（{@code jdbc_url}/{@code jdbcUrl}）与服务端原始 {@code uri}/{@code isUri}
	 *       （串内主机为集群内地址，与解析端点不一致；Mongo 由 {@link #customizeForConnector} 用外部端点重建）；</li>
	 *   <li>类型特化见 {@link #customizeForConnector}（Mongo 重建内嵌凭证 uri、MySQL 补 highPerformance、
	 *       AS400 补 journal 字段）。</li>
	 * </ul>
	 */
	private Map<String, Object> buildGroupConfig(DbType dbType, Connection conn, String host, int port) {
		Map<String, Object> node = new LinkedHashMap<>(conn.toMap());
		node.put("host", host);
		node.put("port", port);
		node.put("user", nullToEmpty(conn.getUser()));
		node.put("password", nullToEmpty(conn.getPassword()));
		node.put("database", nullToEmpty(conn.getDatabase()));
		node.remove("jdbc_url");
		node.remove("jdbcUrl");
		node.remove("uri");
		node.remove("isUri");
		customizeForConnector(dbType, conn, node, host, port);
		return node;
	}

	/** 按连接器消费方式做类型特化（详见 {@link #buildGroupConfig} 注释）。 */
	private static void customizeForConnector(DbType dbType, Connection conn,
			Map<String, Object> node, String host, int port) {
		switch (dbType) {
			case MONGODB:
				// MongodbConfig.isUri 默认 true：只用连接串建连、忽略单独 user/password 字段。
				// 租约原始 uri 指向集群内地址，外部不可达且可能与解析端点不一致，必须用
				// host:port + 内嵌凭证重建；authSource=库名（专属实例账号建在租户库），
				// directConnection=true（副本集成员登记为集群内地址，直连须跳过拓扑发现）。
				node.put("uri", buildMongoUri(conn, host, port));
				node.put("isUri", true);
				break;
			case MYSQL:
				// 对齐静态 mysql-connection.json：走 MysqlReaderV2 高性能读取路径
				node.put("highPerformance", true);
				break;
			case AS400:
				// db2i 连接器 CDC 依赖 journal 配置；租约描述符缺省时按库名 / QSQJRN 兜底
				Object journalLibrary = node.get("journalLibraryName");
				if (journalLibrary == null || String.valueOf(journalLibrary).isEmpty()) {
					node.put("journalLibraryName", nullToEmpty(conn.getDatabase()));
				}
				Object journal = node.get("journalName");
				if (journal == null || String.valueOf(journal).isEmpty()) {
					node.put("journalName", DEFAULT_JOURNAL_NAME);
				}
				break;
			default:
				// 其余 JDBC 类连接器：核心五键 + toMap 附加字段已足够，无需特化
				break;
		}
	}

	/**
	 * 重建 MongoDB 连接串：{@code mongodb://[user[:pass]@]host:port/database?params}。
	 * 凭证字母表为纯字母数字（dbforge 生成），无需百分号转义。保留租约原始 uri 的 query 参数，
	 * 但 {@code authSource}/{@code directConnection} 以本方法解析值为准。
	 */
	private static String buildMongoUri(Connection conn, String host, int port) {
		String user = conn.getUser();
		String password = conn.getPassword();
		String database = nullToEmpty(conn.getDatabase());
		StringBuilder sb = new StringBuilder("mongodb://");
		if (user != null && !user.isEmpty()) {
			sb.append(user);
			if (password != null && !password.isEmpty()) {
				sb.append(':').append(password);
			}
			sb.append('@');
		}
		sb.append(host).append(':').append(port).append('/').append(database);
		Map<String, String> params = new LinkedHashMap<>();
		// 保留原始 uri 的自定义 query 参数（authSource/directConnection 由下面强制覆盖，先剔除）
		String uri = conn.getUri();
		if (uri != null) {
			int q = uri.indexOf('?');
			if (q >= 0) {
				for (String pair : uri.substring(q + 1).split("&")) {
					String[] kv = pair.split("=", 2);
					if (kv.length == 2 && !"authSource".equals(kv[0]) && !"directConnection".equals(kv[0])) {
						params.put(kv[0], kv[1]);
					}
				}
			}
		}
		if (user != null && !user.isEmpty()) {
			// 专属实例的租户账号建在租户库（非 admin），鉴权库须指向该库
			params.put("authSource", database);
		}
		params.put("directConnection", "true");
		sb.append('?');
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) {
				sb.append('&');
			}
			sb.append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}
		return sb.toString();
	}

	/**
	 * 解析最终用于连接的 host，优先级：
	 * <ol>
	 *   <li>{@link #ENV_NODE_HOST} 单值强制覆盖（调试用）；</li>
	 *   <li>租约原始 external_host（endpoints.external_host，其次顶层 host）经 {@link #hostMapping} 映射
	 *       （场景 3：内网 host → 公网 host；未启用或无匹配则原样返回）。</li>
	 * </ol>
	 */
	private String resolveExternalHost(Connection conn) {
		if (nodeHostOverride != null && !nodeHostOverride.isEmpty()) {
			return nodeHostOverride;
		}
		String rawHost = rawExternalHost(conn);
		String mapped = hostMapping.get(rawHost);
		if (mapped != null && !mapped.isEmpty()) {
			System.out.println("[dbforge-it] host 映射: " + rawHost + " -> " + mapped);
			return mapped;
		}
		return rawHost;
	}

	/** 租约原始 external_host：优先 endpoints.external_host，其次顶层 host。 */
	private String rawExternalHost(Connection conn) {
		Endpoints ep = conn.getEndpoints();
		if (ep != null && ep.getExternalHost() != null && !ep.getExternalHost().isEmpty()) {
			return ep.getExternalHost();
		}
		if (conn.getHost() != null && !conn.getHost().isEmpty()) {
			return conn.getHost();
		}
		throw new IllegalStateException("dbforge 连接未返回可用 host: " + conn);
	}

	/** 租约 external_port（动态 NodePort）：优先 endpoints.external_port，其次顶层 port。 */
	private int resolveExternalPort(Connection conn) {
		Endpoints ep = conn.getEndpoints();
		if (ep != null && ep.getExternalPort() != null) {
			return ep.getExternalPort();
		}
		if (conn.getPort() != null) {
			return conn.getPort();
		}
		throw new IllegalStateException("dbforge 连接未返回可用 port: " + conn);
	}

	// ===================== 供应模式（mode）解析 =====================

	/**
	 * 解析某库的申请模式：{@link #modeByGroup} 命中组名用其值，否则用 {@code "*"} 通配值，都无则返回
	 * {@code null}（请求体省略 mode，交由服务端取能力清单首位）。
	 */
	private Mode resolveMode(DbType dbType) {
		Mode m = modeByGroup.get(dbType.value());
		return m != null ? m : modeByGroup.get(MODE_WILDCARD);
	}

	/**
	 * 合并内置默认（{@link #DEFAULT_MODES}）与 {@link #ENV_MODE} 覆盖，得到组名→模式表（含 {@code "*"} 通配）。
	 * {@link #ENV_MODE} 支持 {@code group=mode[,group=mode...]}（按库指定）或裸 {@code mode}（通配全部）；
	 * 未知模式名直接抛错，避免拼写错误静默失效。
	 */
	private static Map<String, Mode> loadModeOverrides() {
		Map<String, Mode> map = new LinkedHashMap<>(DEFAULT_MODES);
		String spec = System.getenv(ENV_MODE);
		if (spec != null && !spec.trim().isEmpty()) {
			String s = spec.trim();
			if (s.indexOf('=') < 0) {
				map.put(MODE_WILDCARD, parseMode(s));
			} else {
				for (String pair : s.split(",")) {
					if (pair.trim().isEmpty()) {
						continue;
					}
					String[] kv = pair.split("=", 2);
					if (kv.length != 2 || kv[0].trim().isEmpty()) {
						throw new IllegalArgumentException(ENV_MODE + " 片段应为 group=mode，实为: " + pair);
					}
					map.put(kv[0].trim(), parseMode(kv[1].trim()));
				}
			}
		}
		System.out.println("[dbforge-it] 供应模式覆盖（组→mode，*=通配；未列出的库交服务端默认）: " + map);
		return map;
	}

	private static Mode parseMode(String v) {
		Mode m = Mode.fromValueOrNull(v);
		if (m == null) {
			throw new IllegalArgumentException("未知 mode: " + v + "（可选 dedicated / shared）");
		}
		return m;
	}

	// ===================== host 映射（场景 3：公网外，内网 host → 公网 host） =====================

	/**
	 * 加载 host 映射表（{@link #ENV_HOST_MAPPING} 控制）：未设置 → 空表（场景 1/2/4，不转换）；
	 * {@code true}/{@code 1}/{@code yes}/{@code on} → 内置默认 {@value #DEFAULT_HOST_MAPPING}；其它 → 视为
	 * {@code .properties} 路径（classpath 优先，其次文件系统）。文件为 {@code 内网host=可达host} 行，{@code #} 为注释。
	 * 文件缺失或加载失败直接抛错——避免“以为映射了其实没有”而连到不可达的内网 IP。
	 */
	private static Map<String, String> loadHostMapping() {
		String spec = System.getenv(ENV_HOST_MAPPING);
		if (spec == null || spec.trim().isEmpty()) {
			return Collections.emptyMap();
		}
		String path = isTruthy(spec) ? DEFAULT_HOST_MAPPING : spec.trim();
		Properties props = new Properties();
		try (InputStream in = openResource(path)) {
			if (in == null) {
				throw new IllegalStateException(ENV_HOST_MAPPING + " 指定的 host 映射文件未找到: " + path);
			}
			props.load(in);
		} catch (IOException e) {
			throw new IllegalStateException("加载 host 映射文件失败: " + path, e);
		}
		Map<String, String> map = new LinkedHashMap<>();
		for (String key : props.stringPropertyNames()) {
			String value = props.getProperty(key);
			if (!key.trim().isEmpty() && value != null && !value.trim().isEmpty()) {
				map.put(key.trim(), value.trim());
			}
		}
		System.out.println("[dbforge-it] host 映射已启用（" + path + "）: " + (map.isEmpty() ? "(无有效规则)" : map));
		return map;
	}

	/** 是否“启用默认映射”的真值写法（true/1/yes/on，大小写不敏感）。 */
	private static boolean isTruthy(String v) {
		String s = v.trim();
		return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
	}

	/** 打开资源：classpath 优先，其次文件系统；均不存在返回 null（由调用方决定报错）。 */
	private static InputStream openResource(String path) {
		InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
		if (in != null) {
			return in;
		}
		File file = new File(path);
		if (file.isFile()) {
			try {
				return new FileInputStream(file);
			} catch (IOException e) {
				return null;
			}
		}
		return null;
	}

	// ===================== 续租守护 =====================

	/** 守护线程周期性续租，避免长测试超过单次 TTL 中途失联；不阻塞 JVM 退出，续租失败静默（TTL GC 兜底）。 */
	private void startRenewer() {
		renewer = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				sleep(TimeUnit.MINUTES.toMillis(RENEW_INTERVAL_MINUTES));
				if (Thread.currentThread().isInterrupted()) {
					return;
				}
				// 与供应 / 关闭共用实例锁取快照，避免与 leasesByGroup 的写入 / 清空并发。
				List<Lease> snapshot;
				synchronized (this) {
					snapshot = new ArrayList<>(leasesByGroup.values());
				}
				for (Lease lease : snapshot) {
					try {
						lease.renew(RENEW_EXTEND_MINUTES);
					} catch (RuntimeException ignore) {
						// 续租失败不影响主流程：租约未过期仍可用，过期则由服务端 TTL GC 回收。
					}
				}
			}
		}, "dbforge-it-renewer");
		renewer.setDaemon(true);
		renewer.start();
	}

	// ===================== 清理 =====================

	/** 释放全部租约（幂等，可安全作为关闭钩子重复调用）。 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (renewer != null) {
			renewer.interrupt();
			renewer = null;
		}
		for (Lease lease : leasesByGroup.values()) {
			try {
				lease.release();
			} catch (RuntimeException ignore) {
				// best-effort：释放失败由服务端 TTL GC 兜底回收。
			}
		}
		leasesByGroup.clear();
	}

	// ===================== 小工具 =====================

	private static String env(String name, String defaultValue) {
		String v = System.getenv(name);
		return (v == null || v.isEmpty()) ? defaultValue : v;
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
