package io.tapdata.it.l3.steps.deploy;

import io.tapdata.it.l3.common.ParameterConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * deploy_tapdata 参数。
 * <p>
 * 必填：artifact_url / harbor_registry / mongodb_uri。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DeployTapdataParameter extends ParameterConfig {

    /** tapdata tar.gz 制品下载 URL — 必填。 */
    private String artifactUrl;

    /** Harbor 仓库地址（如 harbor.example.com）— 必填。 */
    private String harborRegistry;

    /** Harbor 项目名 — 默认 tapdata。 */
    private String harborProject = "tapdata";

    /** Harbor 用户名（私有仓库 docker login 用）。 */
    private String harborUsername;

    /** Harbor 密码。 */
    private String harborPassword;

    /** 镜像 tag — 不传则从 artifactUrl 文件名推断，再不行用 latest。 */
    private String imageTag;

    /** K8s namespace — 不传则自动生成不重复的。 */
    private String namespace;

    /** Helm release 名称 — 默认 tapdata。 */
    private String helmRelease = "tapdata";

    /** MongoDB 连接串（完整 URI）— 必填。 */
    private String mongodbUri;

    /** MongoDB 用户 — 默认 root。 */
    private String mongodbUser = "root";

    /** MongoDB 密码。 */
    private String mongodbPassword;

    /** MongoDB 多副本 hosts（逗号分隔），不传则从 mongodbUri 推断。 */
    private String mongodbHosts;

    /** TM 元数据库名 — 默认 tapdata。dbforge 租约场景需指定为租约 database（用户仅对该库有权限）。 */
    private String mongodbDatabase = "tapdata";

    /** MongoDB 认证库 — 默认 admin。dbforge 租约场景需指定为租约 database（用户创建在该库下）。 */
    private String mongodbAuthSource = "admin";

    /** tapdata-server 副本数 — 默认 1。 */
    private Integer serverReplicas = 1;

    /** tapdata-engine 副本数 — 默认 1。 */
    private Integer engineReplicas = 1;

    /** tapdata-apiserver 副本数 — 默认 1。 */
    private Integer apiserverReplicas = 1;

    /**
     * engine / apiserver 访问 TM 的 backendUrl。
     * <p>
     * 默认 {@code http://tapdata-server-svr:3030/api/}（同 namespace 短服务名）；
     * 传 svc 全域名可显式验证 K8s 服务发现，例如
     * {@code http://tapdata-server-svr.<namespace>.svc.cluster.local:3030/api/}。
     */
    private String backendUrl;

    /** 远程 K8s 服务器 host — 默认 113.98.206.142。 */
    private String host = "113.98.206.142";

    /** 远程已有的自定义 Dockerfile 路径（绝对路径）。不传则使用内置模板。 */
    private String dockerfilePath;

    /** 远程 Helm chart 目录路径。不传则内置 deploy-on-kubernetes 资源。 */
    private String helmChartPath;

    /** License 服务器地址。 */
    private String licenseHost;

    /** License 内容。 */
    private String license;

    /** PVC storageClassName。 */
    private String storageClass;

    /** 镜像拉取 Secret 名称 — 不传则自动创建。 */
    private String imagePullSecret;

    /**
     * 预构建镜像名（完整 registry/repo:tag）。
     * <p>
     * 如果设置了此参数，跳过 artifact_url 下载 + docker build + docker push，
     * 直接用这个镜像名部署。适用于镜像已在 Harbor 上存在的场景。
     * <p>
     * 互斥：设置后 artifact_url 不再必填。
     */
    private String prebuiltImage;

    /**
     * 就绪断言的最少 Running Pod 数 — 默认 = server + engine + apiserver 副本之和。
     * 设为 0 可关闭计数断言（kubectl wait Ready 断言仍生效）。
     */
    private Integer minReadyPods;
}
