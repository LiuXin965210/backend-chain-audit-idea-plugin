# Backend Chain Audit

IntelliJ IDEA 内部插件，从 Java/Spring 方法、HTTP 路径或 MQ topic 出发，静态分析调用链及其涉及的 MySQL、Redis、RabbitMQ、Kafka 和外围 HTTP 接口。

当前版本：`0.1.7`

## 为什么使用 IDEA 插件扫描

普通脚本通常依赖正则、文本搜索或自行解析源码，难以准确处理重载方法、接口实现、继承、泛型、静态导入和依赖库符号。Backend Chain Audit 直接复用 IntelliJ IDEA 已构建的工程模型、Java 语义模型和索引，分析基础与 IDEA 的跳转定义、查找实现等代码能力一致。

主要优势：

- **语义解析而非文本匹配**：通过 PSI reference resolve 将调用表达式定位到真实 `PsiMethod`，能够区分同名方法、重载和不同包下的类。
- **理解完整工程上下文**：复用 IDEA 已导入的 Maven/Gradle 依赖、源码根、模块和依赖库，不需要重新实现 Java classpath 解析。
- **保留源码证据**：调用边和资源均关联 `SmartPsiElementPointer`，结果可双击跳转到调用位置、Mapper 方法或资源声明。
- **结果可解释**：每条边和资源记录解析依据及置信度，不把无法静态确认的结果伪装成确定事实。
- **贴近开发流程**：直接从光标方法启动，结果支持筛选、排序、源码跳转以及 Markdown/Mermaid 导出。
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
- **JPA**：根据 Repository 泛型、实体 `@Table`、Repository 方法和 `@Query` 识别表及读写操作。
- **SQL**：优先使用 JSqlParser 解析；动态 SQL 无法完整解析时使用后备识别并降低置信度。
- **Feign**：组合服务名、`@FeignClient.path`、类级 Mapping、方法级 Mapping 和 HTTP 方法。
- **自定义 HTTP 工具**：按设置中的工具类前缀识别，使用方法名推断 HTTP 方法，并解析首个 URL 参数及 `@Value` 配置占位符。
- **Kafka/RabbitMQ**：识别 Template、Producer wrapper、Listener、JSH 注解以及 `@Value` topic/queue 字段。
- **Redis**：识别 Spring Cache、RedisTemplate 和 Redis 组件调用，并尽可能保留 key 表达式。

每条资源都保存类型、名称、操作、置信度、证据说明和源码指针。开启去重时，MySQL 按“表名 + 操作”合并，外围接口按“HTTP 方法 + 地址”合并。

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

1. 将光标放在 Java 方法中，右键选择 `分析后端代码链路`。
2. 或打开 `Tools | Backend Chain Audit | 按路径或 Topic 分析`。
3. 在底部 `Backend Chain Audit` 工具窗口查看调用树和资源，双击可跳转源码。
4. 在 `Settings | Tools | Backend Chain Audit` 配置扫描规则。

资源表支持按类型、资源和操作筛选，点击类型表头可排序。

## 配置

- **最大方法递归深度**：限制递归层数，防止超大调用图持续扩张。
- **过滤包前缀**：匹配的方法不会展示、统计或导出，例如 `java.`、`org.slf4j.`。
- **沿本地 MQ 继续扫描**：根据生产 topic/queue 查找当前 IDEA Project 内的消费者。
- **资源去重**：对 MySQL 表和外围接口按资源语义去重。
- **HTTP 工具类前缀**：配置工程自定义 HTTP wrapper，例如 `jsh.mgt.lib.http.BasicHttpUtil`。

包前缀和 HTTP 工具类均支持使用逗号或换行分隔。

## 当前覆盖

- Java PSI 调用链、接口多实现、`@Qualifier`、`@Primary`、方法引用、自调用、模板方法和循环保护。
- Spring MVC HTTP 路径、Kafka/RabbitMQ topic/queue 入口。
- MyBatis XML（含 `<include>`）、JPA Repository、Native SQL 和 JSqlParser 表提取。
- Redis、Spring Cache、标准 Rabbit/Kafka、JSH Rabbit 注解、Feign 和可配置 HTTP 工具类。
- 资源筛选、排序、去重、源码跳转，以及 Markdown 总览和 Mermaid 导出。

## 当前边界

- Kotlin 仅保证基础 UAST 入口与调用解析，完整度以 Java 为主。
- 反射、脚本执行、运行时代理、远程配置动态路由和无法确定的工厂分发不能完全静态还原。
- 动态 SQL 会输出所有可识别候选，不保证等同数据库最终执行 SQL。
- ES、MongoDB、RocketMQ、Dubbo、QueryDSL 和 MyBatis-Plus Wrapper 尚未深度解析。
- 当前只递进同一 IDEA Project 内的 MQ 消费者，跨工程目录配置尚未启用。
- 插件的目标是提高静态审计覆盖率和可解释性，不能替代集成测试、运行日志和生产链路追踪。

## 构建

构建要求 JDK 21：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew clean test buildPlugin verifyPluginStructure
```

安装包生成在 `build/distributions/`。
