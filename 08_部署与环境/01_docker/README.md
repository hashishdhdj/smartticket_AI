# Docker 环境（MySQL + Redis + PostgreSQL/pgvector + RabbitMQ）

> 项目：AI驱动的智能工单服务系统
> 阶段：阶段1 最小骨架 → 阶段2 RAG（第5天加入 pgvector）→ 阶段3 工程化（第6周加入 RabbitMQ）
> 最后更新：2026-10-09（阶段3 第6周）
> 说明：本目录的 compose 承担「本地开发所需的中间件」这一职责；应用本身不在此容器内运行。

---

## 一、服务清单

| 服务 | 镜像 | 容器名 | 宿主端口 | 数据卷 → 容器路径 |
|---|---|---|---|---|
| MySQL | `mysql:8.0` | `smartticket-mysql` | 3306 | `01_docker_mysql-data` → `/var/lib/mysql` |
| Redis | `redis:7-alpine` | `smartticket-redis` | 6379 | `01_docker_redis-data` → `/data` |
| PostgreSQL + pgvector | `pgvector/pgvector:pg16` | `smartticket-pgvector` | 5432 | `01_docker_pgvector-data` → `/var/lib/postgresql/data` |
| RabbitMQ（含管理台） | `rabbitmq:3-management` | `smartticket-rabbitmq` | 5672 / 15672 | `01_docker_rabbitmq-data` → `/var/lib/rabbitmq` |

四个服务都配置了 healthcheck：MySQL 用 `mysqladmin ping`，Redis 用 `redis-cli ping`，PostgreSQL 用 `pg_isready`，RabbitMQ 用 `rabbitmq-diagnostics -q ping`。

MySQL 启动参数：`--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`；Redis 启动参数：`--appendonly yes --requirepass ${REDIS_PASSWORD}`；PostgreSQL 由环境变量 `POSTGRES_DB=smartticket_vector` 建库；RabbitMQ 由 `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` 建默认用户。

**为什么会有第二个数据库**：工单数据在 MySQL，RAG 知识库的向量在 PostgreSQL。
MySQL 8.0 没有向量类型与向量索引，而 pgvector 是 PostgreSQL 的扩展——扩展不能跨数据库安装，
所以只能并存，不是"换数据库"。两个库各管一件事，应用侧对应两条独立连接。

**为什么会有 RabbitMQ（阶段3 第6周）**：工单创建后要自动跑 AI 分析。若在创建请求里同步调模型，
模型排队几十秒会把创建接口一起拖慢（与 DEF-002 同类故障，只是触发源换成模型服务）。
因此改为"创建成功后发一条消息，AI 在消费者线程里跑"，创建请求立刻返回。
15672 是本地的 Web 管理台：<http://localhost:15672>，可直观看到队列积压与死信队列条数。


---

## 二、前置条件

1. **Docker Desktop 已启动**（引擎就绪）。
   引擎未启动时，`docker compose` 命令会报 `cannot find the file specified`，`mvn test` 也会因连不上 3306 / 6379 而大批失败。
2. **存在本目录下的 `.env`**（已被 `.gitignore` 忽略，不入库）。模板见 `08_部署与环境/02_env/.env.example`：

| 变量 | 用途 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码。**必须与数据卷初始化时使用的密码一致**，否则容器启动失败 |
| `REDIS_PASSWORD` | Redis 密码（阶段1 第10天起启用 `requirepass`） |
| `POSTGRES_PASSWORD` | PostgreSQL `postgres` 用户密码（阶段2 第5天加入）。同样必须与数据卷初始化时一致 |
| `RABBITMQ_USER` | RabbitMQ 默认用户（阶段3 第6周加入），默认 `smartticket` |
| `RABBITMQ_PASSWORD` | RabbitMQ 默认用户密码（阶段3 第6周加入）。**不要留空**，空密码会建出"无密码用户"，应用连接被拒 |

同时，应用侧 `06_项目源代码/ticket-service/src/main/resources/application-local.yml` 里的这几个密码必须与 `.env` 保持一致，否则应用连不上中间件。

> RabbitMQ 的账号密码只在**首次初始化数据卷**时由镜像固化。容器已存在后再改 `.env` 不会改变已有用户，
> 要换密码需进容器执行 `rabbitmqctl change_password <user> <newpass>`，或删卷重建。

### 拉不到镜像时：配置镜像加速器（阶段2 第5天踩到）

`pgvector/pgvector:pg16` 的基础镜像来自 Docker Hub。国内直连 `registry-1.docker.io` 会超时，
报错形如 `dial tcp ...: connectex: A connection attempt failed`。

在 `%USERPROFILE%\.docker\daemon.json` 里加一段，然后**重启 Docker Desktop**：

```json
{
  "registry-mirrors": ["https://docker.m.daocloud.io"]
}
```

临时验证也可以直接指定镜像前缀（不改配置）：

```powershell
docker pull docker.m.daocloud.io/pgvector/pgvector:pg16
docker tag  docker.m.daocloud.io/pgvector/pgvector:pg16 pgvector/pgvector:pg16
```

> 用加速器前先确认它可达：`Test-NetConnection docker.m.daocloud.io -Port 443 -InformationLevel Quiet`。
> 第三方的加速器地址会失效，失效时换一个即可——`compose` 里写的是官方镜像名，不依赖某个加速器。

---

## 三、常用命令

以下命令均在 **`08_部署与环境/01_docker/`** 目录下执行。

| 目的 | 命令 |
|---|---|
| 启动全部 | `docker compose up -d` |
| 只启动某个服务 | `docker compose up -d mysql` |
| 查看状态（含 healthy） | `docker compose ps` |
| 跟踪日志 | `docker compose logs -f mysql` |
| 停止（保留容器与数据） | `docker compose stop` |
| 停止并删除容器（保留数据卷） | `docker compose down` |
| **危险**：连数据卷一起删除 | `docker compose down -v` |

> `down -v` 会删除数据卷，**MySQL 数据与 Redis 的 AOF 全部丢失**，下次启动将重新执行初始化 SQL。仅在需要"从零重建"时使用。

---

## 四、初始化 SQL 的行为（重要，容易踩）

两套初始化脚本挂在**不同**目录，互不干扰：

| 挂载源（源码目录） | 目标服务 | 建出什么 |
|---|---|---|
| `../../06_项目源代码/sql` | MySQL | `ticket` / `ticket_log` |
| `../../06_项目源代码/sql-vector` | PostgreSQL | `vector` 扩展 + `knowledge_chunk` 表与索引 |

挂载都用**源码目录**（只读），避免 SQL 在仓库里出现第二份副本后漂移。
PostgreSQL 的入口脚本只处理**本目录下**的 `*.sql`（不递归），所以向量库单独用一个源码目录，
不与 MySQL 的 `sql/` 混放——这也是为什么 `sql-vector/` 是独立目录而不是 `sql/` 的子目录。

**官方镜像都只在数据目录为空时执行这些脚本，而且只执行一次。** 数据卷已存在时，
即使新增或修改 SQL 文件，也**不会**被自动执行。

因此：

- **首次** `up`（数据卷不存在）→ 脚本自动执行
- **之后**每次 `up` → 不执行；要改表结构必须**手动执行 SQL**，或删卷重建

改动 `sql-vector/01_init_pgvector.sql` 后要让它重新生效：

```powershell
# 只删向量库的卷，不影响 MySQL 与 Redis
docker compose rm -sf pgvector
docker volume rm 01_docker_pgvector-data
docker compose up -d pgvector
```

### 验证「脚本能自动建表」的方法（不影响现有数据卷）

```powershell
docker run -d --name smartticket-sql-verify `
  -e MYSQL_ROOT_PASSWORD=verify_only_123 `
  -e MYSQL_DATABASE=smartticket `
  -v "d:\SmartTicket-AI\06_项目源代码\sql:/docker-entrypoint-initdb.d:ro" `
  mysql:8.0
# 等约 30 秒，观察是否出现 running /docker-entrypoint-initdb.d/01_init_schema.sql
docker logs smartticket-sql-verify
docker exec -e MYSQL_PWD=verify_only_123 smartticket-sql-verify mysql -uroot -D smartticket -e "SHOW TABLES;"
docker rm -f smartticket-sql-verify
```

**2026-09-23（第10天）用此法验证通过**：脚本被自动执行，`ticket`、`ticket_log` 两张表及全部 7 个索引均正确创建，字符集为 `utf8mb4_unicode_ci`。

**2026-10-03（阶段2 第5天）pgvector 用直接 `up` 新卷的方式验证通过**，实测输出：

```text
 extname | extversion        扩展
---------+------------
 vector  | 0.8.7
 pg_trgm | 1.6        ← 阶段2 第6天加入，关键词检索用

表 knowledge_chunk 的列（节选）：
    content_hash  | character varying(64)  | not null
    embedding     | vector(1024)           | not null
    model         | character varying(100) | not null

Indexes（4 个）：
    "knowledge_chunk_pkey" PRIMARY KEY, btree (id)
    "uk_knowledge_chunk_source" UNIQUE, btree (source_file, chunk_index)
    "idx_knowledge_chunk_embedding" hnsw (embedding vector_cosine_ops)
    "idx_knowledge_chunk_content_trgm" gin (content gin_trgm_ops)

容器状态：smartticket-pgvector | Up (healthy)
```

> `pg_trgm` 属于 PostgreSQL contrib 模块，镜像已内置，无需编译。
> **注意**：初始化脚本只在空卷时执行，因此**在已有数据卷上新增扩展/索引必须手工执行**（或删卷重建），
> 例如第6天加的这两句：
>
> ```powershell
> docker exec smartticket-pgvector psql -U postgres -d smartticket_vector `
>   -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;" `
>   -c "CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_content_trgm ON knowledge_chunk USING gin (content gin_trgm_ops);"
> ```

---

## 五、连接方式

应用侧（`application.yml` + `application-local.yml`）连接的是宿主机的 `127.0.0.1:3306`、`127.0.0.1:6379`、`127.0.0.1:5432` 与 `127.0.0.1:5672`。

**MySQL**（密码经 `MYSQL_PWD` 传入，不出现在命令行）：

```powershell
$env:MP = ((Get-Content "d:\SmartTicket-AI\08_部署与环境\01_docker\.env" | Where-Object { $_ -match '^MYSQL_ROOT_PASSWORD=' }) -replace '^MYSQL_ROOT_PASSWORD=','').Trim()
docker exec -e MYSQL_PWD=$env:MP smartticket-mysql mysql -uroot -D smartticket -e "SHOW TABLES;"
```

**Redis**（密码经 `REDISCLI_AUTH` 传入，避免 `-a` 的明文警告）：

```powershell
docker exec -e REDISCLI_AUTH=<REDIS_PASSWORD> smartticket-redis redis-cli PING
docker exec -e REDISCLI_AUTH=<REDIS_PASSWORD> smartticket-redis redis-cli --scan --pattern "ticket:detail:*"
```

**PostgreSQL（pgvector）**：容器内 socket 连接默认 trust，所以**不需要密码**：

```powershell
docker exec smartticket-pgvector psql -U postgres -d smartticket_vector -c "\d knowledge_chunk"
docker exec smartticket-pgvector psql -U postgres -d smartticket_vector -c "select count(*) from knowledge_chunk;"
```

验证存进去的向量确实是 1024 维（`vector_dims` 是 pgvector 提供的函数）：

```powershell
docker exec smartticket-pgvector psql -U postgres -d smartticket_vector `
  -c "select source_file, chunk_index, model, vector_dims(embedding) from knowledge_chunk limit 5;"
```

也可以直接试一次关键词检索（阶段2 第6天）。**含中文的 SQL 不要走 `-c`**：
命令行里的中文会先被 PowerShell 按当前代码页转换一次，实测会被破坏；
改成"把 SQL 写成 UTF-8 文件再拷进容器执行"：

```powershell
# 1) 先用编辑器存一个 UTF-8 的 probe.sql（不要 BOM 也可以），内容例如：
#    select source_file, chunk_index,
#           round(word_similarity('验证码异常', content)::numeric, 4) as ws
#    from knowledge_chunk order by ws desc limit 5;
docker cp .\probe.sql smartticket-pgvector:/tmp/probe.sql
docker exec smartticket-pgvector psql -U postgres -d smartticket_vector -f /tmp/probe.sql
```

自检方法：文件第一条写成 `SELECT length('验证码异常') AS n;`，**正确结果是 5**。
若不是 5，说明 SQL 文件的编码或传入方式有问题，后面的分数都不要信。

**RabbitMQ**（阶段3 第6周）：

```powershell
# 容器内查看节点与队列状态（本地 socket 连接走 Erlang cookie，不需要账号密码）
docker exec smartticket-rabbitmq rabbitmq-diagnostics -q ping
docker exec smartticket-rabbitmq rabbitmqctl -q -p / list_users
docker exec smartticket-rabbitmq rabbitmqctl -q -p / list_queues name messages consumers

# 用应用侧同一份凭据验证账号密码（走管理台 HTTP API；密码经环境变量传入，不写死在命令行）
$env:MQP = ((Get-Content "d:\SmartTicket-AI\08_部署与环境\01_docker\.env" | Where-Object { $_ -match '^RABBITMQ_PASSWORD=' }) -replace '^RABBITMQ_PASSWORD=','').Trim()
curl.exe -s -u "smartticket:$env:MQP" http://localhost:15672/api/whoami
# 凭据正确会返回形如 {"name":"smartticket",...}；错误则返回 401
```

> 注意 `rabbitmqctl list_users` 能列出用户，**但这不能证明密码对**——它走的是容器内 socket，
> 与 AMQP 登录无关。真正验证密码的是上面的 `/api/whoami` 与应用的 `RabbitConnectivityTest`。

Web 管理台：<http://localhost:15672>（用户名 `smartticket`，密码见 `01_docker/.env` 的 `RABBITMQ_PASSWORD`）。
第6步做完后，可在管理台的 Queues 页看到业务队列、重试与死信队列的消息数，比命令行更直观。

---

## 六、已知限制

1. **端口绑定为 `0.0.0.0`**（`3306:3306`、`6379:6379`、`5432:5432`），同一局域网内的设备可以访问。本机开发可接受；若要在有公网 IP 的机器上使用，应改为 `127.0.0.1:3306:3306` 或加防火墙规则。
2. **应用本身未容器化**：`ticket-service` 仍需通过 Maven 或 IDEA 启动。阶段1 第10天未要求容器化应用，留待阶段3（工程化/部署）处理；届时还需把应用侧的 `spring.data.redis.host` 从 `127.0.0.1` 改为服务名 `redis`，向量库的 `url` 同理改为 `pgvector`。
3. **向量表结构变更需删卷重建**：`knowledge_chunk` 的 DDL 只在空卷时执行。换嵌入模型若改变维度，必须改 `sql-vector/01_init_pgvector.sql` 里的 `VECTOR(n)` 并删卷重建（不同维度的向量无法比较）。
4. **PostgreSQL 未做备份策略**：语料是静态的 classpath 资源，向量随时可由 `--smartticket.vector.index-on-startup=true` 重建，因此不备份。这一点与 MySQL 不同——工单数据丢了就没了。
