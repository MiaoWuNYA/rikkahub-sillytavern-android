package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * QQ Bot (QQ 开放平台 API v2, WebSocket 网关) 配置.
 *
 * QQ Bot 是当前助手的"QQ 私聊通道"，只处理私聊 (C2C_MESSAGE_CREATE)。
 * 每个用户自己去 q.qq.com 注册机器人，拿 AppID/Secret 填进来。
 *
 * 字段:
 *  - [enabled]: 总开关. 开启后启动 QqBotService 建立 WebSocket 连接.
 *  - [appId] / [appSecret]: 在 QQ 开放平台 (q.qq.com) 创建机器人后获得.
 *  - [accessToken] / [accessTokenExpireAt]: getAppAccessToken 返回的凭证缓存 (过期前刷新).
 *    持久化是为了重启后不立即重新拿 token, 但过期仍会自动刷新.
 */
@Serializable
data class QqBotSetting(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val accessToken: String = "",
    val accessTokenExpireAt: Long = 0,
)
