# Spring Boot Entity Demo

这套代码对应 [mysql_red_culture_schema.sql](</D:/大创项目/mysql_red_culture_schema.sql>) 的表结构，按 `MyBatis-Plus + Lombok` 组织。

可直接参考：

- [pom.xml](</D:/大创项目/springboot-entity-demo/pom.xml>)
- [application-example.yml](</D:/大创项目/springboot-entity-demo/src/main/resources/application-example.yml>)

包路径：

- `com.redculture.platform.entity`
- `com.redculture.platform.enums`

建议后续继续补充：

- `mapper` 接口
- `service` 与 `serviceImpl`
- `dto` / `vo`
- `MetaObjectHandler`，用于自动填充 `created_at` 和 `updated_at`
- 枚举 JSON 序列化配置

本目录现已包含：

- 基础实体类
- 常用枚举类
- 6 个核心主实体 `Mapper`
- 6 个核心主实体 `Service` 与 `ServiceImpl`
- 6 个基础查询 `Controller`
- 1 个地图首页聚合接口 `GET /api/map/overview?regionId=...`
- `MybatisMetaObjectHandler`
- `MybatisPlusConfig`
- Spring Boot 启动类

示例初始化数据：

- [mysql_red_culture_seed.sql](</D:/大创项目/mysql_red_culture_seed.sql>)

基础接口示例：

- `GET /api/regions`
- `GET /api/sites`
- `GET /api/heroes`
- `GET /api/events`
- `GET /api/memorials`
- `GET /api/stories`
- `GET /api/map/overview?regionId=4`
- `GET /api/map/nearby?longitude=113.9783&latitude=38.3439&radiusKm=30&limit=10`

地图聚合接口说明：

- `GET /api/map/overview?regionId=...`
- 现在支持递归聚合下级行政区数据
- 例如传入市级 `regionId`，会自动汇总该市下所有县、乡镇的红色资源

就近查询接口说明：

- `GET /api/map/nearby`
- 参数：`longitude`、`latitude`
- 可选参数：`radiusKm`、`limit`
- 当前返回附近遗址和纪念馆，并按距离从近到远排序
