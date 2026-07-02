package com.liuxin.backendchain.analysis

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.liuxin.backendchain.model.AnalysisOptions
import com.liuxin.backendchain.model.BatchRowStatus
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

    fun testConstructorMethodReferenceDoesNotWarn() {
        myFixture.configureByText(
            "StockHoldingService.java",
            """
                class StockHolding {
                    StockHolding(String code) {}
                }
                class StockHoldingService {
                    java.util.List<StockHolding> convert(java.util.List<String> codes) {
                        return codes.stream().map(StockHolding::new).collect(java.util.stream.Collectors.toList());
                    }
                }
            """.trimIndent()
        )

        val method = findClass("StockHoldingService").findMethodsByName("convert", false).single()
        val result = CallGraphAnalyzer(
            project,
            AnalysisOptions(),
            emptyList()
        ).analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertTrue(result.warnings.none { it.message.contains("StockHolding::new") })
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

    fun testConcreteMethodOverridesAreOptInForCallGraph() {
        myFixture.configureByText(
            "ConcreteOverrideServices.java",
            """
                class BaseService {
                    void run() { save(); }
                    protected void save() { baseRepo(); }
                    void baseRepo() {}
                }
                class ChildService extends BaseService {
                    @Override protected void save() { childRepo(); }
                    void childRepo() {}
                }
            """.trimIndent()
        )
        val method = findClass("BaseService").findMethodsByName("run", false).single()
        val extractor = object : ResourceExtractor {
            override fun extract(context: CallContext): List<ResourceRef> {
                val target = context.resolvedMethod ?: return emptyList()
                if (target.name !in setOf("baseRepo", "childRepo")) return emptyList()
                return listOf(ResourceRef(ResourceType.MYSQL, target.name, Operation.READ, Confidence.CONFIRMED, "repo", null))
            }
        }

        val defaultResult = CallGraphAnalyzer(project, AnalysisOptions(), listOf(extractor))
            .analyze(EntryPoint(EntryType.METHOD, "test"), method)
        val optInResult = CallGraphAnalyzer(project, AnalysisOptions(followConcreteMethodOverrides = true), listOf(extractor))
            .analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertEquals(listOf("baseRepo"), defaultResult.resources.map { it.name })
        assertEquals(setOf("baseRepo", "childRepo"), optInResult.resources.map { it.name }.toSet())
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

    fun testResourceNameNarrowsInterfaceImplementation() {
        myFixture.configureByText(
            "Resource.java",
            """
                package javax.annotation;
                public @interface Resource {
                    String name() default "";
                    String value() default "";
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Service.java",
            """
                package org.springframework.stereotype;
                public @interface Service {
                    String value() default "";
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "ResourceDispatch.java",
            """
                import javax.annotation.Resource;
                import org.springframework.stereotype.Service;

                interface AddService { void insert(); }
                @Service("cloudAddService")
                class CloudAddService implements AddService {
                    @Override public void insert() {}
                }
                @Service("storeAddService")
                class StoreAddService implements AddService {
                    @Override public void insert() {}
                }
                class Controller {
                    @Resource(name = "cloudAddService")
                    private AddService service;
                    void create() { service.insert(); }
                }
            """.trimIndent()
        )

        val method = findClass("Controller").findMethodsByName("create", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resolved = call.resolveMethod()!!
        val targets = resolveImplementations(project, resolved, call)

        assertEquals(listOf("CloudAddService"), targets.map { it.containingClass?.name })
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

    fun testResolvesExternalHttpPlaceholderFromYaml() {
        myFixture.configureByText("Value.java", "@interface Value { String value(); }")
        myFixture.addFileToProject(
            "src/main/resources/application.yml",
            """
                msg:
                  url:
                    send-message: http://notice-inner/api/notice/send
            """.trimIndent()
        )
        myFixture.configureByText(
            "NoticeClient.java",
            """
                class BasicHttpUtil {
                    Object exchange(String url, Object body) { return null; }
                }
                class NoticeClient {
                    @Value("${'$'}{msg.url.send-message}") String sendMessageUrl;
                    BasicHttpUtil basicHttpUtil;
                    void send(Object body) {
                        basicHttpUtil.exchange(sendMessageUrl, body);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("NoticeClient").findMethodsByName("send", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = ExternalHttpExtractor(listOf("BasicHttpUtil"))
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("未声明 http://notice-inner/api/notice/send", resource.name)
        assertContains(resource.detail, "配置解析：\${msg.url.send-message} -> http://notice-inner/api/notice/send")
    }

    fun testExtractsHttpClientUrlFromStringFormatInitializer() {
        myFixture.configureByText("Value.java", "@interface Value { String value(); }")
        myFixture.addFileToProject(
            "src/main/resources/application.yml",
            """
                ylh:
                  gateway: https://dev.ylhtest.com
                  cloud:
                    service:
                      policy: ylh-cloud-service-policy
            """.trimIndent()
        )
        myFixture.configureByText(
            "PromotionClient.java",
            """
                class SpringCloudInnerUtil {
                    static Object postForObject(String url, Object body, Object reference) { return null; }
                }
                class PromotionClient {
                    @Value("${'$'}{ylh.gateway}") String ylhGateway;
                    @Value("${'$'}{ylh.cloud.service.policy}") String policyFeignName;

                    void send(Object body) {
                        String url = String.format(
                            "%s/%s/api/page/promotion/common/order-info-back-add",
                            ylhGateway, policyFeignName);
                        SpringCloudInnerUtil.postForObject(url, body, null);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("PromotionClient").findMethodsByName("send", false).single()
        val call = PsiTreeUtil.findChildrenOfType(method.body, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "postForObject" }
        val resource = ExternalHttpExtractor(listOf("SpringCloudInnerUtil"))
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(
            "POST https://dev.ylhtest.com/ylh-cloud-service-policy/api/page/promotion/common/order-info-back-add",
            resource.name
        )
        assertContains(resource.detail, "配置解析：\${ylh.gateway}/\${ylh.cloud.service.policy}/api/page/promotion/common/order-info-back-add")
    }

    fun testResolvesFeignPlaceholderFromProperties() {
        myFixture.configureByText(
            "FeignAnnotations.java",
            """
                @interface FeignClient {
                    String name() default "";
                    String path() default "";
                }
                @interface PostMapping {
                    String value() default "";
                }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "ylh.cloud.service.notice=ylh-cloud-service-notice"
        )
        myFixture.configureByText(
            "NoticeFeign.java",
            """
                @FeignClient(name = "${'$'}{ylh.cloud.service.notice}", path = "/api")
                interface NoticeFeign {
                    @PostMapping("/inner/notice/send") Object send(Object body);
                }
                class NoticeService {
                    NoticeFeign noticeFeign;
                    void send(Object body) {
                        noticeFeign.send(body);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("NoticeService").findMethodsByName("send", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = ExternalHttpExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("ylh-cloud-service-notice POST /api/inner/notice/send", resource.name)
        assertContains(resource.detail, "配置解析：\${ylh.cloud.service.notice} POST /api/inner/notice/send -> ylh-cloud-service-notice POST /api/inner/notice/send")
    }

    fun testExtractsCustomJshFeignClientEndpoint() {
        myFixture.configureByText(
            "FeignAnnotations.java",
            """
                @interface JshFeignClient {
                    String name() default "";
                    String url() default "";
                    String authAlias() default "";
                }
                @interface PostMapping {
                    String value() default "";
                }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "src/main/resources/application.properties",
            "jsh.feign.evaluation.url=https://evaluation-inner"
        )
        myFixture.configureByText(
            "EvaluationFeign.java",
            """
                @JshFeignClient(
                    name = "EvaluationFeign",
                    url = "${'$'}{jsh.feign.evaluation.url}",
                    authAlias = "mg")
                interface EvaluationFeign {
                    @PostMapping("/biz/api/inner/activity/create-prefill-evaluation-info")
                    String createPrefillEvaluationInfo(Object paramDto);
                }
                class EvaluationService {
                    EvaluationFeign evaluationFeign;
                    String run(Object paramDto) {
                        return evaluationFeign.createPrefillEvaluationInfo(paramDto);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("EvaluationService").findMethodsByName("run", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = ExternalHttpExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(
            "https://evaluation-inner POST /biz/api/inner/activity/create-prefill-evaluation-info",
            resource.name
        )
        assertContains(resource.detail, "JshFeignClient")
    }

    fun testExtractsHttpClientUrlFromLocalVariableInitializer() {
        myFixture.configureByText(
            "Value.java",
            """
                package org.springframework.beans.factory.annotation;
                public @interface Value { String value(); }
            """.trimIndent()
        )
        myFixture.configureByText(
            "SpringCloudInnerUtil.java",
            """
                package jsh.mgt.lib.http.inner;
                public class SpringCloudInnerUtil {
                    public static Object postForObject(String url, Object body, Object typeReference) { return null; }
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "StockClient.java",
            """
                package com.yilihuo.cloud.service.order.feign;
                import jsh.mgt.lib.http.inner.SpringCloudInnerUtil;
                import org.springframework.beans.factory.annotation.Value;

                class StockClient {
                    @Value("${'$'}{ylh.cloud.service.stock}") String prefix;
                    @Value("${'$'}{ylh.cloud.gateway}") String gateway;

                    Object confirmReceivingGoodsByOrderCode(Object paramsDto) {
                        String requestMapping = "/api/inner/stock/receiving-goods-management/"
                            + "confirm-receiving-goods-by-order-code";
                        String url = gateway + "/" + prefix + requestMapping;
                        return SpringCloudInnerUtil.postForObject(url, paramsDto, null);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("com.yilihuo.cloud.service.order.feign.StockClient")
            .findMethodsByName("confirmReceivingGoodsByOrderCode", false)
            .single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = ExternalHttpExtractor(listOf("jsh.mgt.lib.http.inner.SpringCloudInnerUtil"))
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(
            "POST \${ylh.cloud.gateway}/\${ylh.cloud.service.stock}/api/inner/stock/receiving-goods-management/confirm-receiving-goods-by-order-code",
            resource.name
        )
        assertEquals(Confidence.INFERRED, resource.confidence)
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

    fun testCrossProjectFeignReportsMissingMapping() {
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
            "CrossProjectFeign.java",
            """
                @FeignClient(value = "${'$'}{ylh.cloud.service.user}")
                interface UserFeign {
                    @GetMapping("/api/user/detail")
                    String detail();
                }
                class CrossProjectCaller {
                    UserFeign userFeign;
                    String run() { return userFeign.detail(); }
                }
            """.trimIndent()
        )

        val method = findClass("CrossProjectCaller").findMethodsByName("run", false).single()
        val result = CallGraphAnalyzer(
            project,
            AnalysisOptions(followCrossProjectFeign = true),
            listOf(ExternalHttpExtractor())
        ).analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertTrue(result.resources.any { it.type == ResourceType.EXTERNAL_HTTP })
        assertTrue(result.warnings.any { it.message.contains("未配置服务映射") })
    }

    fun testCrossProjectFeignReportsUnopenedMappedProject() {
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
            "MappedFeign.java",
            """
                @FeignClient(value = "${'$'}{ylh.cloud.service.stock}")
                interface StockFeign {
                    @GetMapping("/api/stock/detail")
                    String detail();
                }
                class CrossProjectMappedCaller {
                    StockFeign stockFeign;
                    String run() { return stockFeign.detail(); }
                }
            """.trimIndent()
        )

        val method = findClass("CrossProjectMappedCaller").findMethodsByName("run", false).single()
        val result = CallGraphAnalyzer(
            project,
            AnalysisOptions(
                followCrossProjectFeign = true,
                crossProjectFeignMappings = mapOf("${'$'}{ylh.cloud.service.stock}" to "missing-project")
            ),
            listOf(ExternalHttpExtractor())
        ).analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertTrue(result.warnings.any { it.message.contains("目标工程未打开") })
    }

    fun testBatchHttpAnalyzerDoesNotFollowCrossProjectFeign() {
        myFixture.configureByText(
            "FeignAnnotations.java",
            """
                @interface FeignClient {
                    String value() default "";
                }
                @interface GetMapping {
                    String[] value() default {};
                    String[] path() default {};
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "BatchController.java",
            """
                @FeignClient(value = "${'$'}{ylh.cloud.service.user}")
                interface BatchUserFeign {
                    @GetMapping("/api/user/detail")
                    String detail();
                }
                class BatchController {
                    BatchUserFeign userFeign;
                    @GetMapping("/api/batch/source")
                    String run() { return userFeign.detail(); }
                }
            """.trimIndent()
        )

        val row = BatchHttpAnalyzer(project, AnalysisOptions(followCrossProjectFeign = true))
            .analyzeRow(1, "/api/batch/source")

        assertEquals(BatchRowStatus.SUCCESS, row.status)
        assertTrue(row.result!!.warnings.none { it.message.contains("未配置服务映射") })
        assertTrue(row.result.resources.any { it.type == ResourceType.EXTERNAL_HTTP })
    }

    fun testCrossProjectFeignMissingMappingWarningIsCachedPerEndpoint() {
        myFixture.configureByText(
            "FeignAnnotations.java",
            """
                @interface FeignClient {
                    String value() default "";
                }
                @interface GetMapping {
                    String[] value() default {};
                    String[] path() default {};
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "RepeatedFeignCaller.java",
            """
                @FeignClient(value = "${'$'}{ylh.cloud.service.user}")
                interface RepeatedUserFeign {
                    @GetMapping("/api/user/detail")
                    String detail();
                }
                class RepeatedFeignCaller {
                    RepeatedUserFeign userFeign;
                    String run() {
                        userFeign.detail();
                        return userFeign.detail();
                    }
                }
            """.trimIndent()
        )

        val method = findClass("RepeatedFeignCaller").findMethodsByName("run", false).single()
        var resolveCount = 0
        val result = CallGraphAnalyzer(
            project,
            AnalysisOptions(followCrossProjectFeign = true),
            listOf(ExternalHttpExtractor())
        ) { status ->
            if (status.contains("定位目标工程")) resolveCount++
        }.analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertEquals(1, resolveCount)
        assertEquals(1, result.warnings.count { it.message.contains("未配置服务映射") })
    }

    fun testReadOnlyExternalHttpStillFollowsCrossProjectFeign() {
        myFixture.configureByText(
            "FeignAnnotations.java",
            """
                @interface FeignClient {
                    String value() default "";
                }
                @interface PostMapping {
                    String[] value() default {};
                    String[] path() default {};
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "ExcludedSearchFeignCaller.java",
            """
                @FeignClient(value = "${'$'}{ylh.cloud.service.user}/api")
                interface ExcludedUserFeign {
                    @PostMapping("/inner/memberCompany/memberCustomerSupplier/search-customerSupplier-by-pro")
                    String search();
                }
                class ExcludedSearchFeignCaller {
                    ExcludedUserFeign userFeign;
                    String run() { return userFeign.search(); }
                }
            """.trimIndent()
        )

        val method = findClass("ExcludedSearchFeignCaller").findMethodsByName("run", false).single()
        var resolveCount = 0
        val result = CallGraphAnalyzer(
            project,
            AnalysisOptions(
                followCrossProjectFeign = true,
                excludedExternalHttpPathPatterns = listOf("/search-")
            ),
            listOf(ExternalHttpExtractor())
        ) { status ->
            if (status.contains("定位目标工程")) resolveCount++
        }.analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertEquals(1, resolveCount)
        val external = result.resources.single { it.type == ResourceType.EXTERNAL_HTTP }
        assertEquals(Operation.READ, external.operation)
        assertTrue(result.warnings.any { it.message.contains("未配置服务映射") })
    }

    fun testClassifiesExternalHttpByConfiguredPathPatterns() {
        myFixture.configureByText(
            "ExternalFilterService.java",
            """
                class ExternalFilterService {
                    void run() {
                        getTaxRate();
                        listCustomers();
                        searchOrders();
                        createOrder();
                    }
                    void getTaxRate() {}
                    void listCustomers() {}
                    void searchOrders() {}
                    void createOrder() {}
                }
            """.trimIndent()
        )
        val extractor = object : ResourceExtractor {
            override fun extract(context: CallContext): List<ResourceRef> {
                val target = context.resolvedMethod ?: return emptyList()
                val path = when (target.name) {
                    "getTaxRate" -> "/api/member/get-tax-rate-by-member-id"
                    "listCustomers" -> "/api/member/list-customer"
                    "searchOrders" -> "/api/order/search-order"
                    "createOrder" -> "/api/order/create-order"
                    else -> return emptyList()
                }
                return listOf(ResourceRef(ResourceType.EXTERNAL_HTTP, "GET $path", Operation.CALL, Confidence.CONFIRMED, "http", null))
            }
        }
        val method = findClass("ExternalFilterService").findMethodsByName("run", false).single()

        val result = CallGraphAnalyzer(
            project,
            AnalysisOptions(excludedExternalHttpPathPatterns = listOf("/get-", "/list-", "regex:/search-")),
            listOf(extractor)
        ).analyze(EntryPoint(EntryType.METHOD, "test"), method)

        assertEquals(
            listOf(
                "GET /api/member/get-tax-rate-by-member-id" to Operation.READ,
                "GET /api/member/list-customer" to Operation.READ,
                "GET /api/order/search-order" to Operation.READ,
                "GET /api/order/create-order" to Operation.CALL
            ),
            result.resources.map { it.name to it.operation }
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

    fun testExtractsJshRocketMqProducerTopicAndTagFromValueFields() {
        myFixture.configureByText(
            "JshRocketMqProducer.java",
            """
                package jsh.mgt.lib.rocketmq.producer;
                public class JshRocketMqProducer {
                    public void sendDelayMsg(String topic, String tag, String key, byte[] body, long time) {}
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "ReturnServiceApplyAutoAuditSender.java",
            """
                import jsh.mgt.lib.rocketmq.producer.JshRocketMqProducer;
                @interface Value { String value(); }
                class ReturnServiceApplyAutoAuditSender {
                    @Value("${'$'}{rocketmq.producer.topic.returnServiceAutoAudit}") String topic;
                    @Value("${'$'}{rocketmq.producer.tag.returnServiceAutoAudit}") String tag;
                    JshRocketMqProducer jshRocketMqProducer;
                    void sendMq(byte[] body) {
                        jshRocketMqProducer.sendDelayMsg(topic, tag, "key", body, System.currentTimeMillis());
                    }
                }
            """.trimIndent()
        )

        val method = findClass("ReturnServiceApplyAutoAuditSender").findMethodsByName("sendMq", false).single()
        val call = findMethodCalls(method).single { it.methodExpression.referenceName == "sendDelayMsg" }
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals(ResourceType.ROCKETMQ, resource.type)
        assertEquals(
            "${'$'}{rocketmq.producer.topic.returnServiceAutoAudit}:${'$'}{rocketmq.producer.tag.returnServiceAutoAudit}",
            resource.name
        )
        assertEquals(Operation.PRODUCE, resource.operation)
        assertEquals(Confidence.CONFIRMED, resource.confidence)
        assertContains(resource.detail, "RocketMQ 包装器参数解析")
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

    fun testExtractsAndLocatesJshRocketMqListenerByConfigKey() {
        myFixture.configureByText(
            "JshRocketMqListener.java",
            """
                package jsh.mgt.lib.rocketmq.consumer;
                public interface JshRocketMqListener {
                    String topic();
                    String tag();
                    void consume(byte[] bytes);
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "RocketMqProperties.java",
            """
                package jsh.mgt.lib.rocketmq.properties;
                public class RocketMqProperties {
                    public java.util.Map<String, Config> getConsumer() { return null; }
                    public static class Config {
                        public String getTopic() { return null; }
                        public String getTag() { return null; }
                    }
                }
            """.trimIndent()
        )
        myFixture.configureByText(
            "ReturnServiceApplyAutoAuditConsume.java",
            """
                import jsh.mgt.lib.rocketmq.consumer.JshRocketMqListener;
                import jsh.mgt.lib.rocketmq.properties.RocketMqProperties;
                class ReturnServiceApplyAutoAuditConsume implements JshRocketMqListener {
                    RocketMqProperties rocketMqProperties;
                    public String topic() {
                        return rocketMqProperties.getConsumer().get("returnServiceAutoAudit").getTopic();
                    }
                    public String tag() {
                        return rocketMqProperties.getConsumer().get("returnServiceAutoAudit").getTag();
                    }
                    public void consume(byte[] bytes) {}
                }
            """.trimIndent()
        )

        val method = findClass("ReturnServiceApplyAutoAuditConsume").findMethodsByName("consume", false).single()
        val resource = InfrastructureExtractor()
            .extract(CallContext(method, null, method, method.text)).single()

        assertEquals(ResourceType.ROCKETMQ, resource.type)
        assertEquals("returnServiceAutoAudit", resource.name)
        assertEquals(Operation.CONSUME, resource.operation)
        assertEquals(Confidence.INFERRED, resource.confidence)
        assertEquals(
            1,
            EntryPointLocator(project).byMqTopic(
                "${'$'}{rocketmq.producer.topic.returnServiceAutoAudit}:${'$'}{rocketmq.producer.tag.returnServiceAutoAudit}"
            ).size
        )
    }

    fun testKafkaPlaceholderTopicDoesNotMatchEveryPropertyEndingWithTopic() {
        myFixture.configureByText(
            "KafkaAnnotations.java",
            """
                @interface KafkaListener {
                    String[] topics() default {};
                    String[] topic() default {};
                    String[] value() default {};
                }
                class KafkaConsumers {
                    @KafkaListener(topics = "${'$'}{spring.kafka.insertTradeOrderPostProcess.producer.topic}")
                    void expected(String body) {}
                    @KafkaListener(topics = "${'$'}{spring.kafka.sg-order-retail.producer.topic}")
                    void unrelated(String body) {}
                }
            """.trimIndent()
        )

        val matches = EntryPointLocator(project).byMqTopic(
            "${'$'}{spring.kafka.insertTradeOrderPostProcess.producer.topic}"
        )

        assertEquals(1, matches.size)
        assertEquals("expected", matches.single().method.name)
    }

    fun testExtractsConfiguredRocketMqProducerAndConsumerNames() {
        myFixture.configureByText(
            "CustomRocket.java",
            """
                package com.company.mq;
                public class CompanyRocketProducer {
                    public void sendDelayMsg(String topic, String tag, String key, byte[] body, long time) {}
                }
                interface CompanyRocketListener {
                    String topic();
                    String tag();
                    void consume(byte[] bytes);
                }
                class CompanySender {
                    CompanyRocketProducer producer;
                    void send(byte[] body) {
                        producer.sendDelayMsg("company.topic", "created", "key", body, 1L);
                    }
                }
                class CompanyConsumer implements CompanyRocketListener {
                    public String topic() { return "company.topic"; }
                    public String tag() { return "created"; }
                    public void consume(byte[] bytes) {}
                }
            """.trimIndent()
        )
        val extractor = InfrastructureExtractor(
            customMqProducerClasses = listOf("CompanyRocketProducer"),
            customMqConsumerInterfaces = listOf("CompanyRocketListener")
        )

        val send = findClass("com.company.mq.CompanySender").findMethodsByName("send", false).single()
        val sendCall = findMethodCalls(send).single { it.methodExpression.referenceName == "sendDelayMsg" }
        val produced = extractor.extract(CallContext(send, sendCall, sendCall.resolveMethod(), sendCall.text)).single()
        val consume = findClass("com.company.mq.CompanyConsumer").findMethodsByName("consume", false).single()
        val consumed = extractor.extract(CallContext(consume, null, consume, consume.text)).single()

        assertEquals("company.topic:created", produced.name)
        assertEquals(Operation.PRODUCE, produced.operation)
        assertEquals("company.topic:created", consumed.name)
        assertEquals(Operation.CONSUME, consumed.operation)
        assertTrue(
            EntryPointLocator(
                project,
                customMqProducerClasses = listOf("CompanyRocketProducer"),
                customMqConsumerInterfaces = listOf("CompanyRocketListener")
            ).byMqTopic("company.topic:created").isNotEmpty()
        )
    }

    fun testBatchHttpAnalyzerHandlesStrictHttpLocationOutcomes() {
        myFixture.configureByText(
            "Mappings.java",
            """
                @interface RequestMapping { String[] value() default {}; String[] path() default {}; }
                @interface GetMapping { String[] value() default {}; String[] path() default {}; }
                @RequestMapping("/api")
                class OrderController {
                    @GetMapping("/order/save")
                    void save() {}
                }
                class FirstDuplicateController {
                    @GetMapping("/dup")
                    void first() {}
                }
                class SecondDuplicateController {
                    @GetMapping("/dup")
                    void second() {}
                }
            """.trimIndent()
        )

        val analyzer = BatchHttpAnalyzer(project, AnalysisOptions())
        val success = analyzer.analyzeRow(1, "/api/order/save")
        val missing = analyzer.analyzeRow(2, "/missing")
        val duplicate = analyzer.analyzeRow(3, "/dup")
        val nonHttp = analyzer.analyzeRow(4, "order.topic")

        assertEquals(BatchRowStatus.SUCCESS, success.status)
        assertEquals("OrderController.save", success.analyzedMethod?.displayName)
        assertEquals(BatchRowStatus.SKIPPED, missing.status)
        assertEquals("未定位到接口", missing.reason)
        assertEquals(BatchRowStatus.SKIPPED, duplicate.status)
        assertEquals("定位到多个接口", duplicate.reason)
        assertEquals(BatchRowStatus.SKIPPED, nonHttp.status)
        assertEquals("不是 HTTP 路径", nonHttp.reason)
    }

    fun testBatchHttpAnalyzerUsesSingleInterfaceImplementationAsRoot() {
        myFixture.configureByText(
            "InterfaceMapping.java",
            """
                @interface GetMapping { String[] value() default {}; String[] path() default {}; }
                interface OrderApi {
                    @GetMapping("/interface/order")
                    void submit();
                }
                class OrderApiImpl implements OrderApi {
                    @Override public void submit() { save(); }
                    void save() {}
                }
            """.trimIndent()
        )

        val row = BatchHttpAnalyzer(project, AnalysisOptions()).analyzeRow(1, "/interface/order")

        assertEquals(BatchRowStatus.SUCCESS, row.status)
        assertEquals("OrderApiImpl.submit", row.analyzedMethod?.displayName)
        assertTrue(row.result!!.callGraph.methods.values.any { it.displayName == "OrderApiImpl.save" })
    }

    fun testBatchHttpAnalyzerSkipsInterfaceMappingWithMultipleImplementations() {
        myFixture.configureByText(
            "MultiInterfaceMapping.java",
            """
                @interface GetMapping { String[] value() default {}; String[] path() default {}; }
                interface MultiOrderApi {
                    @GetMapping("/interface/multi-order")
                    void submit();
                }
                class FirstOrderApiImpl implements MultiOrderApi {
                    @Override public void submit() {}
                }
                class SecondOrderApiImpl implements MultiOrderApi {
                    @Override public void submit() {}
                }
            """.trimIndent()
        )

        val row = BatchHttpAnalyzer(project, AnalysisOptions()).analyzeRow(1, "/interface/multi-order")

        assertEquals(BatchRowStatus.SKIPPED, row.status)
        assertEquals("interface 方法存在多个实现", row.reason)
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

    fun testExtractsJpaEntityNameAsTableName() {
        myFixture.configureByText(
            "JpaEntityNameRepository.java",
            """
                @interface Entity { String name() default ""; }
                @Entity(name = "defective_product_order") class DefectiveProductOrder {}
                interface JpaRepository<T, ID> { T saveAndFlush(T entity); }
                interface DefectiveProductOrderRepository extends JpaRepository<DefectiveProductOrder, Long> {}
                class DefectiveProductPrepareService {
                    DefectiveProductOrderRepository repository;
                    DefectiveProductOrder save(DefectiveProductOrder order) {
                        return repository.saveAndFlush(order);
                    }
                }
            """.trimIndent()
        )

        val method = findClass("DefectiveProductPrepareService").findMethodsByName("save", false).single()
        val call = PsiTreeUtil.findChildOfType(method.body, PsiMethodCallExpression::class.java)!!
        val resource = JpaExtractor()
            .extract(CallContext(method, call, call.resolveMethod(), call.text)).single()

        assertEquals("defective_product_order", resource.name)
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
