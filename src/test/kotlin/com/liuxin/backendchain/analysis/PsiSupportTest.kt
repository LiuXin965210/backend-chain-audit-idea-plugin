package com.liuxin.backendchain.analysis

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.liuxin.backendchain.model.AnalysisOptions
import com.liuxin.backendchain.model.EntryPoint
import com.liuxin.backendchain.model.EntryType
import com.liuxin.backendchain.model.Operation
import com.liuxin.backendchain.model.ResourceRef
import com.liuxin.backendchain.model.ResourceType
import com.liuxin.backendchain.model.Confidence
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PsiSupportTest : BasePlatformTestCase() {
    fun testFindsMethodReferences() {
        myFixture.configureByText(
            "OrderService.java",
            """
                class OrderService {
                    void execute(java.util.function.Consumer<String> action) {}
                    void createTradeOrder(String context) {}
                    void insertTradeOrder() {
                        execute(this::createTradeOrder);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("OrderService").findMethodsByName("insertTradeOrder", false).single()
        val references = findMethodReferences(method)

        assertEquals(1, references.size)
        assertEquals("createTradeOrder", (references.single().resolve() as com.intellij.psi.PsiMethod).name)
    }

    fun testFindsConcreteTemplateMethodOverrides() {
        myFixture.configureByText(
            "Services.java",
            """
                class BaseService {
                    protected void insertParamCheck() {}
                }
                class CartService extends BaseService {
                    @Override protected void insertParamCheck() {}
                }
                class PlainService extends BaseService {}
            """.trimIndent()
        )

        val method = findClass("BaseService").findMethodsByName("insertParamCheck", false).single()
        val targets = resolveImplementations(project, method)

        assertTrue(targets.any { it.containingClass?.name == "BaseService" })
        assertTrue(targets.any { it.containingClass?.name == "CartService" })
        assertTrue(targets.none { it.containingClass?.name == "PlainService" })
    }

    fun testExcludedJdkCallsAreNotAddedToGraph() {
        myFixture.configureByText(
            "AuditService.java",
            """
                class AuditService {
                    void run(java.util.List<String> values) {
                        values.isEmpty();
                        save();
                    }
                    void save() {}
                }
            """.trimIndent()
        )

        val method = findClass("AuditService").findMethodsByName("run", false).single()
        val result = CallGraphAnalyzer(project, com.liuxin.backendchain.model.AnalysisOptions(), emptyList())
            .analyze(com.liuxin.backendchain.model.EntryPoint(com.liuxin.backendchain.model.EntryType.METHOD, "test"), method)

        assertTrue(result.callGraph.methods.values.none { it.className.startsWith("java.") })
        assertTrue(result.callGraph.methods.values.any { it.methodName == "save" })
    }

    fun testFindsInterfaceImplementationBySuperMethod() {
        myFixture.configureByText(
            "ReplenishService.java",
            """
                interface ReplenishService {
                    void save(String value);
                }
                class ReplenishServiceImpl implements ReplenishService {
                    @Override public void save(String value) {}
                }
            """.trimIndent()
        )

        val method = findClass("ReplenishService").findMethodsByName("save", false).single()
        val targets = resolveImplementations(project, method)

        assertEquals(listOf("ReplenishServiceImpl"), targets.map { it.containingClass?.name })
    }

    fun testExtractsBasicHttpUtilConfiguredPrefixAndConstantPath() {
        myFixture.configureByText("Value.java", "@interface Value { String value(); }")
        myFixture.configureByText(
            "JshApiClient.java",
            """
                class BasicHttpUtil {
                    Object postForObject(String url, Object body) { return null; }
                }
                class JshApiClient {
                    @Value("${'$'}{replenish.jsh.open-api-prefix}") String domainUrl;
                    BasicHttpUtil basicHttpUtil;
                    void createPopscOrder(Object body) {
                        basicHttpUtil.postForObject(
                            domainUrl + "/btbrrs/jsh-zhilian/api/popsc/receive", body);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("JshApiClient").findMethodsByName("createPopscOrder", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = ExternalHttpExtractor(listOf("BasicHttpUtil"))
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(ResourceType.EXTERNAL_HTTP, resource.type)
        assertEquals("POST \${replenish.jsh.open-api-prefix}/btbrrs/jsh-zhilian/api/popsc/receive", resource.name)
    }

    fun testDeduplicatesMysqlAndExternalResourcesWhenEnabled() {
        myFixture.configureByText(
            "ResourceService.java",
            """
                class ResourceService {
                    void run() { first(); second(); }
                    void first() {}
                    void second() {}
                }
            """.trimIndent()
        )
        val method = findClass("ResourceService").findMethodsByName("run", false).single()
        val extractor = object : ResourceExtractor {
            override fun extract(context: CallContext): List<ResourceRef> {
                val target = context.resolvedMethod ?: return emptyList()
                if (target.name !in setOf("first", "second")) return emptyList()
                return listOf(ResourceRef(ResourceType.MYSQL, "trade_order", Operation.READ, Confidence.INFERRED, target.name, null))
            }
        }

        val result = CallGraphAnalyzer(project, AnalysisOptions(deduplicateResources = true), listOf(extractor))
            .analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertEquals(1, result.resources.size)
    }

    fun testExtractsKafkaTopicFromProducerValueField() {
        myFixture.configureByText("KafkaValue.java", "@interface Value { String value(); }")
        myFixture.configureByText(
            "OrderKafkaProducer.java",
            """
                class OrderKafkaProducer {
                    @Value("${'$'}{spring.kafka.order.producer.topic}") String orderTopic;
                    Boolean send(String data) { return true; }
                }
                class OrderService {
                    OrderKafkaProducer producer;
                    void create(String data) { producer.send(data); }
                }
            """.trimIndent()
        )

        val method = findClass("OrderService").findMethodsByName("create", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(ResourceType.KAFKA, resource.type)
        assertEquals("${'$'}{spring.kafka.order.producer.topic}", resource.name)
        assertEquals(Operation.PRODUCE, resource.operation)
    }

    fun testExtractsKafkaTopicFromKafkaTemplateArgumentValueField() {
        myFixture.configureByText("KafkaValue2.java", "@interface Value { String value(); }")
        myFixture.configureByText(
            "KafkaTemplate.java",
            """
                class KafkaTemplate { void send(String topic, String data) {} }
                class Producer {
                    @Value("${'$'}{spring.kafka.order.producer.topic}") String orderTopic;
                    KafkaTemplate template;
                    void send(String data) { template.send(orderTopic, data); }
                }
            """.trimIndent()
        )

        val method = findClass("Producer").findMethodsByName("send", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("${'$'}{spring.kafka.order.producer.topic}", resource.name)
    }

    private fun findClass(name: String) = JavaPsiFacade.getInstance(project)
        .findClass(name, GlobalSearchScope.projectScope(project))
        ?: error("Class not found: $name")
}
