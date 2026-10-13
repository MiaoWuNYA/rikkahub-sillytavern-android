import { defineConfig } from 'vitepress'

const GITHUB = 'https://github.com/MiaoWuNYA/rikkahub-sillytavern-android'

// 侧栏分组：对齐上游 rikkahub 文档框架，按华灯功能扩展
const sidebar = [
  {
    text: '快速开始',
    items: [
      { text: '介绍', link: '/' },
      { text: '下载', link: '/download' },
      { text: '快速上手', link: '/quickstart' }
    ]
  },
  {
    text: '对话',
    items: [
      { text: '对话', link: '/chat/conversations' },
      { text: '多模态', link: '/chat/multimodal' },
      { text: '分支', link: '/chat/branching' },
      { text: '网页搜索', link: '/chat/search' }
    ]
  },
  {
    text: '记忆与缓存',
    items: [
      { text: '提示词缓存', link: '/memory/cache' },
      { text: '记忆系统', link: '/memory/memory' },
      { text: 'Jev 智能决策', link: '/memory/jev' }
    ]
  },
  {
    text: '助手',
    items: [
      { text: '概览', link: '/assistants/overview' },
      { text: '人设与导演备注', link: '/assistants/persona' },
      { text: '群聊', link: '/assistants/group-chat' }
    ]
  },
  {
    text: '酒馆兼容',
    items: [
      { text: '角色卡', link: '/tavern/character-card' },
      { text: '世界书', link: '/tavern/world-book' },
      { text: '预设·正则·快速回复', link: '/tavern/preset-regex' },
      { text: '宏与斜杠命令', link: '/tavern/macros-slash' },
      { text: '美化主题', link: '/tavern/theme' }
    ]
  },
  {
    text: '扩展功能',
    items: [
      { text: '插件', link: '/extensions/plugins' },
      { text: '技能', link: '/extensions/skills' },
      { text: '本地工具', link: '/extensions/local-tools' },
      { text: 'MCP', link: '/extensions/mcp' },
      { text: '手机增强', link: '/extensions/toolbox' },
      { text: '语音与通话', link: '/extensions/speech' },
      { text: '工作区', link: '/extensions/workspace' }
    ]
  },
  {
    text: '情感陪伴',
    items: [
      { text: '情侣空间', link: '/companion/couple-space' },
      { text: '生活空间', link: '/companion/life-space' }
    ]
  },
  {
    text: '设置与同步',
    items: [
      { text: '华灯设置', link: '/settings/huadeng' },
      { text: '外观', link: '/settings/appearance' },
      { text: 'Web 访问', link: '/settings/web-access' },
      { text: '备份', link: '/settings/backup' }
    ]
  }
]

export default defineConfig({
  lang: 'zh-CN',
  // 纯产物输出：构建结果直接生成到部署目录 site/docs/（相对 docs-site 解析）
  outDir: '../site/docs',
  // 部署在站点 /docs/ 子路径下，资源引用需带此前缀
  base: '/docs/',
  title: '华灯 · RikkaHub Plus',
  description: '原生安卓 AI 聊天客户端，兼为 SillyTavern 兼容端。接上 API 就能聊，角色卡 / 世界书 / 预设按酒馆官方语义一键导入。',
  head: [
    ['link', { rel: 'icon', type: 'image/webp', href: 'https://bu.dusays.com/2026/10/12/6acc74bdbe387.webp' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    ['link', { rel: 'stylesheet', href: 'https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600;700;900&display=swap' }],
    ['meta', { name: 'theme-color', media: '(prefers-color-scheme: light)', content: '#fff8f5' }],
    ['meta', { name: 'theme-color', media: '(prefers-color-scheme: dark)', content: '#161211' }]
  ],
  cleanUrls: true,
  themeConfig: {
    logo: 'https://bu.dusays.com/2026/10/12/6acc74bdbe387.webp',
    nav: [
      { text: '文档', link: '/', activeMatch: '^/$|^/(download|quickstart|chat|memory|assistants|tavern|extensions|settings)' },
      { text: 'GitHub', link: GITHUB }
    ],
    sidebar,
    socialLinks: [{ icon: 'github', link: GITHUB }],
    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
          modal: {
            noResultsText: '没有匹配的结果',
            resetButtonTitle: '清除关键词',
            footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' }
          }
        }
      }
    },
    outline: { label: '此页面', level: [2, 3] },
    docFooter: { prev: '上一篇', next: '下一篇' },
    lastUpdated: { text: '最后更新', formatOptions: { dateStyle: 'medium' } },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '菜单',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到浅色',
    darkModeSwitchTitle: '切换到深色'
  },
  markdown: {
    lineNumbers: false,
    // 把所有渲染输出中的 {{...}}（酒馆宏）转义为 HTML 实体，
    // 避免被 Vue 模板插值解析；覆盖正文、行内代码、代码块与内嵌 HTML
    config(md) {
      const escapeBraces = (s) => s
        .replace(/\{\{/g, '&#123;&#123;')
        .replace(/\}\}/g, '&#125;&#125;')
      for (const name of ['text', 'code_inline', 'fence', 'html_block', 'html_inline']) {
        const original = md.renderer.rules[name]
        md.renderer.rules[name] = (tokens, idx, options, env, self) => {
          const html = original
            ? original(tokens, idx, options, env, self)
            : self.renderToken(tokens, idx, options)
          return escapeBraces(html)
        }
      }
    }
  }
})
