# Kubernetes 部署清单

> ⚠️ **诚实声明**：这些清单**没有在真实集群上验证过**（CI 里没有 K8s）。
> 它们是标准的 Deployment / Service / HPA 写法，可以作为起点，
> 但上线前必须 `kubectl --dry-run=server -f deploy/k8s/` 走一遍。

## 部署顺序

```bash
kubectl apply -f deploy/k8s/00-namespace.yaml
kubectl apply -f deploy/k8s/10-config.yaml     # 先把 REPLACE_ME 换成真实值
kubectl apply -f deploy/k8s/20-app.yaml
kubectl apply -f deploy/k8s/30-gateway.yaml
kubectl apply -f deploy/k8s/40-hpa.yaml
```

## 前置依赖

清单假设以下中间件已经在集群内（或可通过 DNS 访问）：

| 组件 | Service 名称 | 说明 |
|---|---|---|
| MySQL | `mysql.mall.svc.cluster.local` | 生产建议用云托管 RDS + 读写分离 |
| Redis | `redis.mall.svc.cluster.local` | 生产建议用集群版 |
| RabbitMQ | `rabbitmq.mall.svc.cluster.local` | 也可换 Kafka / RocketMQ |
| Elasticsearch | `elasticsearch.mall.svc.cluster.local` | 可选，连不上会自动降级 |
| OTLP Collector | `otel-collector:4318` | 可选，关闭则 `OTEL_ENABLED=false` |

本地想一键起全套，用仓库根目录的 `docker compose --profile full up -d` 更简单。

## 上生产前必须改的

1. `10-config.yaml` 里的密码 / JWT secret 换成 External Secrets 注入；
2. 镜像地址换成你自己的仓库（当前是占位 `ghcr.io/adfddda/...`）；
3. 加 `NetworkPolicy` 限制东西向流量；
4. 加 `PodDisruptionBudget`，避免节点维护时实例被一次性驱逐；
5. 关闭 `/v3/api-docs` 与 `/actuator` 的公网暴露。
