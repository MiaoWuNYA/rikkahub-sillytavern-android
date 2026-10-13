---
layout: home
title: 介绍
titleTemplate: 欢迎使用华灯 — 你的安卓 AI 聊天应用

hero:
  name: 华灯
  text: 你的安卓 AI 聊天应用
  tagline: 接上 API 就能聊。角色卡、世界书、预设按酒馆官方语义一键导入——酒馆与日常聊天两条路线，共用同一个客户端，互不干扰。
  actions:
    - theme: brand
      text: 快速上手 →
      link: /quickstart
    - theme: alt
      text: GitHub 仓库
      link: https://github.com/MiaoWuNYA/rikkahub-sillytavern-android

features:
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M13 3 6.5 13.5h4.8L10.5 21 17.5 10.5h-4.9L13 3Z"/></svg>'
    title: 接上 API 就能聊
    details: 多供应商、流式输出、Markdown 与代码高亮，数据全部保存在本机，没有订阅费与中间商。
    link: /quickstart
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 4.5A3 3 0 0 0 6.6 8 3 3 0 0 0 5 10.8a3 3 0 0 0 1.7 2.7A3 3 0 0 0 9.5 19c1 0 2-.5 2.5-1.3V4.5a2.6 2.6 0 0 0-2.5 0Z"/><path d="M14.5 4.5A3 3 0 0 1 17.4 8 3 3 0 0 1 19 10.8a3 3 0 0 1-1.7 2.7A3 3 0 0 1 14.5 19c-1 0-2-.5-2.5-1.3"/></svg>'
    title: 记忆与长对话
    details: 语义记忆、三层记忆与滚动压缩，长对话不失忆、不爆上下文，AI 开局就认识你。
    link: /memory/memory
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="4" width="14" height="4.6" rx="2.3"/><rect x="4" y="9.7" width="16" height="4.6" rx="2.3"/><rect x="5" y="15.4" width="14" height="4.6" rx="2.3"/></svg>'
    title: 提示词缓存优化
    details: 消除每轮变化的上下文分叉点，长对话稳定命中前缀缓存，token 开销显著下降。
    link: /memory/cache
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.6"/><path d="m7.6 12.6 3.1 3.1 5.7-6.6"/></svg>'
    title: Jev 智能决策
    details: 专用判断小模型替主模型做记忆筛选与是非判断，毫秒级响应，静默回退不阻塞对话。
    link: /memory/jev
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3h8v4.5a4 4 0 0 1-8 0V3Z"/><path d="M12 11.5V18M8.5 21h7M9.8 21c1.4-.6 2.2-1.5 2.2-3M14.2 21c-1.4-.6-2.2-1.5-2.2-3"/></svg>'
    title: 酒馆深度兼容
    details: 角色卡、世界书、预设、正则、快速回复、美化主题，按 SillyTavern 官方语义无损导入导出。
    link: /tavern/character-card
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><path d="M16.5 13.4v6.2M13.4 16.5h6.2"/></svg>'
    title: 插件系统
    details: QuickJS 沙箱插件 ZIP 导入，AI 可直接调用插件工具，内置三个实用插件开箱即用。
    link: /extensions/plugins
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="7" y="2.5" width="10" height="19" rx="3"/><path d="M10.5 18.5h3"/></svg>'
    title: 手机增强
    details: 30 个设备工具供 AI 调用，微信 / QQ Bot 接入指定助手，AI 还能按计划主动找你聊天。
    link: /extensions/toolbox
  - icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="9.5" y="2.5" width="5" height="11" rx="2.5"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M8.5 21h7"/></svg>'
    title: 语音与通话
    details: 豆包 TTS 2.0 + 火山 ASR，一个 Agent Plan Key 支撑语音与视频通话，随时打断、通话存档。
    link: /extensions/speech
---

华灯是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度定制分支，使用 Kotlin 与 Jetpack Compose 原生构建——不依赖 Termux，也不在后台挂 Node 服务。装上就能聊；想要酒馆能力时，再把桌面端的资产搬进来。

如果你正在用 Chatbox 或其他通用客户端接 API 聊天，华灯是值得考虑的升级：基础体验完全对齐，之上还多了一整层记忆、缓存、插件与酒馆兼容能力。见 [从 Chatbox 换过来](/quickstart#chatbox)。

::: tip 两条路线互不干扰
酒馆相关功能默认不向普通对话注入额外上下文——不导入角色卡时，华灯就是一个干净的多供应商聊天客户端。
:::
