# libs —— 第三方依赖说明

这些 `classes.jar` 都是从 Maven 上的 Shizuku 官方 AAR 里拆出来的，
构建脚本（`build-v6.ps1`）把它们直接加进 `javac` 的 classpath 和 `d8` 的输入。

| 文件 | 来源 | 用途 |
|---|---|---|
| `shizuku-api/classes.jar` | `dev.rikka.shizuku:api:13.1.5` | 客户端 API（`rikka.shizuku.Shizuku` 等） |
| `shizuku-provider/classes.jar` | `dev.rikka.shizuku:provider:13.1.5` | `ShizukuProvider`，服务端把 binder 递进来的入口 |
| `shizuku-shared-classes.jar` | `dev.rikka.shizuku:shared:13.1.5` | `ShizukuApiConstants` 等共用常量 |
| `shizuku-aidl-classes.jar` | `dev.rikka.shizuku:aidl:13.1.5` | `moe.shizuku.server.*` 的 AIDL 桩（`IShizukuService` 等） |
| `annotation.jar` | `androidx.annotation:annotation:1.2.0` | 仅编译期注解，不参与运行 |

下载地址（Maven Central）：

```
https://repo1.maven.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.aar
https://repo1.maven.org/maven2/dev/rikka/shizuku/provider/13.1.5/provider-13.1.5.aar
https://repo1.maven.org/maven2/dev/rikka/shizuku/shared/13.1.5/shared-13.1.5.aar
https://repo1.maven.org/maven2/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar
```

AAR 里的 `classes.jar` 直接解压取出即可，其余文件（`AndroidManifest.xml`、
`res/` 等）本项目没有使用 —— 需要的 `<provider>` 声明已手动写进本项目的 manifest。

## 许可

Shizuku 由 RikkaApps 开发，以 **Apache License 2.0** 发布：
<https://github.com/RikkaApps/Shizuku>

`androidx.annotation` 同样以 **Apache License 2.0** 发布。

本仓库只是把编译产物放在一起以便离线构建，版权归原作者所有。
