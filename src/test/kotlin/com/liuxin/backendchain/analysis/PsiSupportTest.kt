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
import kotlin.test.assertContains
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

    fun testOnlyProjectSourceHidesDependencyMethodsFromGraph() {
        myFixture.configureByText(
            "ProjectOnlyService.java",
            """
                package com.haier.jsh.order;
                class ProjectOnlyService {
                    void run(java.util.List<String> values) {
                        values.isEmpty();
                        save();
                    }
                    void save() {}
                }
            """.trimIndent()
        )

        val method = findClass("com.haier.jsh.order.ProjectOnlyService").findMethodsByName("run", false).single()
        val result = CallGraphAnalyzer(
            project,
            AnalysisOptions(excludedPackagePrefixes = emptyList(), onlyProjectSource = true),
            emptyList()
        ).analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertTrue(result.callGraph.methods.values.none { it.className.startsWith("java.") })
        assertTrue(result.callGraph.methods.values.any { it.className == "com.haier.jsh.order.ProjectOnlyService" && it.methodName == "save" })
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

    fun testFeignUsesExplicitValueWhenNameHasEmptyDefault() {
        myFixture.configureByText(
            "FeignAnnotations.java",
            """
                @interface FeignClient {
                    String name() default "";
                    String value() default "";
                    String url() default "";
                    String path() default "";
                }
                @interface GetMapping {
                    String[] value() default {};
                    String[] path() default {};
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "UserFeign.java",
            """
                @FeignClient(value = "${'$'}{ylh.cloud.service.user}/api")
                interface UserFeign {
                    @GetMapping("/inner/member/shop/search-site-address-by-id")
                    String searchSiteAddressById(Long id);
                }
                class RetailReturnService {
                    UserFeign userFeign;
                    String run(Long id) { return userFeign.searchSiteAddressById(id); }
                }
            """.trimIndent()
        )

        val method = findClass("RetailReturnService").findMethodsByName("run", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = ExternalHttpExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(
            "${'$'}{ylh.cloud.service.user}/api GET /inner/member/shop/search-site-address-by-id",
            resource.name
        )
    }

    fun testHidesOnlyFieldBackedSimpleAccessors() {
        myFixture.configureByText(
            "AccessorService.java",
            """
                class OrderDto {
                    private String orderNo;
                    String getOrderNo() { return orderNo; }
                    void setOrderNo(String orderNo) { this.orderNo = orderNo; }
                }
                class OrderService {
                    OrderDto dto = new OrderDto();
                    void run() {
                        dto.setOrderNo("1");
                        dto.getOrderNo();
                        getOrderInfo();
                    }
                    void getOrderInfo() {}
                }
            """.trimIndent()
        )

        val method = findClass("OrderService").findMethodsByName("run", false).single()
        val result = CallGraphAnalyzer(project, AnalysisOptions(hideSimpleAccessors = true), emptyList())
            .analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertTrue(result.callGraph.methods.values.none { it.methodName in setOf("getOrderNo", "setOrderNo") })
        assertTrue(result.callGraph.methods.values.any { it.methodName == "getOrderInfo" })
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

    fun testDeduplicatesEveryResourceTypeAndMergesEvidence() {
        val resources = ResourceType.entries.flatMap { type ->
            listOf(
                ResourceRef(type, "same-resource", Operation.READ, Confidence.CONFIRMED, "first", null),
                ResourceRef(type, "same-resource", Operation.READ, Confidence.UNRESOLVED, "second", null)
            )
        }

        val merged = ResourceDeduplicator.normalize(resources, true)

        assertEquals(ResourceType.entries.size, merged.size)
        assertTrue(merged.all { it.confidence == Confidence.UNRESOLVED })
        assertTrue(merged.all { it.detail.contains("first") && it.detail.contains("second") })
        assertEquals(resources.size, ResourceDeduplicator.normalize(resources, false).size)
    }

    fun testResourceEvidenceContainsInferredImplementationPath() {
        myFixture.configureByText(
            "DispatchServices.java",
            """
                interface AddService { void insert(); }
                class RetailAddService implements AddService {
                    @Override public void insert() { saveRetail(); }
                    void saveRetail() {}
                }
                class PurchaseAddService implements AddService {
                    @Override public void insert() { savePurchase(); }
                    void savePurchase() {}
                }
                class Controller {
                    AddService service;
                    void create() { service.insert(); }
                }
            """.trimIndent()
        )
        val method = findClass("Controller").findMethodsByName("create", false).single()
        val extractor = object : ResourceExtractor {
            override fun extract(context: CallContext): List<ResourceRef> {
                val target = context.resolvedMethod ?: return emptyList()
                if (target.name !in setOf("saveRetail", "savePurchase")) return emptyList()
                return listOf(
                    ResourceRef(ResourceType.MYSQL, target.name, Operation.WRITE, Confidence.CONFIRMED, "repository save", null)
                )
            }
        }

        val result = CallGraphAnalyzer(project, AnalysisOptions(), listOf(extractor))
            .analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertEquals(2, result.resources.size)
        assertTrue(result.resources.all { it.confidence == Confidence.INFERRED })
        assertTrue(result.resources.all { it.detail.contains("调用路径：Controller.create") })
        assertTrue(result.resources.any { it.detail.contains("PurchaseAddService.insert") })
        assertTrue(result.resources.all { it.detail.contains("原始证据：repository save") })
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

    fun testExtractsRocketMqProducerTopicAndTag() {
        myFixture.configureByText(
            "RocketProducer.java",
            """
                class RocketMQTemplate {
                    void syncSend(String destination, Object body) {}
                }
                class RocketProducer {
                    RocketMQTemplate template;
                    void send(Object body) { template.syncSend("order-topic:created", body); }
                }
            """.trimIndent()
        )

        val method = findClass("RocketProducer").findMethodsByName("send", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(ResourceType.ROCKETMQ, resource.type)
        assertEquals("order-topic:created", resource.name)
        assertEquals(Operation.PRODUCE, resource.operation)
        assertEquals(Confidence.CONFIRMED, resource.confidence)
    }

    fun testExtractsAliyunOnsSendAsyncMessageTopicAndTag() {
        myFixture.configureByText(
            "Message.java",
            """
                package com.aliyun.openservices.ons.api;
                public class Message {
                    public Message(String topic, String tag, String key, byte[] body) {}
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Producer.java",
            """
                package com.aliyun.openservices.ons.api;
                public interface Producer { void sendAsync(Message message, Object callback); }
            """.trimIndent()
        )
        myFixture.configureByText(
            "OnsProducer.java",
            """
                import com.aliyun.openservices.ons.api.Message;
                import com.aliyun.openservices.ons.api.Producer;
                @interface Value { String value(); }
                class OnsProducer {
                    @Value("${'$'}{aliyun.rocketMQ.cancelOrder.topic}") String topic;
                    @Value("${'$'}{aliyun.rocketMQ.cancelOrder.messageTag}") String messageTag;
                    Producer producer;
                    void send(byte[] body) {
                        Message message = new Message(topic, messageTag, "key", body);
                        producer.sendAsync(message, new Object());
                    }
                }
            """.trimIndent()
        )

        val method = findClass("OnsProducer").findMethodsByName("send", false).single()
        val call = findMethodCalls(method).single { it.methodExpression.referenceName == "sendAsync" }
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(ResourceType.ROCKETMQ, resource.type)
        assertEquals(
            "${'$'}{aliyun.rocketMQ.cancelOrder.topic}:${'$'}{aliyun.rocketMQ.cancelOrder.messageTag}",
            resource.name
        )
        assertEquals(Operation.PRODUCE, resource.operation)
        assertContains(resource.detail, "ONS/RocketMQ Message")
    }

    fun testExtractsAndLocatesAliyunOnsSubscribeConsumer() {
        myFixture.configureByText(
            "Consumer.java",
            """
                package com.aliyun.openservices.ons.api;
                public interface Consumer { void subscribe(String topic, String tag, Object listener); }
            """.trimIndent()
        )
        myFixture.configureByText(
            "OnsConsumer.java",
            """
                import com.aliyun.openservices.ons.api.Consumer;
                @interface Value { String value(); }
                class OnsConsumer {
                    @Value("${'$'}{aliyun.rocketMQ.cancelOrder.topic}") String topic;
                    @Value("${'$'}{aliyun.rocketMQ.cancelOrder.messageTag}") String messageTag;
                    Consumer consumer;
                    void start() { consumer.subscribe(topic, messageTag, new Object()); }
                }
            """.trimIndent()
        )

        val method = findClass("OnsConsumer").findMethodsByName("start", false).single()
        val call = findMethodCalls(method).single { it.methodExpression.referenceName == "subscribe" }
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        val destination = "${'$'}{aliyun.rocketMQ.cancelOrder.topic}:${'$'}{aliyun.rocketMQ.cancelOrder.messageTag}"
        assertEquals(destination, resource.name)
        assertEquals(Operation.CONSUME, resource.operation)
        assertEquals(1, EntryPointLocator(project).byMqTopic(destination).size)
    }

    fun testUsesConfiguredCustomMqAnnotationsWithoutHardcodedName() {
        myFixture.configureByText(
            "CustomMqAnnotations.java",
            """
                @interface CompanyProducer { String queue(); }
                @interface CompanyConsumer { String topic(); }
                class Sender { void send(String body) {} }
                class CustomMqService {
                    @CompanyProducer(queue = "company.queue") Sender sender;
                    void publish() { sender.send("body"); }
                    @CompanyConsumer(topic = "company.topic") void consume() {}
                }
            """.trimIndent()
        )

        val publish = findClass("CustomMqService").findMethodsByName("publish", false).single()
        val sendCall = findMethodCalls(publish).single()
        val configured = InfrastructureExtractor(listOf("CompanyProducer"), listOf("CompanyConsumer"))
        val produced = configured.extract(CallContext(publish, sendCall, sendCall.resolveMethod(), sendCall.text)).single()
        val consume = findClass("CustomMqService").findMethodsByName("consume", false).single()
        val consumed = configured.extract(CallContext(consume, null, consume, consume.text)).single()

        assertEquals("company.queue", produced.name)
        assertEquals(Operation.PRODUCE, produced.operation)
        assertEquals("company.topic", consumed.name)
        assertEquals(Operation.CONSUME, consumed.operation)
        assertTrue(InfrastructureExtractor().extract(CallContext(publish, sendCall, sendCall.resolveMethod(), sendCall.text)).isEmpty())
    }

    fun testExtractsRocketMqClassListenerAndLocatesTopic() {
        myFixture.configureByText(
            "RocketConsumer.java",
            """
                @interface RocketMQMessageListener {
                    String topic();
                    String selectorExpression() default "*";
                }
                @RocketMQMessageListener(topic = "order-topic", selectorExpression = "created")
                class RocketConsumer {
                    void onMessage(String body) {}
                }
            """.trimIndent()
        )

        val method = findClass("RocketConsumer").findMethodsByName("onMessage", false).single()
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, null, method, method.text)).single()

        assertEquals(ResourceType.ROCKETMQ, resource.type)
        assertEquals("order-topic:created", resource.name)
        assertEquals(Operation.CONSUME, resource.operation)
        assertEquals(1, EntryPointLocator(project).byMqTopic("order-topic:created").size)
    }

    fun testExtractsMyBatisPlusBaseMapperAndTableName() {
        myFixture.configureByText(
            "MyBatisPlusMapper.java",
            """
                @interface TableName { String value(); }
                @TableName("retail_order") class RetailOrder {}
                interface BaseMapper<T> { int updateById(T entity); }
                interface RetailOrderMapper extends BaseMapper<RetailOrder> {}
                class RetailService {
                    RetailOrderMapper mapper;
                    int update(RetailOrder order) { return mapper.updateById(order); }
                }
            """.trimIndent()
        )

        val method = findClass("RetailService").findMethodsByName("update", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = MyBatisPlusExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("retail_order", resource.name)
        assertEquals(Operation.WRITE, resource.operation)
        assertEquals(Confidence.CONFIRMED, resource.confidence)
    }

    fun testExtractsMyBatisPlusServiceWrapperAndDefaultTableName() {
        myFixture.configureByText(
            "MyBatisPlusService.java",
            """
                class ShoppingCartItem {}
                class LambdaQueryWrapper<T> {}
                interface IService<T> { java.util.List<T> list(LambdaQueryWrapper<T> wrapper); }
                interface CartService extends IService<ShoppingCartItem> {}
                class CartController {
                    CartService service;
                    java.util.List<ShoppingCartItem> list(LambdaQueryWrapper<ShoppingCartItem> wrapper) {
                        return service.list(wrapper);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("CartController").findMethodsByName("list", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = MyBatisPlusExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("shopping_cart_item", resource.name)
        assertEquals(Operation.READ, resource.operation)
        assertEquals(Confidence.INFERRED, resource.confidence)
    }

    fun testExtractsMyBatisPlusServiceImplEntity() {
        myFixture.configureByText(
            "MyBatisPlusServiceImpl.java",
            """
                class InventoryRecord {}
                interface BaseMapper<T> {}
                class InventoryMapper implements BaseMapper<InventoryRecord> {}
                class ServiceImpl<M, T> { boolean save(T entity) { return true; } }
                class InventoryService extends ServiceImpl<InventoryMapper, InventoryRecord> {
                    boolean create(InventoryRecord record) { return save(record); }
                }
            """.trimIndent()
        )

        val method = findClass("InventoryService").findMethodsByName("create", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val context = CallContext(method, call, call.resolveMethod(), call.text)
        val extractor = MyBatisPlusExtractor()

        assertTrue(extractor.supports(context))
        val resource = extractor.extract(context).single()
        assertEquals("inventory_record", resource.name)
        assertEquals(Operation.WRITE, resource.operation)
    }

    fun testExtractsMyBatisPlusDbStaticUtility() {
        myFixture.configureByText(
            "MyBatisPlusDb.java",
            """
                class OrderRecord {}
                class Db { static <T> java.util.List<T> list(Class<T> type) { return null; } }
                class OrderQuery { java.util.List<OrderRecord> list() { return Db.list(OrderRecord.class); } }
            """.trimIndent()
        )

        val method = findClass("OrderQuery").findMethodsByName("list", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = MyBatisPlusExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("order_record", resource.name)
        assertEquals(Operation.READ, resource.operation)
    }

    fun testExtractsMbgUpdateByExampleSelectiveFromXml() {
        myFixture.configureByText(
            "OrderMapper.java",
            """
                class OrderRecord {}
                class OrderExample {}
                interface OrderMapper {
                    int updateByExampleSelective(OrderRecord record, OrderExample example);
                }
                class OrderService {
                    OrderMapper mapper;
                    int update(OrderRecord record, OrderExample example) {
                        return mapper.updateByExampleSelective(record, example);
                    }
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "OrderMapper.xml",
            """
                <mapper namespace="OrderMapper">
                  <update id="updateByExampleSelective">
                    update order_record
                    <set><if test="record.name != null">name = #{record.name},</if></set>
                    where id = #{example.id}
                  </update>
                </mapper>
            """.trimIndent()
        )

        val method = findClass("OrderService").findMethodsByName("update", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = MyBatisExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("order_record", resource.name)
        assertEquals(Operation.WRITE, resource.operation)
    }

    fun testInfersMbgTableWhenXmlIsMissing() {
        myFixture.configureByText(
            "CartMapper.java",
            """
                class ShoppingCartRecord {}
                class ShoppingCartExample {}
                interface ShoppingCartMapper {
                    int updateByExampleSelective(ShoppingCartRecord record, ShoppingCartExample example);
                }
            """.trimIndent()
        )

        val target = findClass("ShoppingCartMapper").findMethodsByName("updateByExampleSelective", false).single()
        val resource = MyBatisExtractor()
            .extract(CallContext(target, null, target, target.text)).single()

        assertEquals("shopping_cart_record", resource.name)
        assertEquals(Operation.WRITE, resource.operation)
        assertEquals(Confidence.INFERRED, resource.confidence)
    }

    fun testResolvesTkMyBatisGenericMapperEntityInsteadOfTypeVariable() {
        myFixture.configureByText(
            "InsertSelectiveMapper.java",
            """
                package tk.mybatis.mapper.common.base.insert;
                public interface InsertSelectiveMapper<T> { int insertSelective(T record); }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Mapper.java",
            """
                package tk.mybatis.mapper.common;
                public interface Mapper<T>
                    extends tk.mybatis.mapper.common.base.insert.InsertSelectiveMapper<T> {}
            """.trimIndent()
        )
        myFixture.configureByText(
            "TkMapperUsage.java",
            """
                @interface Table { String name(); }
                @Table(name = "tripartite_purchase") class TripartitePurchase {}
                interface TripartitePurchaseMapper extends tk.mybatis.mapper.common.Mapper<TripartitePurchase> {}
                class TripartitePurchaseService {
                    TripartitePurchaseMapper mapper;
                    int save(TripartitePurchase entity) { return mapper.insertSelective(entity); }
                }
            """.trimIndent()
        )

        val method = findClass("TripartitePurchaseService").findMethodsByName("save", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = MyBatisExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("tripartite_purchase", resource.name)
        assertEquals(Operation.WRITE, resource.operation)
        assertTrue(resource.name !in setOf("t", "object"))
        assertContains(resource.detail, "调用点泛型实体推断")
    }

    fun testRejectsUnresolvedGenericPlaceholderAsTableName() {
        myFixture.configureByText("GenericEntity.java", "class ObjectHolder<T> {}")
        val typeParameter = findClass("ObjectHolder").typeParameters.single()

        assertEquals(null, EntityTableResolver.resolve(typeParameter))
        assertEquals(null, EntityTableResolver.inferredFromClassName("T"))
        assertEquals(null, EntityTableResolver.inferredFromClassName("Object"))
    }

    private fun findClass(name: String) = JavaPsiFacade.getInstance(project)
        .findClass(name, GlobalSearchScope.projectScope(project))
        ?: error("Class not found: $name")
}
