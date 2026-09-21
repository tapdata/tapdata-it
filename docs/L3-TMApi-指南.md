# L3 TM API 客户端使用与扩展指南

本文面向 **在 `product-it-core` 之上编写 L3 用例的开发者**，说明如何用类型化 API 与已部署的
TapData 管理端（TM）交互，以及如何为新增的 Controller 扩展对应 API 类。

## 1. 分层与入口

```
TmApiClient           # 认证 + 传输上下文装配 + 各 Controller 的 API 访问器
  └─ TmApiContext     # 持有 tm-sdk TmAvailableRestTemplate（含 TM 探活）+ JDK HttpClient（支持 PATCH）
       └─ VersionHeaderInterceptor                    # 版本 UA（TM 认 query 参数 access_token，不认 header）
AbstractTmApi         # 内聚 HTTP 动词 + TmResponse 拆包（code!=ok 抛 TmApiException）
  ├─ TaskAPI          # TaskController   (/api/task)
  ├─ DataSourceAPI    # DataSourceController (/api/v2/ds)
  ├─ PdkAPI           # PdkController    (/api/pdk)
  ├─ InspectAPI       # InspectController (/api/Inspects)
  ├─ MetadataAPI      # MetadataController (/api/MetaData)
  └─ ClusterAPI       # ClusterStateController (/api/clusterStates)
```

> 传输层的 `baseUrl` 形如 `http://<nodeIP>:<nodePortTM>/api`（由 `EnvironmentManager` 提供），
> 各 API 只需声明相对 `/api` 的段（如 `/task`）。

## 2. 建立客户端与认证

三种取得 `access_token` 的方式，任选其一：

> **认证口径**：TM 的 `LoginUserResolver` 只认查询参数 `access_token`，不认 `access-token` Header；
> 因此框架把 token 拼到每个请求地址上（登录类接口无 token 时自动跳过）。

```java
// 1) 账密登录（口令按 TM 前端一致的 RC4 加密后提交）
TmApiClient tm = TmApiClient.at("http://10.0.0.5:30080/api").login("admin@admin.com", "admin");

// 2) 预置 token 直连（独立进程反复测同一环境最省事，跳过登录）
TmApiClient tm = TmApiClient.at(baseUrl).withToken(presetToken);

// 3) accessCode 换 token
TmApiClient tm = TmApiClient.at(baseUrl).loginByAccessCode(accessCode);
```

在 `ProductIT` 基类中，上述步骤已由 `@BeforeAll` 自动完成，用例直接使用方法 `tm()` 获得已登录客户端。

## 3. 典型调用

```java
// 建数据源：连接体用轻量装配件（不依赖重量级 tm-api DataSourceDto）
String srcId = tm.dataSourceApi().create(
        DataSourcePayload.source("mysql_src", "mysql")
                .config("host", "10.0.0.2").config("port", 3306)
                .config("user", "root").config("password", "pw")
                .config("database", "demo"));

// 建任务：复杂带多态 DAG 的建任务体推荐用版本无关的 JSON 模板字符串
String taskId = tm.taskApi().save(taskJsonTemplate);
tm.taskApi().confirm(taskId, confirmJsonTemplate);
tm.taskApi().start(taskId);

// 轮询状态（读侧统一以 Map 承载，规避 DAG/Node 抽象类型反序列化风险）
String status = tm.taskApi().status(taskId);

// 列表 + filter（TmFilter 组装 ?filter= JSON）
List<Map<String, Object>> tasks = tm.taskApi().list(
        TmFilter.where("status", "processing").limit(50));
```

## 4. 请求体：DTO 还是 JSON 模板？

`AbstractTmApi` 的写方法接受 `Object body`：

- **`String`**：视为原样 JSON（推荐用于带多态 `DAG`/`Node` 的建任务体，框架不臆测服务端节点类型判定）。
- **其它对象（如 tm-common `TaskDto`、`Map`、`DataSourcePayload`）**：经 `TmJson` 序列化。

> tm-common `TaskDto` 的 `id` 为 `ObjectId`，其字段自带 `@JsonSerialize/@JsonDeserialize`，
> 且 `TmJson` 额外注册了 `ObjectId ↔ 十六进制串`（兼容 `{"$oid":..}`）以对齐 TM 口径。
> **读**一个含完整 DAG 的任务时不要用 `TaskDto` 反序列化（抽象 `Node` 无 Jackson 类型信息会失败），
> 统一用 `Map<String,Object>`（API 已如此封装），仅按需读取标量字段。

## 5. 拆包与错误

`ResponseMessage`（TM Web 层）在线上是 `{code, message/msg, data, reqId}`。框架用本地
`TmResponse<T>` 映射，`code` 非 `"ok"`/空即抛 `TmApiException`（携带 `code`）。分页 `Page` 映射为
`TmPage<T>`（`items`/`total`）。用例无需自行判 `code`。

## 6. 为新 Controller 扩展 API（"每 Controller 一 API"）

1. 在 `manager/tm/.../<domain>/controller/XxxController.java` 确认 `@RequestMapping` base 段与目标端点
   （HTTP 方法、路径、`@RequestParam`、`@RequestBody`、返回 `ResponseMessage<...>`）。
2. 在 `io.tapdata.it.l3.api` 新建 `XxxAPI extends AbstractTmApi`：
   - `private static final String BASE = "/xxx";`（相对 `/api`）
   - 复用基类的 `get/post/put/patch/delete` 与 `data(...)`/`call(...)`；
   - 读用 `TypeReference<TmResponse<Map<String,Object>>>` / `...TmPage<Map<...>>`；写返回 `extractId(...)`。
3. 在 `TmApiClient` 增加懒加载访问器 `xxxApi()`（与现有访问器同构）。
4. **不要**在本框架重复实现 HTTP/拆包/认证细节——它们已由 `TmApiContext`/`AbstractTmApi` 内聚。

## 7. 契约锚点（校验端点时查阅）

- 登录/换 token：`UserController` `POST /api/users/login`、`POST /api/users/generatetoken`（`@IgnoreLogin`）。
- 任务：`TaskController` `@RequestMapping({"/api/Task","/api/task"})`：`save` POST、`confirm/{id}` PATCH、
  `start/{id}` PUT、`stop/{id}` PUT、`{id}` GET/DELETE、`` GET `?filter=`。
- 数据源：`DataSourceController` `@RequestMapping({"api/Connections","/api/v2/ds"})`。
- PDK：`PdkController` `/api/pdk`：`upload/source`（multipart）、`jar/v2`、`checkMd5/v3`。
