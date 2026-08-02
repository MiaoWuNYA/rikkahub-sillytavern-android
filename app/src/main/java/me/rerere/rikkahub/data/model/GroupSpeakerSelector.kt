package me.rerere.rikkahub.data.model

import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * 群聊发言人选择器 — 对齐酒馆官方 group-chats.js
 *
 * NATURAL: talkativeness(0-1) × 骰子 + 名字分词匹配，不强制人数
 * LIST:    全部启用成员
 * POOLED:  用户消息后未发言者优先随机选 1 人
 * MANUAL:  用户指定；非用户输入（自动接话）时随机选 1 人
 */
object GroupSpeakerSelector {

    private fun extractWords(text: String): Set<String> {
        return text.split(Regex("[\\s,，。！？.!?、；;：:/\\\\()（）\\[\\]【】{}「」『』\"']+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun findMentionedMembers(
        input: String,
        members: List<Assistant>,
        bannedId: Uuid?,
    ): Set<Uuid> {
        if (input.isBlank()) return emptySet()
        val inputWords = extractWords(input)
        if (inputWords.isEmpty()) return emptySet()
        return members.filter { m ->
            if (bannedId != null && m.id == bannedId) return@filter false
            val nameWords = extractWords(m.name)
            nameWords.isNotEmpty() && nameWords.any { word ->
                inputWords.any { it.equals(word, ignoreCase = true) }
            }
        }.map { it.id }.toSet()
    }

    /**
     * NATURAL 模式（对齐酒馆）
     *
     * 1. 名字匹配 → 提到的入选
     * 2. 按 talkativeness 掷骰子（打乱顺序遍历）
     * 3. 没人入选时从 chatty（talkativeness>0）成员里随机补 1 个
     * 4. 禁止连续同一个人发言
     */
    fun pickNatural(
        members: List<Assistant>,
        userInput: String,
        lastSpeakerId: Uuid?,
        allowSelfResponses: Boolean,
    ): List<Uuid> {
        if (members.isEmpty()) return emptyList()
        val activated = mutableSetOf<Uuid>()
        val bannedId = if (allowSelfResponses) null else lastSpeakerId

        // 1. 名字匹配
        if (userInput.isNotBlank()) {
            activated.addAll(findMentionedMembers(userInput, members, bannedId))
        }

        // 2. 按 talkativeness 掷骰子（打乱顺序）
        val chatty = mutableListOf<Assistant>()
        val shuffled = members.shuffled()
        for (m in shuffled) {
            if (bannedId != null && m.id == bannedId) continue
            if (m.talkativeness > 0) chatty.add(m)
            if (Random.nextFloat() < m.talkativeness) {
                activated.add(m.id)
            }
        }

        // 3. 仍没人 → 优先从 talkativeness>0 的成员里随机选一个
        if (activated.isEmpty()) {
            val pool = chatty.ifEmpty { members }
            pool.randomOrNull()?.let { activated.add(it.id) }
        }

        return activated.toList()
    }

    /**
     * LIST 模式：全部启用成员（对齐酒馆 activateListOrder）
     */
    fun pickList(members: List<Assistant>): List<Uuid> {
        return members.map { it.id }
    }

    /**
     * POOLED 模式：用户消息后未发言者优先随机选 1 人（对齐酒馆 activatePooledOrder）
     */
    fun pickPooled(
        members: List<Assistant>,
        lastSpeakerId: Uuid?,
        speakerHistory: List<Uuid>,
        allowSelfResponses: Boolean,
        isUserInput: Boolean = true,
    ): List<Uuid> {
        if (members.isEmpty()) return emptyList()

        // 对齐酒馆 activatePooledOrder：用户输入时立即停止统计，未发言者=全体成员
        val haveNotSpoken = if (isUserInput) members else members.filter { it.id !in speakerHistory }
        val picked = if (haveNotSpoken.isNotEmpty()) {
            haveNotSpoken.random()
        } else {
            val pool = if (!isUserInput && members.size > 1 && lastSpeakerId != null && !allowSelfResponses) {
                members.filter { it.id != lastSpeakerId }
            } else members
            pool.randomOrNull() ?: return emptyList()
        }
        return listOf(picked.id)
    }

    /**
     * 统一入口
     */
    fun pick(
        strategy: GroupActivationStrategy,
        members: List<Assistant>,
        enabledMembers: List<Assistant>,
        userInput: String = "",
        lastSpeakerId: Uuid? = null,
        speakerHistory: List<Uuid> = emptyList(),
        allowSelfResponses: Boolean = false,
        manualSpeakerId: Uuid? = null,
        isUserInput: Boolean = true,
    ): List<Uuid> {
        return when (strategy) {
            GroupActivationStrategy.NATURAL -> pickNatural(
                enabledMembers, userInput, lastSpeakerId, allowSelfResponses,
            )
            GroupActivationStrategy.LIST -> pickList(enabledMembers)
            GroupActivationStrategy.POOLED -> pickPooled(
                enabledMembers, lastSpeakerId, speakerHistory, allowSelfResponses, isUserInput,
            )
            GroupActivationStrategy.MANUAL -> {
                val sid = manualSpeakerId
                when {
                    sid != null && enabledMembers.none { it.id == sid } -> emptyList()
                    sid != null -> listOf(sid)
                    isUserInput -> emptyList() // 用户输入时只上屏消息不生成（对齐酒馆）
                    else -> enabledMembers.shuffled().take(1).map { it.id }
                }
            }
        }
    }
}
