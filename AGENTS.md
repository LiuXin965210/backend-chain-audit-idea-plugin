# AGENTS.md

## 基本约定

- 本仓库是 IntelliJ IDEA 插件 `Backend Chain Audit`，核心目标是基于 IDEA PSI/索引做 Java/Spring 调用链、资源表、中间件和外围接口的静态审计。
- 修改前先确认当前分支和远端状态；涉及打包、发版、推送前必须先同步最新 `main`，避免基于旧版本打包。
- 不要回滚用户已有改动；遇到工作树非空时先说明相关文件，再只处理本任务范围。

## 编码前思考
- 明确假设，不确定时询问而非猜测。
- 存在歧义时，列出多种解释，不默默选一种。
- 如果任务有明显更简单的做法，直接指出。
- 发现矛盾或不一致时停下来，要求澄清。

## 简洁优先
- 用最少的代码解决问题。
- 不为一次性需求创建抽象层。
- 不为"万一以后需要"加灵活性和可配置性。
- 如果 200 行可以写成 50 行，重写它。
- 检查标准：资深工程师会觉得这过于复杂吗？如果是，简化。

## 精准修改

- 只修改与当前任务直接相关的代码。
- 不顺手改进相邻代码、注释或格式。
- 不重构本来能正常工作的部分。
- 匹配现有代码风格，即使你更偏好另一种写法。
- 因你的修改而变成死代码的导入和变量，删除掉。
- 发现预先存在的死代码时，提出来但不要删。

## 目标驱动执行

- 定义清晰的成功标准再开始。
- "修复 bug" 转化为 "写一个重现 bug 的测试，然后让它通过"。
- "添加验证" 转化为 "为无效输入写测试，然后让它们通过"。
- "重构 X" 转化为 "确保重构前后所有测试都能通过"。
- 多步骤任务先给简短计划，每一步带验证方式。

## 构建与验证

- 本机默认 `java` 是 JDK 8，不能运行当前 Gradle 工程。
- 优先使用 IDEA 自带 JBR 构建：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew test buildPlugin
```

- 如需完整清理打包：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew clean buildPlugin
```

- 静态分析核心回归至少跑：

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./gradlew test --tests com.liuxin.backendchain.analysis.PsiSupportTest
```

- 安装包生成在 `build/distributions/`。构建后要确认 zip 文件名、内部 jar 版本和 `gradle.properties` 的 `pluginVersion` 一致。

## 发版注意

- 升版本时先确认当前 `gradle.properties`、已有 `build/distributions/` 包和远端 `main` 状态。
- 小功能递增补丁/小版本；用户明确“升一个大版本”时可从 `0.2.5` 升到 `1.0.0`。
- 发版说明中要给出：
  - 修改的版本号
  - 构建命令
  - 测试结果
  - 新包绝对路径
- 注释时刻更新README.md中的功能描述
