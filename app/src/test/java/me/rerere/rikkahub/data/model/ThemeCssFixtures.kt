package me.rerere.rikkahub.data.model

/**
 * 真实主题 CSS 的精简夹具。
 *
 * 每个片段都取自实际主题文件（酒馆美化合集），已裁掉与提取逻辑无关的装饰规则，
 * 只保留曾导致提取错误的形态。用于防止这几类问题回归：
 * - 注释里写占位 url，真正的 url 在后面（朝雾系列导入过字面量「你的图片链接」）；
 * - 注释里出现 body/.mes 等词，把装饰图误判成聊天背景/气泡底图；
 * - 背景图铺在 .mes_block / .mes 上（bjd、蝶 系列的主题特征）；
 * - 伪元素上的头像框被当成气泡底图。
 */
internal object ThemeCssFixtures {

    /**
     * 朝雾(粉白轻松熊) 的真实形态（已简化）。
     *
     * 关键点：被注释掉的占位 `background: url("你的图片链接")` 恰好紧跟在
     * `body.no-blur #top-bar` 规则体内，且注释里出现 body —— 旧实现会把注释和
     * 选择器粘在一起，从而把这个占位串当成聊天背景图导入。
     */
    const val PLACEHOLDER_IN_COMMENT = """
#top-bar,
body.no-blur #top-bar {
  box-shadow: none !important;
  outline: 1px solid rgba(0,0,0,.2) !important;
  /* 顶栏背景图更换请把注.释符号删去 */
 /* background: url("你的图片链接");
  background-size: cover;
  background-repeat: no-repeat; */
}

/* 聊天区真实背景 */
body {
  background-image: url('https://i.postimg.cc/43Jv3pM5/IMG-4662.jpg');
  background-size: cover;
}
"""

    /** 注释里提到 body，但真正带图的是图标 */
    const val COMMENT_MENTIONS_BODY = """
/* 这个 body 区域的图标说明 */
.drawer-icon.fa-solid::before {
  background-image: url('https://x.example/icon.png');
}
"""

    /** bjd 系列：纹理直接铺在 .mes_block 上 */
    const val BUBBLE_ON_MES_BLOCK = """
.mes_block {
  background-image: url('https://files.catbox.moe/im5vzu.jpeg');
  background-size: 100% 100%;
  border-radius: 12px;
}
"""

    /** 蝶 系列：两侧共用一个 .mes 底图 */
    const val BUBBLE_ON_MES = """
.mes {
  background: url("https://iili.io/fWSOCIR.png") no-repeat center;
  background-size: contain;
}
"""

    /** 区分 is_user 的主题 */
    const val BUBBLE_IS_USER = """
.mes[is_user="true"] { background-image: url('https://x.example/user.png'); }
.mes[is_user="false"] { background-image: url('https://x.example/bot.png'); }
"""

    /** 头像框/角标：伪元素上的图必须被忽略 */
    const val PSEUDO_DECORATIONS = """
.avatar::after { background-image: url('https://x.example/frame.png'); }
.mes::after   { background-image: url('https://x.example/corner.png'); }
.mes::before  { background-image: url('https://x.example/gem.png'); }
#options_button::after { background-image: url('https://x.example/btn.png'); }
"""

    /** 顶部 UI 皮肤（不涉及聊天区），应提取不到任何背景 */
    const val UI_SHELL_ONLY = """
#top-settings-holder {
  background-image: url('https://x.example/topbar.png');
}
#extensionsMenuButton::before {
  background-image: url('https://x.example/menu.png');
}
"""
}
