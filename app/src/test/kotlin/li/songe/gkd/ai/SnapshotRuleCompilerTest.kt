package li.songe.gkd.ai

import li.songe.gkd.data.AttrInfo
import li.songe.gkd.data.ComplexSnapshot
import li.songe.gkd.data.DeviceInfo
import li.songe.gkd.data.NodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRuleCompilerTest {
    @Test
    fun countdownSkipTextProducesStableContainsSelector() {
        val snapshot = snapshot(
            node(id = 0, pid = -1, name = "android.widget.FrameLayout", childCount = 2),
            node(id = 1, pid = 0, text = "跳过 5秒", clickable = true, index = 0),
            node(id = 2, pid = 0, text = "了解更多", clickable = true, index = 1),
        )

        val selector = SnapshotRuleCompiler.compileSelector(snapshot, 1)

        assertTrue(selector.contains("text*=\"跳过\""))
        assertEquals(listOf(1), SnapshotRuleCompiler.matchingNodeIds(snapshot, selector))
    }

    @Test
    fun duplicateTextFallsBackToUniqueViewId() {
        val snapshot = snapshot(
            node(id = 0, pid = -1, name = "android.widget.FrameLayout", childCount = 2),
            node(
                id = 1,
                pid = 0,
                vid = "ad_skip",
                fullId = "com.example:id/ad_skip",
                text = "关闭",
                clickable = true,
                index = 0,
            ),
            node(id = 2, pid = 0, text = "关闭", clickable = true, index = 1),
        )

        val selector = SnapshotRuleCompiler.compileSelector(snapshot, 1)

        assertTrue(selector.contains("vid=\"ad_skip\""))
        assertEquals(listOf(1), SnapshotRuleCompiler.matchingNodeIds(snapshot, selector))
    }

    @Test
    fun uniqueParentAnchorCanLocateAnonymousCloseNode() {
        val snapshot = snapshot(
            node(id = 0, pid = -1, name = "android.widget.FrameLayout", childCount = 1),
            node(
                id = 1,
                pid = 0,
                vid = "ad_container",
                fullId = "com.example:id/ad_container",
                name = "android.widget.FrameLayout",
                childCount = 1,
            ),
            node(
                id = 2,
                pid = 1,
                name = "android.widget.ImageView",
                clickable = true,
                index = 0,
                left = 900,
                top = 100,
                right = 980,
                bottom = 180,
            ),
        )

        val selector = SnapshotRuleCompiler.compileSelector(snapshot, 2)

        assertTrue(selector.contains("ad_container"))
        assertEquals(listOf(2), SnapshotRuleCompiler.matchingNodeIds(snapshot, selector))
    }

    @Test
    fun ambiguousAnonymousNodeIsRejected() {
        val snapshot = snapshot(
            node(id = 0, pid = -1, name = "android.widget.FrameLayout", childCount = 2),
            node(id = 1, pid = 0, name = "android.view.View", index = 0),
            node(id = 2, pid = 0, name = "android.view.View", index = 1),
        )

        assertThrows(IllegalStateException::class.java) {
            SnapshotRuleCompiler.compileSelector(snapshot, 1)
        }
    }

    @Test
    fun anonymousTopRightImageCanUseConservativePositionFallback() {
        val snapshot = snapshot(
            node(id = 0, pid = -1, name = "android.widget.FrameLayout", childCount = 2),
            node(
                id = 1,
                pid = 0,
                name = "android.widget.ImageView",
                clickable = true,
                index = 0,
                left = 920,
                top = 120,
                right = 1000,
                bottom = 200,
            ),
            node(
                id = 2,
                pid = 0,
                name = "android.widget.ImageView",
                clickable = true,
                index = 1,
                left = 300,
                top = 900,
                right = 700,
                bottom = 1300,
            ),
        )

        val selector = SnapshotRuleCompiler.compileSelector(snapshot, 1)

        assertTrue(selector.contains("right>"))
        assertEquals(listOf(1), SnapshotRuleCompiler.matchingNodeIds(snapshot, selector))
    }

    private fun snapshot(vararg nodes: NodeInfo) = ComplexSnapshot(
        id = 123L,
        appId = "com.example",
        activityId = "com.example.AdActivity",
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
        nodes = nodes.toList(),
    )

    private fun node(
        id: Int,
        pid: Int,
        fullId: String? = null,
        vid: String? = null,
        name: String = "android.widget.TextView",
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        childCount: Int = 0,
        index: Int = 0,
        left: Int = 0,
        top: Int = 0,
        right: Int = 200,
        bottom: Int = 100,
    ) = NodeInfo(
        id = id,
        pid = pid,
        idQf = null,
        textQf = null,
        attr = AttrInfo(
            id = fullId,
            vid = vid,
            name = name,
            text = text,
            desc = desc,
            clickable = clickable,
            focusable = false,
            checkable = false,
            checked = null,
            editable = false,
            longClickable = false,
            visibleToUser = true,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            width = right - left,
            height = bottom - top,
            childCount = childCount,
            index = index,
            depth = if (pid < 0) 0 else 1,
        )
    )
}
