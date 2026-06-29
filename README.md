# Backend Chain Audit

IntelliJ IDEA 内部插件，从 Java/Spring 方法、HTTP 路径或 MQ topic 出发，静态分析调用链及其涉及的 MySQL、Redis、RabbitMQ、Kafka、RocketMQ 和外围 HTTP 接口。

当前版本：`1.2.0`

支持 IDEA 版本：`2023.3` 至 `2026.1.x`（IntelliJ Platform build `233` 到 `261.*`）。该范围来自 `build.gradle.kts` 中的 `platformVersion = 2023.3.8`、`sinceBuild = "233"` 和 `untilBuild = "261.*"` 配置；低于 `2023.3` 或高于 `2026.1.x` 的 IDEA 版本不会被当前插件声明为兼容。

## 为什么使用 IDEA 插件扫描

普通脚本通常依赖正则、文本搜索或自行解析源码，难以准确处理重载方法、接口实现、继承、泛型、静态导入和依赖库符号。Backend Chain Audit 直接复用 IntelliJ IDEA 已构建的工程模型、Java 语义模型和索引，分析基础与 IDEA 的跳转定义、查找实现等代码能力一致。

主要优势：

- **语义解析而非文本匹配**：通过 PSI reference resolve 将调用表达式定位到真实 `PsiMethod`，能够区分同名方法、重载和不同包下的类。
- **理解完整工程上下文**：复用 IDEA 已导入的 Maven/Gradle 依赖、源码根、模块和依赖库，不需要重新实现 Java classpath 解析。
- **保留源码证据**：调用边和资源均关联 `SmartPsiElementPointer`，结果可双击跳转到调用位置、Mapper 方法或资源声明。
- **结果可解释**：每条边和资源记录解析依据及置信度，不把无法静态确认的结果伪装成确定事实。
- **贴近开发流程**：直接从光标方法启动，结果支持筛选、排序、源码跳转以及 Markdown/CSV/Mermaid 导出。
- **批量接口统计**：支持一次输入最多 100 个 HTTP 接口路径，提交前校验格式并自动去重，按接口维度逐个扫描后导出批量统计报告。
- **可配置扩展**：可配置排除包、HTTP 工具类、最大深度、MQ 递进和资源去重，不要求所有工程采用相同封装。

## 准确性如何保障

### 1. 使用 IDEA 的语义代码模型

Java 调用链以 IntelliJ Platform 的 [PSI（Program Structure Interface）](https://plugins.jetbrains.com/docs/intellij/psi-files.html) 为基础。PSI 是 IDEA 对源码的结构化、语义化表示，不是简单字符串或正则匹配。

对每个方法调用，插件使用 IDEA 的 [References and Resolve](https://plugins.jetbrains.com/docs/intellij/references-and-resolve.html) 机制解析真实声明：

```text
调用表达式 -> PsiReference/PsiMethodCallExpression -> resolve -> PsiMethod
```

因此可以正确区分：

- 同名方法和方法重载；
- `this.xxx()`、私有方法、父类方法和构造器；
- 接口声明与实现类；
- Java 方法引用，例如 `this::createTradeOrder`；
- 项目源码与 Maven/Gradle 依赖中的声明。

Kotlin 入口使用 [UAST](https://plugins.jetbrains.com/docs/intellij/uast.html) 获取统一 JVM 语言结构，但当前完整调用链能力仍以 Java 为主。

### 2. 展开接口实现和运行时候选

插件使用 IDEA 项目索引查找接口实现和子类，并结合方法签名定位真实覆写方法：

- 唯一实现：进入对应实现方法；
- 多实现：保留所有静态候选，不擅自选择其中一个；
- `@Qualifier`：按指定 Bean 名缩小候选；
- `@Primary`：存在唯一主实现时优先定位；
- 模板方法：同时保留基类实现和可能执行的子类覆写；
- 工厂或 `ApplicationContext.getBean()`：无法唯一确定时输出所有能够静态确认的分支。

这类项目级查找依赖 IDEA 的索引和 PSI Stub。JetBrains 对相关机制的说明见 [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html) 和 [Stub Indexes](https://plugins.jetbrains.com/docs/intellij/stub-indexes.html)。

### 3. 资源识别必须来自可追溯证据

调用图中的每个方法都会交给资源提取器处理：

- **MyBatis**：通过 Mapper 完整类名匹配 XML `namespace`，通过方法名匹配 statement `id`，展开 `<include>` 后解析 SQL。
- **MyBatis-Plus**：识别 `BaseMapper`、`IService`、`ServiceImpl`、`Db` 和 Wrapper 常用 CRUD，通过实体 `@TableName` 或默认命名规则定位表。
- **MyBatis Generator/通用 Mapper**：解析 `select/update/deleteByExample` 及 `tk.mybatis Mapper<T>` 等写法；通过调用点泛型继承链还原实体，禁止将 `T/Object` 误识别为表名。
- **JPA**：根据 Repository 泛型、实体 `@Table`、Repository 方法和 `@Query` 识别表及读写操作。
- **SQL**：优先使用 JSqlParser 解析；动态 SQL 无法完整解析时使用后备识别并降低置信度。
- **Feign**：组合服务名、`@FeignClient.path`、类级 Mapping、方法级 Mapping 和 HTTP 方法。
- **自定义 HTTP 工具**：按设置中的工具类前缀识别，使用方法名推断 HTTP 方法，并解析首个 URL 参数及 `@Value` 配置占位符。
- **Kafka/RabbitMQ/RocketMQ**：识别 Template、Producer wrapper、Listener、自定义 MQ 注解、阿里云 ONS API、`JshRocketMqProducer` / `JshRocketMqListener` 包装器以及 `@Value` topic/queue 字段。
- **Redis**：识别 Spring Cache、RedisTemplate 和 Redis 组件调用，并尽可能保留 key 表达式。

每条资源都保存类型、名称、操作、置信度、证据说明和源码指针。证据说明包含从入口到资源的调用路径；经过接口多实现或覆写候选时会标记“推断分支”，并降低资源置信度。开启去重时，所有资源均按“类型 + 名称 + 操作”合并，并保留全部不同证据。

### 4. 用置信度表达静态分析边界

插件不会把“静态可能执行”描述成“运行时一定执行”：

| 置信度 | 含义 | 示例 |
|---|---|---|
| 已确认 | IDEA 能直接解析声明或注解证据 | PSI resolve、固定 Feign Mapping、Listener 注解 |
| 推断 | 有明确代码依据，但运行时仍存在分支 | 接口多实现、JPA 方法语义、自定义 HTTP 工具 |
| 待确认 | 存在动态值或静态信息不足 | 动态表名、动态 URL、无法还原的 Redis key |

扫描结果展示所有静态可能分支，不代表某一次请求会执行全部分支。需要判断真实运行路径时，应结合参数条件、Spring Bean 配置、运行日志或链路追踪系统复核。

### 5. 索引、线程和缓存一致性

项目级 resolve 和实现查找依赖 IDEA 索引。插件会等待 Smart Mode 后再扫描，避免在索引未完成时给出残缺结果。JetBrains 对 Dumb/Smart Mode 的说明见 [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)。

扫描运行在可取消后台任务中，并通过 Non-Blocking Read Action 读取 PSI，避免阻塞 IDEA UI。相关线程规则见 [IntelliJ Platform Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)。

结果缓存包含 PSI 修改计数；源码发生变化后旧缓存自动失效并重新分析。源码跳转使用 Smart Pointer 保存证据位置，避免直接长期持有可能失效的 PSI 元素。

## 使用

1. 将光标放在 Java 方法中，右键选择 `分析链路`。
2. 或打开 `Tools | Backend Chain Audit | 按路径或 Topic 分析`。
3. 批量统计 HTTP 接口时，打开底部 `Backend Chain Audit` 工具窗口并点击 `批量统计`，或打开 `Tools | Backend Chain Audit | 批量统计 HTTP 接口`。
4. 在底部 `Backend Chain Audit` 工具窗口查看调用树和资源，双击可跳转源码。
5. 在 `Settings | Tools | Backend Chain Audit` 配置扫描规则。

资源表支持按类型、资源和操作筛选，点击类型表头可排序。

### 单条分析

- **当前方法**：在 Java 方法内右键选择 `分析链路`，以当前方法作为入口分析调用链和资源。
- **HTTP 路径**：通过 `按路径或 Topic 分析` 输入 `/api/order/save` 这类 HTTP 路径，插件会定位 Spring MVC Controller 方法并分析。
- **MQ Topic**：通过 `按路径或 Topic 分析` 输入 Kafka/RabbitMQ/RocketMQ topic 或 queue，插件会定位本地消费者并分析。

单条 HTTP 分析保持宽松定位：当同一路径匹配多个方法时，会合并所有候选结果并在警告中说明。

### 批量统计 HTTP 接口

批量入口用于对接口清单做资源统计，不在面板内合并展示所有接口结果，只通过文件导出。

使用方式：

1. 打开 `Tools | Backend Chain Audit | 批量统计 HTTP 接口`，或在工具窗口点击 `批量统计`。
2. 在多行文本框中输入 HTTP 接口路径，一行一个，最多 100 个去重后的路径。
3. 选择导出格式：默认导出 Markdown，可额外勾选 CSV；至少需要选择一种格式。
4. 选择保存位置后开始后台任务。进度按接口维度展示，例如 `进度 12/80：/api/order/save`。

批量输入会先校验，校验失败时不会启动后台扫描，并会提示具体行号和原因：

- 自动忽略空行，并按完整路径去重；重复行会提示已去重数量和对应行号。
- 只接受 `/` 开头的 HTTP 路径；不接受完整 URL、根路径 `/`、空格、query 参数或 fragment。
- 去重后的路径数不能超过 100 个。

批量定位规则更严格：

- 未定位到接口会跳过。
- 同一 URL 定位到多个接口会跳过。
- Mapping 定义在 `interface` 方法上时，仅在唯一非抽象实现存在时继续分析实现方法。
- `interface` 方法存在多个实现或找不到实现时会跳过。
- 用户取消后，已完成部分仍会写入导出文件，并在 Markdown 总览中标注取消状态。

批量导出内容：

- **Markdown**：包含总览统计、按 URL 的结果表、成功接口资源汇总、跳过/失败原因明细。
- **CSV**：包含明细行和资源证据列，列包括序号、输入 URL、状态、跳过/失败原因、HTTP 方法、入口、分析方法、方法数、资源数、资源类型、资源名称、操作、置信度、证据。
- 成功但无资源的接口仍输出入口摘要。
- 跳过和失败的行会明确写出原因，资源列留空。

## 配置

- **最大方法递归深度**：限制递归层数，防止超大调用图持续扩张。
- **调用链仅展示当前工程源码方法**：隐藏 Maven/Gradle 依赖和第三方工具包方法，只保留 IDEA 当前 Project 源码目录中的方法；资源仍从工程内调用点识别。
- **过滤包前缀**：匹配的方法不会展示、统计或导出，例如 `java.`、`org.slf4j.`。
- **沿本地 MQ 继续扫描**：根据 RabbitMQ、Kafka、RocketMQ 的生产 topic/queue 查找当前 IDEA Project 内的消费者。
- **资源去重**：对全部资源类型按“类型 + 名称 + 操作”去重，并合并证据。
- **HTTP 工具类前缀**：配置工程自定义 HTTP wrapper，例如 `jsh.mgt.lib.http.BasicHttpUtil`；调用 URL 支持从局部变量初始化表达式回溯，识别 `@Value` 字段和字符串拼接。
- **自定义 MQ 生产者注解**：配置生产者字段注解短名或完整类名，读取 `queue/value/name`；默认启用 `JshRabbitProducer`。
- **自定义 MQ 消费者注解**：配置消费者方法或类注解短名或完整类名，读取 `topics/topic/queues/queue/value/name`；默认启用 `JshRabbitConsumer`。
- **自定义 MQ 生产者类**：配置生产者 wrapper 短名或完整类名，读取 `sendMsg/sendDelayMsg/sendMessage/sendDelayMessage` 的前两个参数作为 RocketMQ topic/tag；默认启用 `JshRocketMqProducer`。
- **自定义 MQ 消费者接口**：配置消费者接口短名或完整类名，从实现类的 `topic()/tag()` 返回值提取 RocketMQ topic/tag；默认启用 `JshRocketMqListener`。
- **隐藏简单访问器**：调用链默认过滤与实体字段对应的平凡 getter/setter，可在设置中关闭，不会屏蔽 `getOrderInfo` 等业务方法。
- **Feign 服务名**：外围接口资源同时展示 `@FeignClient` 显式声明的服务名、HTTP 方法和完整路径。

包前缀、HTTP 工具类和自定义 MQ 配置均支持使用逗号或换行分隔。

## 当前覆盖

- Java PSI 调用链、接口多实现、`@Qualifier`、`@Primary`、方法引用、自调用、模板方法和循环保护。
- Spring MVC HTTP 路径、Kafka/RabbitMQ/RocketMQ topic/queue 入口。
- MyBatis XML（含 `<include>`）、MyBatis-Plus 常用 CRUD、MyBatis Generator Example、JPA Repository、Native SQL 和 JSqlParser 表提取。
- Redis、Spring Cache、标准 Rabbit/Kafka/RocketMQ、阿里云 ONS、`JshRocketMqProducer` / `JshRocketMqListener` 包装器、自定义 MQ 注解、Feign 和可配置 HTTP 工具类，HTTP URL 支持常量、`@Value` 字段和局部变量拼接。
- 资源筛选、排序、全类型去重、源码跳转，以及 Markdown 总览、CSV 明细和 Mermaid 导出。
- 批量 HTTP 接口统计，支持 Markdown/CSV 导出、严格接口定位、interface 唯一实现分析、跳过原因标注和可取消后台进度。

## 当前边界

- Kotlin 仅保证基础 UAST 入口与调用解析，完整度以 Java 为主。
- 反射、脚本执行、运行时代理、远程配置动态路由和无法确定的工厂分发不能完全静态还原。
- 动态 SQL 会输出所有可识别候选，不保证等同数据库最终执行 SQL。
- ES、MongoDB、Dubbo、QueryDSL、MyBatis-Plus 动态表名插件和 Wrapper 字段条件尚未深度解析。
- 当前只递进同一 IDEA Project 内的 MQ 消费者，跨工程目录配置尚未启用。
- 插件的目标是提高静态审计覆盖率和可解释性，不能替代集成测试、运行日志和生产链路追踪。

## 构建

构建要求 JDK 21：

```bash
JAVA_HOME="<JDK 21 Home>" ./gradlew clean test buildPlugin verifyPluginStructure
```

安装包生成在 `build/distributions/`。

## 许可证

本项目采用 MIT License。你可以在 MIT 协议约束下自由使用、复制、修改、合并、发布、分发、再授权或销售本项目副本。
