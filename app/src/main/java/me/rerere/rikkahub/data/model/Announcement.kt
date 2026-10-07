package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 应用公告。
 *
 * **完全离线**：内容随安装包一起发布，放在 assets/announcement/ 下。
 * 不走网络是刻意的——国内访问 GitHub 常常不通，一个「每次启动去拉公告」
 * 的设计在目标用户那里表现为启动变慢或者干脆没公告，比不做还糟。
 *
 * 想改公告内容时改那个 json 文件、重新发版即可。比起搭一套远端下发，
 * 这条路不需要服务器、不需要处理拉取失败，也不会因为网络问题让用户
 * 看到半截状态。
 *
 * 公告在每次启动时以原生 Compose 弹窗展示，用户可以勾选「不再提示」。
 * 判断依据是 id：改了 id 就是一条新公告，会重新弹一次；内容小改而
 * id 不变的话，点过「不再提示」的人不会被打扰。
 */
@Serializable
data class Announcement(
    /**
     * 唯一标识。
     *
     * 「不再提示」是按它记录的。**改动正文时不要顺手改 id**——
     * 那会让所有点过不再提示的人又被打扰一次。只有真正想让所有人
     * 重看一遍时才换 id。
     */
    val id: String = "",

    val title: String = "",

    /** 正文。用 \n 分段，**不要写 Markdown**：弹窗里的 Text 不解析它。 */
    val body: String = "",

    /**
     * 可选的配图。
     *
     * 相对 assets/announcement/ 的文件名，例如 "banner.png"。
     * 留空则不显示图片。图片直接打进安装包，同样不依赖网络。
     */
    val image: String? = null,

    /**
     * 是否允许「不再提示」。
     *
     * 默认允许。极少数必须让用户看到的内容（例如数据安全相关的通知）
     * 可以设成 false，但**慎用**：一个关不掉的弹窗会消耗用户对
     * 所有公告的耐心。
     */
    val dismissible: Boolean = true,
)
