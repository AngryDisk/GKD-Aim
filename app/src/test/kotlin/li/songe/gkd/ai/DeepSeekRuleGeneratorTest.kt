package li.songe.gkd.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekRuleGeneratorTest {
    @Test
    fun parsesJsonModeResponseWithCodeFenceFallback() {
        val decision = DeepSeekRuleGenerator.parseDecision(
            """
            ```json
            {"targetNodeId":7,"ruleName":"开屏广告","reason":"跳过按钮","confidence":0.95}
            ```
            """.trimIndent()
        )

        assertEquals(7, decision.targetNodeId)
        assertEquals("开屏广告", decision.ruleName)
        assertEquals(0.95, decision.confidence!!, 0.0)
    }
}
