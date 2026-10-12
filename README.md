# JDBC SQL Splicer

在 IntelliJ IDEA 的 Java 断点暂停时，将 JDBC 风格的 `?` 占位 SQL 与参数数组/集合拼接成可复制的 SQL。

## 使用方式

1. 使用 Java 调试器在包含 SQL 与参数局部变量的位置暂停。
2. 在编辑器或 Debug Variables 面板右键，选择“拼接 JDBC SQL...”。
3. 在弹窗中确认 SQL 与 Args 变量；`sql`、`args`、`param` 等名称会优先默认选中。
4. 点击“拼接并复制”，完整 SQL 会进入系统剪贴板，并显示 IDEA 通知。

<img width="1484" height="852" alt="f38756a72152db744b0b997b488da094" src="https://github.com/user-attachments/assets/f30f005b-09bc-4453-a4e7-ca89668f2e78" />

<img width="1330" height="568" alt="95695d0a79f9709602a71b15f83460db" src="https://github.com/user-attachments/assets/8c926169-7c72-44bb-b22c-29e5057d0f94" />




插件仅展示当前 Java 栈帧中下列候选变量：

| 下拉框 | 筛选条件 |
| --- | --- |
| SQL | 运行时类型为 `java.lang.String` |
| Args | 数组、`List`、`Set`、`Queue` 或 `Collection` 实现 |

2025.3 的新调试前端会包装变量对象；插件通过通用 `XValue` 展示 API 读取类型，因此无需依赖旧版 Java 调试变量实现。

## 字面量规则

- `null` 转为 `NULL`。
- `String`、`Character`、枚举以及普通对象的回退文本使用单引号包裹；单引号转义为 `''`。
- 数字不加引号，布尔值转为 `TRUE` / `FALSE`。
- `java.sql.Date`、`Time`、`Timestamp` 和 `java.util.Date` 分别按日期、时间或 `yyyy-MM-dd HH:mm:ss` 格式输出。
- `LocalDate`、`LocalTime`、`LocalDateTime` 输出可读日期时间文本。
- 数组（包含基本类型数组）和集合在单个占位符处展开为逗号分隔字面量，适用于 `IN (?)`。
- 空数组与空集合会报错，不生成无效的 `IN ()`。
- 只替换 SQL 代码区的问号；单/双引号、`--` 行注释和 `/* ... */` 块注释中的问号不会被当作占位符。

这是调试与排查工具，生成结果用于验证参数绑定效果；线上代码仍应保持 `PreparedStatement` / `JdbcTemplate` 参数化执行，不应把拼接结果回写成执行 SQL。

## 构建与安装

要求 Gradle 9+；插件支持 IDEA 2024.3（243）至 2025.3（253）构建。目标 IDEA 2024.3.6 的 Gradle 任务需要 JDK 21，插件源码与产物使用 Java 17 字节码兼容级别（`--release 17`）。

```bash
gradle test
gradle buildPlugin
```

安装包生成在 `build/distributions/`，可通过 IDEA 的 **Settings | Plugins | Install Plugin from Disk...** 安装。开发调试运行：

```bash
gradle runIde
```

首次 Gradle 执行会下载目标 IDEA SDK；完成后可以在沙箱 IDEA 中对 JDBC 调用设置断点进行端到端验证。

## 已知边界

- 当前仅支持 Java 调试会话，不支持 JavaScript、Python 或远程非 Java 调试器。
- SQL 扫描器尚未解析 PostgreSQL dollar-quoted string、Oracle `q'...'` 等非标准字符串字面量；这类 SQL 应先人工确认问号位置。
- `TRUE` / `FALSE` 和日期字面量的最终兼容性由目标数据库方言决定。若使用 Oracle、SQL Server 等方言，建议在后续版本提供可配置的布尔/日期渲染策略。
