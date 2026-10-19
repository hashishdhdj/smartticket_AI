# Docker 下载说明

> 更新日期：2026-09-14
> 关联清单：`09_项目依赖与资源/软件版本清单.md`

## 一、版本结论

| 项目 | 内容 |
|---|---|
| Docker Desktop | 4.71.0（本机已安装） |
| Docker Engine（CLI 报告） | 29.4.1, build 055a478 |
| Docker Compose | v5.1.3 |
| 项目要求 | Docker Desktop + Docker Compose（随 Docker Desktop） |
| 状态 | 已安装；引擎已验证可正常启动 |

## 二、官方下载地址

| 类型 | 地址 |
|---|---|
| Windows 版安装包 | https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe |
| 官方下载页 | https://www.docker.com/products/docker-desktop/ |
| 镜像仓库查询 | https://hub.docker.com/ |

## 三、本机安装位置

```text
C:\Program Files\Docker\Docker\Docker Desktop.exe      # 主程序
C:\Program Files\Docker\Docker\resources\bin\docker.exe
```

本机安装状态（未重复下载安装包，直接复用系统已装版本）：

| 命令 | 实测输出 |
|---|---|
| `docker --version` | `Docker version 29.4.1, build 055a478` |
| `docker compose version` | `Docker Compose version v5.1.3` |
| `docker context ls` | 当前上下文 `desktop-linux`（`npipe:////./pipe/dockerDesktopLinuxEngine`） |

## 四、项目所需镜像（已预拉取完成）

镜像未以 tar 形式存入项目目录。原因：镜像体积大（单镜像数百 MB 至 1 GB+），
且项目章程 7.3 环境管理原则与 09 目录说明均要求「大文件只保存下载链接和说明」。
因此采用**预拉取到本地 Docker 镜像库**的方式，保证随时可用。

| 镜像 | 用途 | 拉取结果 | Digest (sha256) |
|---|---|---|---|
| `mysql:8.0` | 主数据库 | 本地已存在（Image is up to date） | `7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b` |
| `redis:7` | 缓存 / 分布式锁 | 已下载 | `71da9275c5f3fcb97d0fa0c8c5b36cc995327265420f17a04bfd544f458059f7` |
| `rabbitmq:3-management` | 消息队列（带管理台） | 已下载 | `e582c0bc7766f3342496d8485efb5a1df782b5ce3886ad017e2eaae442311f69` |
| `pgvector/pgvector:pg16` | 向量数据库（PostgreSQL 16 + pgvector） | 已下载 | `ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b` |

一键复现命令：

```powershell
docker pull mysql:8.0
docker pull redis:7
docker pull rabbitmq:3-management
docker pull pgvector/pgvector:pg16
```

查询本地已就绪镜像：

```powershell
docker images
```

## 五、常见问题与踩坑记录

1. **Docker CLI 存在，但引擎未运行时命令会报错。**
   - 报错原文：
     ```text
     failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine;
     check if the path is correct and if the daemon is running
     ```
   - 原因：Docker Desktop 未启动，Linux 引擎管道不存在。
   - 处理：启动 Docker Desktop 主程序，等待引擎完成初始化后即可正常使用。
   - 验证方式：`docker info --format '{{.ServerVersion}}'` 能输出版本号（实测输出 `29.4.1`）。

2. **不要用 `Start-Service com.docker.service` 去启动引擎。**
   - 实测报错：`Cannot open 'com.docker.service' service on computer '.'`。
   - 原因：该服务需要管理员权限，且它不是启动 Linux 引擎的正确入口。
   - 正确处理：启动 `Docker Desktop.exe`，由它自行拉起 WSL2 后端与引擎。

3. **首次启动引擎需要等待。**
   - 实测启动后立即检查仍失败，等待约 60 秒后才可用。
   - 后续脚本中若依赖 Docker，应加入健康检查轮询，不要立即执行 `docker` 命令。

4. **pgvector 镜像选择说明。**
   - 使用官方 `pgvector/pgvector:pg16`，已内置 pgvector 扩展，无需在容器内额外编译安装。
   - 相比「postgres 官方镜像 + 手动安装扩展」，可省去阶段 2 的环境折腾成本。

5. 磁盘占用提示。
   - 上述 4 个镜像合计占用约 1.5 GB 左右，注意 C 盘 / Docker 数据盘空间。
