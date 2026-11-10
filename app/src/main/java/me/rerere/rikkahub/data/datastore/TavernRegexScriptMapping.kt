package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.st.regex.RegexPlacement
import me.rerere.rikkahub.data.st.regex.RegexScript

/** Settings.globalRegexes 映射成酒馆正则；scope/promptOnly/visualOnly 语义同 AssistantRegex 自身过滤。 */
fun Settings.globalRegexScripts(): List<RegexScript> = globalRegexes.map { it.toRegexScript() }

private fun AssistantRegex.toRegexScript(): RegexScript = RegexScript(
    id = id.toString(),
    scriptName = name,
    findRegex = findRegex,
    replaceString = replaceString,
    placement = buildList {
        if (AssistantAffectScope.USER in affectingScope) add(RegexPlacement.USER_INPUT)
        if (AssistantAffectScope.ASSISTANT in affectingScope) add(RegexPlacement.AI_OUTPUT)
    },
    disabled = !enabled,
    markdownOnly = visualOnly,
    promptOnly = promptOnly,
    runOnEdit = false,
    // native 语义：<=0 表示不限制深度，引擎里必须给 null
    minDepth = minDepth.takeIf { it > 0 },
    maxDepth = maxDepth.takeIf { it > 0 },
)
