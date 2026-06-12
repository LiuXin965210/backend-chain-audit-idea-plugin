package com.liuxin.backendchain.analysis

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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

    private fun findClass(name: String) = JavaPsiFacade.getInstance(project)
        .findClass(name, GlobalSearchScope.projectScope(project))
        ?: error("Class not found: $name")
}
