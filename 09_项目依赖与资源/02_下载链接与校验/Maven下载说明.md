# Maven 下载说明

> 更新日期：2026-09-14
> 关联清单：`09_项目依赖与资源/软件版本清单.md`

## 一、版本结论

| 项目 | 内容 |
|---|---|
| 实际版本 | Apache Maven 3.9.16 |
| 项目要求 | 3.8+（项目章程 6.1 技术栈选型） |
| 状态 | 已下载、已解压、已验证可用 |
| 验证结果 | `mvn -v` 输出 Java version: 17.0.20.1（Eclipse Adoptium） |

## 二、官方下载地址

| 类型 | 地址 |
|---|---|
| 二进制包（zip） | https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip |
| 官方校验文件（SHA512） | https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip.sha512 |
| 签名文件（asc） | https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip.asc |
| 官方下载页 | https://maven.apache.org/download.cgi |

## 三、项目内落地位置

```text
09_项目依赖与资源/02_下载链接与校验/安装包/Maven/
├─apache-maven-3.9.16-bin.zip                 # 原始压缩包
├─apache-maven-3.9.16-bin.zip.sha512          # 官方校验值
└─apache-maven-3.9.16/                        # 已解压，可直接使用
   └─bin/mvn.cmd
```

## 四、校验信息

### 官方 SHA512 比对（已通过）

| 项目 | 值 |
|---|---|
| 官方公布 | `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3` |
| 本地实测 | `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3` |
| 比对结果 | 一致 |

### 附加 SHA256（便于后续快速比对）

| 文件 | 大小 | SHA256 |
|---|---:|---|
| apache-maven-3.9.16-bin.zip | 9.0 MB | `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce` |

校验命令：

```powershell
Get-FileHash "apache-maven-3.9.16-bin.zip" -Algorithm SHA512
Get-FileHash "apache-maven-3.9.16-bin.zip" -Algorithm SHA256
```

## 五、安装与使用说明

### 前置条件

Maven 3.9.x 需要 JDK 8+ 才能运行，本项目要求使用 JDK 17。
Maven 通过 `JAVA_HOME` 定位 JDK，因此 **必须正确设置 `JAVA_HOME`**，否则会直接报错：

```text
The JAVA_HOME environment variable is not defined correctly,
this environment variable is needed to run this program.
```

### 使用项目内免安装 Maven（已验证）

```powershell
$env:JAVA_HOME = "D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1"
$mvn = "D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16\bin\mvn.cmd"
& $mvn -v
```

验证结果：

```text
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16
Java version: 17.0.20.1, vendor: Eclipse Adoptium, runtime: ...\jdk-17-runtime\jdk-17.0.20.1+1
Default locale: zh_CN, platform encoding: GBK
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
```

### 加入全局 PATH（可选）

```powershell
[Environment]::SetEnvironmentVariable("Path", $env:Path + ";D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\Maven\apache-maven-3.9.16\bin", "User")
```

设置后需**新开终端**才会生效。

## 六、注意事项与踩坑记录

1. **必须先设置 `JAVA_HOME`。**
   - 首次直接执行 `mvn -v` 报错：`The JAVA_HOME environment variable is not defined correctly`。
   - 原因：当时终端会话中 `JAVA_HOME` 未指向有效 JDK 目录。
   - 处理：显式设置 `JAVA_HOME` 指向项目内 JDK 17 后恢复正常。

2. **本机原先没有 `mvn` 命令。**
   - 排查结果：`Get-Command mvn` 返回 MISSING，winget 源中也没有官方 Apache Maven 包（仅有第三方同名条目）。
   - 处理：改用 Apache 官方 `dlcdn` 地址手动下载并解压到项目目录。

3. **默认编码为 GBK，不是 UTF-8。**
   - `mvn -v` 显示 `platform encoding: GBK`。
   - 影响：控制台中文可能乱码；后续项目编译建议在 `pom.xml` 中显式声明 `project.build.sourceEncoding=UTF-8`。

4. 不要把 `Maven` 目录提交到 Git，建议加入 `.gitignore`。
