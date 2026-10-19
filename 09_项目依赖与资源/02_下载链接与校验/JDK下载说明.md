# JDK 下载说明

> 更新日期：2026-09-14
> 关联清单：`09_项目依赖与资源/软件版本清单.md`

## 一、版本结论

| 项目 | 内容 |
|---|---|
| 发行版 | Eclipse Temurin（OpenJDK 官方构建） |
| 实际版本 | 17.0.20.1+1 |
| 项目要求 | JDK 17（项目章程 6.1 技术栈选型） |
| 状态 | 已下载并验证通过 |

## 二、官方下载地址

| 类型 | 地址 |
|---|---|
| 安装包（Windows msi） | https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.msi |
| 免安装包（zip） | https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip |
| 官网入口 | https://adoptium.net/temurin/releases/?version=17 |

## 三、项目内落地位置

```text
09_项目依赖与资源/02_下载链接与校验/安装包/JDK/
├─Eclipse Temurin JDK with Hotspot 17_17.0.20.101_Machine_X64_wix_zh-CN.msi   # 安装包
├─OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip                          # 免安装包
└─jdk-17-runtime/jdk-17.0.20.1+1/                                             # 已解压的可直接使用运行时
```

## 四、校验信息（SHA256）

| 文件 | 大小 | SHA256 |
|---|---:|---|
| Eclipse Temurin JDK with Hotspot 17_17.0.20.101_Machine_X64_wix_zh-CN.msi | 160.6 MB | `a6013774c5e4a34951e11c1997d8b4641d2de7736d3eac839231f4266bd1116f` |
| OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip | 182.0 MB | `e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0` |

校验命令（PowerShell）：

```powershell
Get-FileHash "<文件路径>" -Algorithm SHA256
```

## 五、安装与使用说明

### 方式一：安装版（推荐日常开发）

1. 双击 `Eclipse Temurin JDK with Hotspot 17_17.0.20.101_Machine_X64_wix_zh-CN.msi`；
2. 默认安装路径通常为 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.1+1-hotspot`；
3. 安装完成后配置环境变量：
   - `JAVA_HOME` = JDK 安装目录
   - `Path` 追加 `%JAVA_HOME%\bin`
4. 新开终端验证：`java -version`

### 方式二：免安装版（项目内自包含，当前已验证可用）

无需安装，直接指向项目内解压目录即可：

```powershell
$env:JAVA_HOME = "D:\SmartTicket-AI\09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

验证结果：

```text
openjdk version "17.0.20.1" 2026-08-18
OpenJDK Runtime Environment Temurin-17.0.20.1+1 (build 17.0.20.1+1)
OpenJDK 64-Bit Server VM Temurin-17.0.20.1+1 (build 17.0.20.1+1, mixed mode, sharing)
```

## 六、注意事项与踩坑记录

1. **本机默认 JDK 为 25，不是 17。**
   - 当前 `JAVA_HOME` 指向 `C:\Users\chars_bulus\.jdks\openjdk-25.0.2`。
   - 项目要求 JDK 17，若直接使用全局 `java`，会以 Java 25 编译，导致与 Spring Boot 3.3.x 的目标版本不一致。
   - 处理方式：为项目单独指定 JDK 17（IDEA 中配置 Project SDK / Gradle-Maven Runner JDK，或使用上面的免安装版）。

2. `M2_HOME` 不是必需变量。
   - Maven 3.9.x 只需 `JAVA_HOME` 与 `Path` 中包含 `mvn` 的 `bin` 目录。
   - 网上大量教程中的 `M2_HOME` 写法属于旧习惯，配置与否均可。

3. 不要把 `JDK` 目录提交到 Git。
   - 该目录体积约 300 MB 且为二进制文件，建议在 `.gitignore` 中排除。
