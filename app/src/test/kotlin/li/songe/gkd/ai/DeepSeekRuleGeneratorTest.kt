package li.songe.gkd.ai

import li.songe.gkd.data.ComplexSnapshot
import li.songe.gkd.data.DeviceInfo
import li.songe.gkd.data.RpcError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun userDescriptionIsBoundedAndClearlySeparatedFromSnapshot() {
        val description = "右上角的圆形叉号是广告关闭按钮" + "x".repeat(600)

        val prompt = DeepSeekRuleGenerator.buildUserPrompt(testSnapshot(), description)
        val normalized = DeepSeekRuleGenerator.normalizeUserDescription(description)

        assertEquals(DeepSeekRuleGenerator.MAX_USER_DESCRIPTION_LENGTH, normalized.length)
        assertTrue(prompt.contains("<user_description>\n$normalized\n</user_description>"))
        assertTrue(prompt.contains("\"appId\":\"com.example.ad\""))
    }

    @Test
    fun blankUserDescriptionIsRejected() {
        assertThrows(RpcError::class.java) {
            DeepSeekRuleGenerator.normalizeUserDescription("  \n  ")
        }
    }

    @Test
    fun floatingButtonPromptDoesNotInventAUserDescription() {
        val prompt = DeepSeekRuleGenerator.buildUserPrompt(testSnapshot(), null)

        assertTrue(!prompt.contains("<user_description>"))
        assertTrue(prompt.contains("\"appId\":\"com.example.ad\""))
    }

    private fun testSnapshot() = ComplexSnapshot(
        id = 123L,
        appId = "com.example.ad",
        activityId = "com.example.ad.AdActivity",
        screenHeight = 2400,
        screenWidth = 1080,
        isLandscape = false,
        appInfo = null,
        gkdAppInfo = null,
        device = DeviceInfo(
            device = "test",
            model = "test",
            manufacturer = "test",
            brand = "test",
            sdkInt = 37,
            release = "test",
        ),
        nodes = emptyList(),
    )
}
