# Docker 迁移工具

状态：2026-09-29 18:09 服务器迁移成功，七个容器基础检查通过。运维命令以 main-service/docs/部署运维速查.md 为准。

## 执行

上传 packages/docker-migration 中的 migrate.sh、migrate.sh.sha256 到服务器 /opt/mjn-ai/packages/docker-migration/，镜像无需再次上传。

```bash
cd /opt/mjn-ai/packages/docker-migration
sha256sum -c migrate.sh.sha256 && bash migrate.sh
```

脚本先校验当前配置并测试实际应用依赖和 Nginx 配置，再停止服务、备份数据、切换到 Docker。此阶段应用会短暂不可用。检查失败时尝试恢复 systemd 服务；恢复失败会保留现场并输出错误。

## 保留与回退

- MySQL 8.0.46、Redis 7.2.16、Kafka 4.3.1 使用原数据目录和原用户编号；切换前做停机备份并逐文件校验。
- 备份在 /opt/mjn-ai/backups/docker-migration-分钟版本/，旧程序、依赖、镜像、发布目录和日志保留。
- Java/Python 使用 CentOS 7 镜像，现有 JDK、Python、虚拟环境和发布目录以只读方式挂载。它们仍是运行所需文件，后续可再改为包含全部运行时的应用镜像。
- Qdrant 继续使用现有 mjn-qdrant 容器和数据目录，保留其原 Compose 项目。
- 容器使用宿主机网络，沿用现有 5xxxx 服务端口；测试环境使用 4xxxx 的约定保持不变。
- 回退先停止迁移容器，再启动原 systemd 服务，继续使用最新数据，不会自动把数据库恢复成旧快照。

```bash
bash /opt/mjn-ai/tools/docker-migrate.sh rollback
```

## 查看与重启

```bash
bash /opt/mjn-ai/tools/docker-migrate.sh status
bash /opt/mjn-ai/tools/docker-migrate.sh restart java
bash /opt/mjn-ai/tools/docker-migrate.sh restart java python
bash /opt/mjn-ai/tools/docker-migrate.sh restart apps
bash /opt/mjn-ai/tools/docker-migrate.sh restart all
bash /opt/mjn-ai/tools/docker-migrate.sh restart mysql
```

服务名：mysql、redis、kafka、python、java、nginx、qdrant。apps 指 Python、Java、Nginx；all 包含以上七个服务。web、admin、frontend 均指共用的 Nginx。

Java/Python 仍共用 /etc/mjn-ai/config.env，容器每次启动都会重新读取，直接 docker restart 也会生效。配置值按字面值读取，不执行 shell 命令；不支持多行赋值。

迁移后使用上述 Docker 工具管理服务。原 systemd 服务有启动保护，在迁移启用期间不会启动同名进程。

## 配置和日志

当前 Compose：/opt/mjn-ai/docker/current/compose.json。

MySQL、Redis、Kafka、Nginx 的容器配置副本在 /opt/mjn-ai/docker/current/config/，分别为 my.cnf、redis.conf、server.properties、nginx/nginx.conf。修改后重启对应容器。回退使用原生服务的原配置文件。

```bash
docker logs --tail 100 mjn-ai-java
docker logs --tail 100 mjn-ai-python
docker logs --tail 100 mjn-redis
docker logs --tail 100 mjn-kafka
docker logs --tail 100 mjn-qdrant
tail -n 100 /var/log/mysqld.log
tail -n 100 /var/log/mjn-ai/nginx/error.log
```

容器标准输出日志限制为每个服务 3 个 10 MB 文件；原来的文件日志继续使用原路径。迁移完成后还需在浏览器检查登录、聊天、模型调用和知识库流程。

Docker 兼容的应用发布工具已生成在 scripts/ops/packages/mjn-deploy-tools.tar.gz；服务器原有部署工具仍需上传并安装此新包，旧的 systemd 版本不可用于当前容器。
