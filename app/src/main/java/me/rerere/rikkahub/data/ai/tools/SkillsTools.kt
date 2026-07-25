package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths

/**
 * Skill 工具 — Claude Code 风格
 * 一个 use_skill 工具：加载 SKILL.md，自动返回 linked_files
 */
fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    skillManager: SkillManager? = null,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    val byCategory = available.groupBy { it.category ?: "其他" }

    return listOf(
        Tool(
            name = "use_skill",
            description = "Load a skill by name to access specialized knowledge, commands, and workflows.\n\n" +
                "Use this tool before starting a task that matches a skill's domain — it contains API endpoints, tool commands, and proven approaches.\n\n" +
                "When to use:\n" +
                "- A task matches a skill's description or triggers\n" +
                "- You need domain-specific guidance\n" +
                "- The user mentions a topic with a relevant skill\n\n" +
                "When NOT to use:\n" +
                "- General coding tasks without a matching skill\n" +
                "- Browsing skill directories (use this tool with a specific name instead)\n\n" +
                "Args:\n" +
                "- name: Skill name to load (see <available_skills> for options)\n" +
                "- Available skills are listed in <available_skills> above",
            systemPrompt = { _, _ ->
                buildString {
                    appendLine("## Skills")
                    appendLine("Skills provide specialized knowledge and workflows. Use `use_skill` to load a skill by name. Do NOT use file action=\"list\" or file action=\"search\" to browse skill directories.")
                    appendLine("<available_skills>")
                    byCategory.forEach { (cat, skills) ->
                        appendLine("  <!-- $cat -->")
                        skills.forEach { s ->
                            append("  - ${s.name}: ${s.description.take(80)}")
                            if (s.triggers.isNotEmpty()) append(" [触发: ${s.triggers.take(3).joinToString()}]")
                            if (s.linkedFiles.isNotEmpty()) {
                                val count = s.linkedFiles.values.sumOf { it.size }
                                append(" (+${count}文件)")
                            }
                            appendLine()
                        }
                    }
                    appendLine("</available_skills>")
                }
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Skill name")
                        })
                        put("file_path", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional sub-file path from linked_files. Omit for main SKILL.md.")
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: error("name required")
                if (name !in enabledSkills) error("'$name' not available")
                val skill = available.find { it.name == name }
                    ?: error("Skill '$name' not found")

                val filePath = obj["file_path"]?.jsonPrimitive?.content
                val content = if (filePath.isNullOrBlank()) {
                    val body = skillManager?.readSkillBody(name)
                        ?: skill.skillFile.takeIf { it.exists() }?.let { SkillFrontmatterParser.extractBody(it.readText()) }
                        ?: error("Skill '$name' not found")
                    buildString {
                        appendLine(body)
                        if (skill.linkedFiles.isNotEmpty()) {
                            appendLine()
                            appendLine("--- linked_files ---")
                            skill.linkedFiles.forEach { (dir, files) ->
                                files.forEach { f -> appendLine("$dir/$f") }
                            }
                        }
                    }
                } else {
                    val target = skillManager?.resolveSkillFile(name, filePath)
                        ?: SkillPaths.resolveSkillFile(skill.skillDir, filePath)
                        ?: error("Path outside skill directory")
                    if (!target.exists()) error("File '$filePath' not found")
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    )
}
