


## 部署

### 自定义配置
```yaml
mongodb:
  replicaSet:
    statefulSet:
      volumeClaimTemplates:
        - name: data-volume
          storageClassName: "ssd" ## 设置 storageClassName 为 ssd
          accessModes:
            - ReadWriteOnce
          storage: 10Gi
        - name: logs-volume
          storageClassName: "ssd"
          accessModes:
            - ReadWriteOnce
          storage: 2Gi
```

```shell
helm install tapdata . --namespace tapdata -f values-huawei.yaml
```

## 卸载
```shell
helm uninstall tapdata -n tapdata
```


## 启动顺序

```
MongoDB (Operator 创建) 
  → tapdata-server (Init: 等待 MongoDB:27017)
    → tapdata-engine (Init: 等待 tapdata-server-svr:3030)
    → tapdata-apiserver (Init: 等待 tapdata-server-svr:3030)
```