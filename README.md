# Backend Chain Audit

IntelliJ IDEA 内部插件，从 Java/Spring 方法、HTTP 路径或 MQ topic 出发，静态分析调用链及其涉及的 MySQL、Redis、RabbitMQ、Kafka 和外围 HTTP 接口。

当前版本：`0.1.7`

## 构建

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew buildPlugin
```

安装包生成在 `build/distributions/`。

## 使用

1. 将光标放在 Java 方法中，右键选择 `分析后端代码链路`。
2. 或打开 `Tools | Backend Chain Audit | 按路径或 Topic 分析`。
3. 在底部 `Backend Chain Audit` 工具窗口查看调用树和资源，双击可跳转源码。

静态分析会展示所有可能分支，并以“已确认、推断、待确认”区分置信度，不代表某次运行时请求一定执行全部分支。

## 首版覆盖

- Java PSI 调用链、接口多实现、`@Qualifier`、`@Primary`、自调用和循环保护。
- Spring MVC HTTP 路径、Kafka/RabbitMQ topic/queue 入口。
- MyBatis XML（含 `<include>`）、JPA Repository、Native SQL 和 JSqlParser 表提取。
- Redis、Spring Cache、标准 Rabbit/Kafka、JSH Rabbit 注解、Feign、RestTemplate/WebClient 候选。
- 工具窗口源码跳转，以及 Markdown 总览和 Mermaid 导出。

## 当前边界

- Kotlin 仅通过 UAST 提供光标方法入口，调用链完整度以 Java 为主。
- 工厂运行时分发、动态 Bean 选择和动态 SQL 会保留所有静态候选。
- ES、MongoDB、RocketMQ、Dubbo、QueryDSL 和 MyBatis-Plus Wrapper 深度解析留给后续 extractor。
- 当前只递进同一 IDEA Project 内的 MQ 消费者，跨工程目录配置尚未启用。
