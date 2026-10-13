# 华灯文档站（VitePress）

对齐上游 [docs.rikka-ai.com](https://docs.rikka-ai.com/zh/introduction) 的文档框架，主题沿用首页 `site/` 的 MD3 色板（`.vitepress/theme/custom.css`）。

## 命令

```bash
pnpm install   # 首次
pnpm dev       # 本地开发 http://localhost:5173
pnpm build     # 构建纯静态产物 → .vitepress/dist/
pnpm preview   # 本地预览构建产物 http://localhost:4173
```

## 追加页面

1. 在对应目录新建 `页面名.md`（frontmatter 写 `title` 即可）；
2. 在 `.vitepress/config.mts` 的 `sidebar` 数组中登记 `{ text, link }`；
3. 重新 `pnpm build`。

## 内容事实核对约定

文档中的操作路径、开关名称、数量描述必须与代码一致后再合入。常用核对点：

- 设置入口文案：`app/src/main/res/values-zh/strings.xml`（`setting_page_*`）
- 助手详情页区块：`ui/pages/assistant/detail/AssistantDetailPage.kt`
- 角色卡导入按钮：`ui/pages/assistant/detail/AssistantImporter.kt`
- 斜杠命令表：`ui/components/ai/SlashCommands.kt`
- 宏目录：`data/ai/transformers/MacroEngine.kt`（`macroCatalog`）
- 设备工具清单：`data/ai/tools/DeviceToolbox.kt`（`innerTools`）
- 豆包音色：`ui/pages/setting/components/TTSProviderConfigure.kt`
- 更新机制：`utils/UpdateChecker.kt` + 聊天抽屉 `UpdateCard`
