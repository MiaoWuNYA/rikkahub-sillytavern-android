package me.rerere.rikkahub.utils

import me.rerere.ui.markdown.MarkdownBlock
import me.rerere.ui.markdown.MarkdownInline
import me.rerere.ui.markdown.parseMarkdown

// 复用渲染用的 parseMarkdown 取朗读文本；输出已解码，别对结果再洗一次
fun String.stripMarkdown(): String {
    return buildString { appendBlocks(parseMarkdown(this@stripMarkdown).blocks) }
        // 去掉代码等内容后会留下空行和连续空格
        .replace(Regex("[ \t]*\n\\s*"), "\n")
        .replace(Regex("[ \t]{2,}"), " ")
        .trim()
}

private fun StringBuilder.appendBlocks(blocks: List<MarkdownBlock>) {
    for (block in blocks) {
        when (block) {
            is MarkdownBlock.Paragraph -> appendInlines(block.content)
            is MarkdownBlock.Heading -> appendInlines(block.content)
            is MarkdownBlock.Quote -> appendBlocks(block.children)
            is MarkdownBlock.ListBlock -> block.items.forEach { appendBlocks(it.children) }
            is MarkdownBlock.Table -> (listOf(block.header) + block.rows).forEach { row ->
                row.forEach { cell ->
                    appendInlines(cell.content)
                    append(' ')
                }
                append('\n')
            }

            is MarkdownBlock.Details -> {
                appendInlines(block.summary)
                append('\n')
                appendBlocks(block.children)
            }

            is MarkdownBlock.Container -> appendBlocks(block.children)
            is MarkdownBlock.CodeBlock, MarkdownBlock.Divider -> Unit
        }
        append('\n')
    }
}

private fun StringBuilder.appendInlines(nodes: List<MarkdownInline>) {
    for (node in nodes) {
        when (node) {
            is MarkdownInline.Text -> append(node.text)
            is MarkdownInline.Styled -> appendInlines(node.children)
            is MarkdownInline.Link -> appendInlines(node.children)
            is MarkdownInline.Code, is MarkdownInline.Image, is MarkdownInline.Math -> Unit
        }
    }
}

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()

        // 检查是否为加粗格式且独占一整行
        val boldPattern = Regex("^\\*\\*(.+?)\\*\\*$")
        val match = boldPattern.find(line)

        if (match != null) {
            // 返回加粗标记内的文本内容
            return match.groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}
