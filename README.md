封装 mysql Jdbc 驱动使 DataGrip / IDEA / DBeaver 支持 Doris 多 catalog。

## IDEA MyBatis XML SQL 解析

驱动只能把 Doris 的 catalog、schema、table、column 通过 JDBC metadata 暴露给 IDE。MyBatis XML 里的 SQL 高亮、表名解析和列名补全，还需要 IDEA 把 mapper 文件关联到对应数据源。

IDEA 日志里如果看到 `BasicMetaModel - To many roots from database`，并且 `.idea/dataSources.local.xml` 里的 Doris 数据源是 `dbms="UNKNOWN"`，说明 IDE 在用通用 JDBC metadata 模型解析 Doris 多 catalog。不要为了 SQL 高亮把驱动默认伪装成 `MySQL`：JetBrains 会切到 MySQL introspector，执行 MySQL 专用查询，并只按当前 catalog 的 database 模型加载，Doris 其它 catalog 会丢失。驱动默认把 `DatabaseMetaData.getDatabaseProductName()` 报为 `Doris`；如果只需要单 catalog MySQL 兼容行为，可以在 VM options 里显式设置：

```text
-Ddoris.jdbc.database.product.name=MySQL
```

如果之前用过 `MySQL` 伪装版本，替换新版 jar 后建议删除并重建 IDEA / DataGrip 里的 Doris 数据源，或至少执行 `Forget Cached Schemas` 后重新同步。旧数据源可能已经缓存了 `product="MySQL"` / `dbms="MYSQL"`，只刷新表不一定会重写这个识别结果。

建议配置：

1. 在 Database 工具窗口中确认需要的 catalog 和 schema 已勾选并完成 introspection。
2. 打开 `Settings | Languages & Frameworks | SQL Resolution Scopes`。
3. 将 mapper XML 所在目录，例如 `src/main/resources/mapper`，映射到这个 Doris 数据源。
4. 如果 SQL 里使用 Doris 三段名，例如 `` `mysql_catalog`.`iot-business`.`tenant_equipment_info_mgr` ``，把该目录或项目的 SQL Dialect 设置为 `Generic SQL`。

原因：IDEA 的 MySQL dialect 主要按 MySQL 的 `database.table` 两段名解析，Doris 的 `catalog.database.table` 三段名容易在 MyBatis XML 静态解析中被标红。`Generic SQL` 仍支持关键字、表名和列名高亮/补全，但不会用 MySQL 语法限制误判 Doris 三段名。

## 日志开关

驱动默认只记录 ERROR，避免 IDEA / DataGrip introspection 时大量 metadata SQL 写入日志。排查问题时可以在 Driver VM options 里打开：

```text
-Ddoris.jdbc.log.level=INFO
```

日志默认写到：

```text
%USERPROFILE%\.doris-jdbc-wrapper\doris-jdbc-wrapper.log
```

可用参数：

```text
-Ddoris.jdbc.log.level=OFF
-Ddoris.jdbc.log.level=ERROR
-Ddoris.jdbc.log.level=INFO
-Ddoris.jdbc.log.path=C:\temp\doris-jdbc-wrapper.log
```

兼容旧开关：`-Ddoris.jdbc.log.enabled=true` 等价于 `INFO`；`false` 等价于 `OFF`。如果同时设置 `doris.jdbc.log.level`，以 `log.level` 为准。
