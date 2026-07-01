package com.liuxin.backendchain.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ChainAuditSettingsTest {
    @Test
    fun `batch row timeout defaults to unlimited`() {
        val options = ChainAuditSettings.options(ChainAuditSettings.State())

        assertEquals(0, options.batchRowTimeoutSeconds)
    }

    @Test
    fun `batch row timeout is configurable and normalized`() {
        val configured = ChainAuditSettings.options(
            ChainAuditSettings.State(batchRowTimeoutSeconds = 300)
        )
        val negative = ChainAuditSettings.options(
            ChainAuditSettings.State(batchRowTimeoutSeconds = -1)
        )
        val tooLarge = ChainAuditSettings.options(
            ChainAuditSettings.State(batchRowTimeoutSeconds = 100_000)
        )

        assertEquals(300, configured.batchRowTimeoutSeconds)
        assertEquals(0, negative.batchRowTimeoutSeconds)
        assertEquals(86_400, tooLarge.batchRowTimeoutSeconds)
    }
}
