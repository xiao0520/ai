# MJN-AI 运维工具

服务器在 2026-09-29 切换到 Docker。当前容器查看、重启和基础健康检查使用 `/opt/mjn-ai/tools/docker-migrate.sh`；旧的 `mjn-aictl.sh`、`restart-*.sh` 仍面向 systemd，不能管理当前容器。

```bash
bash /opt/mjn-ai/tools/docker-migrate.sh status
bash /opt/mjn-ai/tools/docker-migrate.sh restart java
bash /opt/mjn-ai/tools/docker-migrate.sh restart java python
bash /opt/mjn-ai/tools/docker-migrate.sh restart apps
bash /opt/mjn-ai/tools/docker-migrate.sh restart all
```

单服务和全量应用发布继续使用 `deploy-java.sh`、`deploy-python.sh`、`deploy-web.sh`、`deploy-admin.sh`、`deploy-all.sh`，但服务器须先安装本仓库 `scripts/ops/packages/mjn-deploy-tools.tar.gz` 中的 Docker 兼容版本。上传工具包与 `.sha256` 后执行：

```bash
cd /opt/mjn-ai/packages
sha256sum -c mjn-deploy-tools.tar.gz.sha256 && tar -xzf mjn-deploy-tools.tar.gz -C /opt/mjn-ai/tools
```

首次发布包含 NumPy 2.5.3 的 Python 包前，还需上传 `scripts/ops/packages/mjn-python-runtime.tar.gz` 和 `.sha256` 到 `/opt/mjn-ai/packages/`，执行 `bash /opt/mjn-ai/tools/prepare-python-runtime.sh`。该脚本只升级 Python 容器镜像；预检或健康检查失败时恢复原镜像。之后使用 `bash /opt/mjn-ai/tools/deploy-all.sh --online` 发布应用。

发布工具自动保留旧版本、依赖和备份；失败时尝试恢复旧容器配置。完整打包、上传、回退和配置路径见 [部署运维速查](../../main-service/docs/部署运维速查.md)。

## Java 验证码字体

2026-09-30 Java 的 `/auth/code` 因基础 CentOS 7 容器缺少 fontconfig、freetype、libpng 和字体目录失败。镜像切换前曾从宿主机只读挂载这些文件到 Java 服务；现已复制进 Java 版本镜像，容器不再依赖字体宿主机挂载。独立修复包是 `scripts/ops/packages/mjn-java-fonts-fix.tar.gz` 和同名 `.sha256`。以下命令仅适用于恢复旧挂载模式后再次修复字体；当前版本镜像不需要执行：

```bash
cd /opt/mjn-ai/packages && sed -i 's/\r//g' mjn-java-fonts-fix.tar.gz.sha256 && sha256sum -c mjn-java-fonts-fix.tar.gz.sha256 && tar -xzf mjn-java-fonts-fix.tar.gz -C /opt/mjn-ai/tools && /opt/mjn-ai/tools/python3.14 /opt/mjn-ai/tools/fix-java-fonts.py
```

工具先在隔离容器绘制文字，再备份 Compose 并仅重建 Java，最后检查真实验证码接口；失败时尝试恢复旧配置。首次执行的备份在 `/opt/mjn-ai/backups/java-fonts-20260930-1535`，已显示 `SUCCESS`。

## 模型凭证更新

管理端模型保存的是 `credentialKey` 别名，实际 API Key 存在服务器 `/etc/mjn-ai/config.env` 的 `AI_PROVIDER_CREDENTIALS` 中。修改该文件后执行 `bash /opt/mjn-ai/tools/docker-migrate.sh restart python`，让 Python 进程重新读取配置；更改别名时还须核对模型发布版本所引用的别名。不要在日志或工单中粘贴密钥值。

## 应用版本镜像发布

2026-09-30 18:45 已切换到 `image-20260930-184420`：Java JAR、Python 应用和虚拟环境、两个前端构建产物，以及 Java JDK/字体和应用启动所需 Python 运行时均已封入各自的版本镜像。18:46 七个容器均运行；浏览器验证管理端登录、普通聊天、知识库列表和详情均正常。MySQL、Redis、Kafka、Qdrant 数据目录不变；`/etc/mjn-ai/config.env`、应用元数据和 Nginx 配置仍以只读挂载提供给容器，密钥不进入镜像。现有容器名称、端口和 `unless-stopped` 重启策略不变。

以下是首次切换时使用的命令记录，服务器已于 2026-09-30 18:45 执行成功，正常运维无需重复执行 `--migrate-current`。初次安装时先在本地运行 `python scripts/ops/build-image-deploy-package.py`，把 `scripts/ops/packages/mjn-image-deploy-tools.tar.gz` 和同名 `.sha256` 上传到服务器 `/opt/mjn-ai/packages/`，确认磁盘空间后逐行执行：

```bash
cd /opt/mjn-ai/packages
sha256sum -c mjn-image-deploy-tools.tar.gz.sha256
tar -xzf mjn-image-deploy-tools.tar.gz -C /opt/mjn-ai/tools
/opt/mjn-ai/tools/python3.14 /opt/mjn-ai/tools/deploy-images.py --migrate-current
```

最后一条先离线构建并预检三个应用镜像，再备份 Compose 和配置、逐个重建 Python、Java、Nginx。每个服务有健康检查，Java 还要通过 `/auth/code` 验证码检查；失败时自动恢复原配置和容器。镜像构建或预检失败不会切换服务。成功后查看：

```bash
bash /opt/mjn-ai/tools/docker-migrate.sh status
/opt/mjn-ai/tools/python3.14 /opt/mjn-ai/tools/deploy-images.py --list
docker inspect -f '{{.Name}} {{.Config.Image}} {{.State.Status}}' mjn-ai-java mjn-ai-python mjn-nginx
```

浏览器再验证登录、普通聊天和知识库。初次切换若需恢复原宿主机发布目录挂载模式，保留旧发布目录并执行：

```bash
/opt/mjn-ai/tools/python3.14 /opt/mjn-ai/tools/deploy-images.py --legacy-rollback
```

切换成功后，后续 `deploy-java.sh`、`deploy-python.sh`、`deploy-web.sh`、`deploy-admin.sh`、`deploy-all.sh` 的上传包和调用方式不变；发布入口自动构建新镜像并切换。`--list` 列出镜像版本，`--rollback <镜像版本>` 回退。发布包仍要先上传并通过 `SHA256SUMS` 校验。镜像模式下不要直接改当前 Compose 的 `image`、应用路径或删除 `docker/image-mode.json`；也不要删除当前和回滚用的发布目录、镜像或首次切换备份。
