package li.songe.gkd.ai

import kotlinx.serialization.Serializable
import li.songe.gkd.data.ComplexSnapshot
import li.songe.gkd.data.NodeInfo
import li.songe.gkd.util.json
import li.songe.selector.MatchOption
import li.songe.selector.QueryContext
import li.songe.selector.Selector
import li.songe.selector.Transform
import li.songe.selector.getBooleanInvoke
import li.songe.selector.getCharSequenceAttr
import li.songe.selector.getCharSequenceInvoke
import li.songe.selector.getIntInvoke

/**
 * Converts a model-selected snapshot node into a deterministic GKD selector.
 *
 * The model never gets to provide executable selector text. Every selector produced here is
 * parsed by GKD and must uniquely match the selected node in the captured snapshot.
 */
internal object SnapshotRuleCompiler {
    private const val MAX_PROMPT_NODES = 450
    private const val MAX_PROMPT_TEXT_LENGTH = 96

    private val dismissKeywords = listOf(
        "跳过",
        "关闭广告",
        "关闭",
        "skip",
        "close ad",
        "close",
    )

    @Serializable
    private data class PromptSnapshot(
        val appId: String,
        val activityId: String?,
        val screenWidth: Int,
        val screenHeight: Int,
        val nodes: List<PromptNode>,
    )

    @Serializable
    private data class PromptNode(
        val nodeId: Int,
        val parentNodeId: Int,
        val name: String?,
        val vid: String?,
        val text: String?,
        val desc: String?,
        val clickable: Boolean,
        val bounds: List<Int>,
        val childCount: Int,
    )

    private class SnapshotNode(val info: NodeInfo) {
        var parent: SnapshotNode? = null
        val children = mutableListOf<SnapshotNode>()
    }

    fun buildPromptPayload(snapshot: ComplexSnapshot): String {
        val selectedNodes = promptNodes(snapshot).asSequence()
            .map { node ->
                val attr = node.attr
                PromptNode(
                    nodeId = node.id,
                    parentNodeId = node.pid,
                    name = attr.name?.substringAfterLast('.')?.promptText(),
                    vid = attr.vid?.promptText(),
                    text = attr.text?.promptText(),
                    desc = attr.desc?.promptText(),
                    clickable = attr.clickable,
                    bounds = listOf(attr.left, attr.top, attr.right, attr.bottom),
                    childCount = attr.childCount,
                )
            }.toList()
        return json.encodeToString(
            PromptSnapshot(
                appId = snapshot.appId,
                activityId = snapshot.activityId,
                screenWidth = snapshot.screenWidth,
                screenHeight = snapshot.screenHeight,
                nodes = selectedNodes,
            )
        )
    }

    fun compileSelector(snapshot: ComplexSnapshot, targetNodeId: Int): String {
        if (promptNodes(snapshot).none { it.id == targetNodeId }) {
            error("AI 返回的节点不在候选列表中")
        }
        val target = snapshot.nodes.find { it.id == targetNodeId }
            ?: error("AI 返回了不存在的节点")
        if (!target.attr.visibleToUser) {
            error("AI 选择的节点当前不可见")
        }

        val graph = buildGraph(snapshot.nodes)
        val targetGraphNode = graph.find { it.info.id == targetNodeId }
            ?: error("快照节点结构不完整")
        val candidates = buildList {
            val text = target.attr.text.normalizedAttr()
            val desc = target.attr.desc.normalizedAttr()
            val vid = target.attr.vid.normalizedAttr()
            val id = target.attr.id.normalizedAttr()
            val name = target.attr.name.normalizedAttr()

            dynamicKeywordSelector("text", text)?.let(::add)
            dynamicKeywordSelector("desc", desc)?.let(::add)
            vid?.let { add(attrSelector("vid", it)) }
            id?.let { add(attrSelector("id", it)) }
            desc?.let { add(attrSelector("desc", it)) }
            text?.let { add(attrSelector("text", it)) }
            if (name != null && text != null) {
                add("${attrSelector("name", name, visible = false)}${attrSelector("text", text)}")
            }
            if (name != null && desc != null) {
                add("${attrSelector("name", name, visible = false)}${attrSelector("desc", desc)}")
            }
        }.distinct()

        candidates.firstOrNull { uniquelyMatches(graph, it, targetNodeId) }?.let { return it }

        buildAncestorSelectors(graph, targetGraphNode).firstOrNull {
            uniquelyMatches(graph, it, targetNodeId)
        }?.let { return it }

        buildPositionSelector(snapshot, target)?.takeIf {
            uniquelyMatches(graph, it, targetNodeId)
        }?.let { return it }

        error("无法为 AI 选择的节点生成唯一且可靠的规则")
    }

    internal fun matchingNodeIds(snapshot: ComplexSnapshot, selector: String): List<Int> {
        return matchingNodes(buildGraph(snapshot.nodes), selector).map { it.info.id }
    }

    private fun promptScore(node: NodeInfo, snapshot: ComplexSnapshot): Int {
        val attr = node.attr
        val searchableText = listOfNotNull(attr.text, attr.desc, attr.vid, attr.id)
            .joinToString(" ")
            .lowercase()
        var score = 0
        if (searchableText.isNotBlank()) score += 20
        if (dismissKeywords.any { searchableText.contains(it.lowercase()) }) score += 300
        if (attr.clickable) score += 50
        if (attr.childCount == 0) score += 10
        if (attr.name?.endsWith("ImageView") == true) score += 15

        val centerX = (attr.left + attr.right) / 2
        val centerY = (attr.top + attr.bottom) / 2
        val smallNode = attr.width in 1..(snapshot.screenWidth / 3).coerceAtLeast(1) &&
                attr.height in 1..(snapshot.screenHeight / 3).coerceAtLeast(1)
        if (smallNode && centerX > snapshot.screenWidth * 2 / 3 && centerY < snapshot.screenHeight / 2) {
            score += 80
        }
        return score
    }

    private fun promptNodes(snapshot: ComplexSnapshot): List<NodeInfo> =
        snapshot.nodes.asSequence()
            .filter { it.attr.visibleToUser }
            .sortedByDescending { promptScore(it, snapshot) }
            .take(MAX_PROMPT_NODES)
            .sortedBy { it.id }
            .toList()

    private fun String.promptText(): String =
        replace(Regex("[\\r\\n\\t]+"), " ").take(MAX_PROMPT_TEXT_LENGTH)

    private fun String?.normalizedAttr(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }

    private fun dynamicKeywordSelector(attribute: String, value: String?): String? {
        if (value == null) return null
        val keyword = dismissKeywords.firstNotNullOfOrNull { candidate ->
            val index = value.indexOf(candidate, ignoreCase = true)
            if (index >= 0) value.substring(index, index + candidate.length) else null
        } ?: return null
        return "[$attribute*=\"${escapeSelectorText(keyword)}\"]" +
                "[$attribute.length<24][visibleToUser=true]"
    }

    private fun attrSelector(name: String, value: String, visible: Boolean = true): String {
        return "[$name=\"${escapeSelectorText(value)}\"]" +
                if (visible) "[visibleToUser=true]" else ""
    }

    private fun escapeSelectorText(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> char
                }
            )
        }
    }

    private fun buildAncestorSelectors(
        graph: List<SnapshotNode>,
        target: SnapshotNode,
    ): List<String> {
        val selectors = mutableListOf<String>()
        val targetName = target.info.attr.name.normalizedAttr()
        var ancestor = target.parent
        var distance = 1
        while (ancestor != null && distance <= 3) {
            val anchorCandidates = buildList {
                ancestor.info.attr.vid.normalizedAttr()?.let { add(attrSelector("vid", it)) }
                ancestor.info.attr.id.normalizedAttr()?.let { add(attrSelector("id", it)) }
                ancestor.info.attr.desc.normalizedAttr()?.let { add(attrSelector("desc", it)) }
                ancestor.info.attr.text.normalizedAttr()?.let { add(attrSelector("text", it)) }
            }
            val anchor = anchorCandidates.firstOrNull {
                uniquelyMatches(graph, it, ancestor.info.id)
            }
            if (anchor != null) {
                val relation = if (distance == 1) ">" else ">$distance"
                val targetPart = buildString {
                    if (targetName != null) append(attrSelector("name", targetName, visible = false))
                    append("[index=${target.info.attr.index}][visibleToUser=true]")
                }
                selectors.add("$anchor $relation $targetPart")
            }
            ancestor = ancestor.parent
            distance++
        }
        return selectors
    }

    private fun buildPositionSelector(snapshot: ComplexSnapshot, target: NodeInfo): String? {
        val attr = target.attr
        val name = attr.name.normalizedAttr() ?: return null
        val width = snapshot.screenWidth
        val height = snapshot.screenHeight
        if (width <= 0 || height <= 0 || attr.width <= 0 || attr.height <= 0) return null

        val centerX = (attr.left + attr.right) / 2
        val centerY = (attr.top + attr.bottom) / 2
        val isSmall = attr.width < width / 3 && attr.height < height / 3
        if (!isSmall || centerX <= width * 2 / 3 || centerY >= height / 2) return null

        return buildString {
            append(attrSelector("name", name, visible = false))
            append("[visibleToUser=true]")
            append("[clickable=${attr.clickable}]")
            append("[right>${width * 2 / 3}]")
            append("[bottom<${height / 2}]")
            append("[width<${width / 3}]")
            append("[height<${height / 3}]")
        }
    }

    private fun uniquelyMatches(
        graph: List<SnapshotNode>,
        selector: String,
        targetNodeId: Int,
    ): Boolean {
        val matches = runCatching { matchingNodes(graph, selector) }.getOrDefault(emptyList())
        return matches.size == 1 && matches.single().info.id == targetNodeId
    }

    private fun matchingNodes(
        graph: List<SnapshotNode>,
        selectorSource: String,
    ): List<SnapshotNode> {
        val selector = Selector.parse(selectorSource)
        return graph.asSequence()
            .filter { it.parent == null }
            .flatMap { root ->
                sequence {
                    selector.match(root, snapshotTransform, MatchOption.default)?.let { yield(it) }
                    yieldAll(snapshotTransform.querySelectorAll(root, selector, MatchOption.default))
                }
            }
            .distinctBy { it.info.id }
            .toList()
    }

    private fun buildGraph(nodes: List<NodeInfo>): List<SnapshotNode> {
        val graph = nodes.map(::SnapshotNode)
        val idMap = graph.associateBy { it.info.id }
        graph.forEach { node ->
            node.parent = idMap[node.info.pid]
            node.parent?.children?.add(node)
        }
        return graph
    }

    private fun getNodeAttr(node: SnapshotNode, name: String): Any? {
        val attr = node.info.attr
        return when (name) {
            "_id" -> node.info.id
            "_pid" -> node.info.pid
            "id" -> attr.id
            "vid" -> attr.vid
            "name" -> attr.name
            "text" -> attr.text
            "desc" -> attr.desc
            "clickable" -> attr.clickable
            "focusable" -> attr.focusable
            "checkable" -> attr.checkable
            "checked" -> attr.checked
            "editable" -> attr.editable
            "longClickable" -> attr.longClickable
            "visibleToUser" -> attr.visibleToUser
            "left" -> attr.left
            "top" -> attr.top
            "right" -> attr.right
            "bottom" -> attr.bottom
            "width" -> attr.width
            "height" -> attr.height
            "childCount" -> attr.childCount
            "index" -> attr.index
            "depth" -> attr.depth
            "parent" -> node.parent
            else -> null
        }
    }

    private fun getNodeInvoke(target: SnapshotNode, name: String, args: List<Any>): Any? {
        return when (name) {
            "getChild" -> target.children.getOrNull(args.firstOrNull() as? Int ?: return null)
            else -> null
        }
    }

    private val snapshotTransform = Transform<SnapshotNode>(
        getAttr = { target, name ->
            when (target) {
                is QueryContext<*> -> when (name) {
                    "prev" -> target.prev
                    "current" -> target.current
                    else -> getNodeAttr(target.current as SnapshotNode, name)
                }

                is SnapshotNode -> getNodeAttr(target, name)
                is CharSequence -> getCharSequenceAttr(target, name)
                else -> null
            }
        },
        getInvoke = { target, name, args ->
            when (target) {
                is SnapshotNode -> getNodeInvoke(target, name, args)
                is QueryContext<*> -> when (name) {
                    "getPrev" -> (args.firstOrNull() as? Int)?.let(target::getPrev)
                    else -> getNodeInvoke(target.current as SnapshotNode, name, args)
                }

                is CharSequence -> getCharSequenceInvoke(target, name, args)
                is Int -> getIntInvoke(target, name, args)
                is Boolean -> getBooleanInvoke(target, name, args)
                else -> null
            }
        },
        getName = { it.info.attr.name },
        getChildren = { it.children.asSequence() },
        getParent = { it.parent },
        getRoot = { node ->
            var root = node
            while (root.parent != null) root = root.parent!!
            root.takeUnless { it === node }
        },
    )
}
