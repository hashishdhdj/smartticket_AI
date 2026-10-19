# Postman / 接口测试工具 下载说明

> 更新日期：2026-09-14
> 关联清单：`09_项目依赖与资源/软件版本清单.md`

## 一、版本结论

| 项目 | 内容 |
|---|---|
| 实际版本 | Postman 12.27.7（Windows 64 位） |
| 项目要求 | Postman 或 Apifox（项目章程 阶段0 任务8） |
| 状态 | 已下载安装包，待安装 |
| 备选工具 | Apifox 2.8.46（本机未安装，未下载，可按需补充） |

## 二、官方下载地址

| 工具 | 地址 |
|---|---|
| Postman Windows 64 位 | https://dl.pstmn.io/download/version/12.27.7/windows_64 |
| Postman 官方下载页 | https://www.postman.com/downloads/ |
| Apifox 官方下载页 | https://apifox.com/download |

## 三、项目内落地位置

```text
09_项目依赖与资源/02_下载链接与校验/安装包/API工具/
└─Postman_12.27.7_User_X64_exe_zh-CN.exe
```

下载方式说明：使用 winget 下载（`winget download --id Postman.Postman`），
官方源地址与上表一致，下载后 winget 自动完成了安装程序哈希校验。

## 四、校验信息（SHA256）

| 文件 | 大小 | SHA256 |
|---|---:|---|
| Postman_12.27.7_User_X64_exe_zh-CN.exe | 199.5 MB | `6762b5e95b4e3374092458b9cb4dec50baff13da8263c9de41326d2ab1fac3ed` |

校验命令：

```powershell
Get-FileHash "Postman_12.27.7_User_X64_exe_zh-CN.exe" -Algorithm SHA256
```

## 五、安装与使用说明

1. 双击 `Postman_12.27.7_User_X64_exe_zh-CN.exe` 完成安装（当前为用户级安装包，无需管理员权限）；
2. 首次启动可选择跳过登录，直接本地使用；
3. 后续在 `07_测试与质量/02_接口测试/` 下导出并归档接口集合文件。

## 六、Apifox 备选说明

若后续希望改用 Apifox，可通过以下方式获取：

```powershell
winget search --query Apifox            # 查询结果：Ruihu.Apifox 2.8.46
winget download --id Ruihu.Apifox --exact --accept-source-agreements --accept-package-agreements --download-directory "<项目内目标目录>"
```

选择建议：
- Postman：生态成熟、面试提及率高，本机已具备安装包；
- Apifox：国内文档与团队协作体验更好，且集成了接口文档管理。

两者选其一即可满足项目阶段1的接口测试需求，不建议同时投入时间。

## 七、注意事项

1. 接口集合属于项目测试资产，应归档到 `07_测试与质量/02_接口测试/`，不要只留在工具本地。
2. 不要把接口中使用的真实账号、Token、密码写入集合文件或提交到代码库。
3. 安装包体积约 200 MB，安装完成后可按需从项目目录清理，仅保留校验记录。
