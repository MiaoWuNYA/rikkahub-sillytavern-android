---
title: 下载：在安卓上安装华灯
---

# 下载：在安卓上安装华灯

华灯仅提供 `arm64-v8a` 单一架构 APK，支持 Android 8.0（API 26）及以上。覆盖安装即可直接升级，数据不会丢失。

## 下载渠道

| 渠道 | 说明 |
| --- | --- |
| **稳定版（推荐）** | [Releases 最新版](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases/latest)，按版本号发布，经过完整测试 |
| **Nightly** | Actions 每天两次自动构建（过去 24 小时无新提交则跳过），来自 [nightly 预发布](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases/tag/nightly)，包含最新改动但未经完整验证 |
| **手动构建产物** | 每次推送都会在 [Actions](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/actions) 产出 APK Artifact（构建页底部「Artifacts」，需登录 GitHub） |

::: tip 选哪个
日常使用选稳定版即可；想第一时间体验新功能、并能接受偶发问题，再考虑 Nightly。两者覆盖安装互相兼容。
:::

## 应用内更新

安装后不需要反复回到网页下载——华灯会自动检查新版本，并在**聊天页左侧抽屉顶部**出现「发现新版本」卡片：

1. 打开主界面的聊天抽屉。
2. 点按「发现新版本」卡片，查看新版本号与更新说明。
3. 点按后跳转下载 APK，覆盖安装即可。

没有出现卡片说明已是最新版本。更新检查以 GitHub Release 为权威来源；网络不畅时自动回退到 jsDelivr、ghproxy 等镜像读取版本快照，国内网络通常也能正常收到更新提示。

::: warning 安全提醒
请只从本项目的 GitHub Releases 或应用内更新卡片获取 APK。从第三方渠道下载的安装包无法保证完整性。
:::

## 安装要求

- Android 8.0（API 26）或更高版本
- arm64-v8a 设备（2016 年之后的绝大多数机型）
- 安装包约 104 MB，覆盖安装直接升级，聊天与配置数据保留
