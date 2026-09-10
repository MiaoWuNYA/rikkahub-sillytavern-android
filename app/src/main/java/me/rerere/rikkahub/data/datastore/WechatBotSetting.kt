package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 微信 Bot (iLink 协议) 配置.
 *
 * 微信 Bot 不是新功能，而是给某个已有助手多开一个"微信消息通道"：
 * 只配置 assistantId，AI/记忆/工具全部复用那个助手。
 * 每个用户在自己手机上扫码登录自己的微信号，拿到 bot_token，各自独立。
 *
 * 字段:
 *  - [enabled]: 总开关. 开启后启动 WeixinBotService 长轮询.
 *  - [assistantId]: 关联的助手. 留空 = 用当前助手 (settings.getCurrentAssistant()).
 *  - [botToken]: 扫码登录后从 iLink 服务器拿到的 Bearer token, 后续所有请求带上.
 *  - [baseUrl]: iLink 服务器地址, 扫码确认时可能下发不同的 baseurl.
 *  - [botId]: 本机微信号对应的 ilink_bot_id (登录后下发), 仅用于显示/标识.
 *  - [pollIntervalSec]: 两次长轮询之间的间隔 (秒). 长轮询本身会 hold 最多 35s.
 */
@Serializable
data class WechatBotSetting(
    val enabled: Boolean = false,
    val assistantId: String = "",
    val botToken: String = "",
    val baseUrl: String = "https://ilinkai.weixin.qq.com",
    val botId: String = "",
    val pollIntervalSec: Int = 1,
)
