package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AppIcon

// 桌面图标靠 activity-alias 启停切换：先启用目标再禁用其余，中途失败也不会没有入口
object AppIconSwitcher {
    private val aliasNames = mapOf(
        AppIcon.RABBIT to "me.rerere.rikkahub.IconRabbit",
        AppIcon.CAT_BODY to "me.rerere.rikkahub.IconCatBody",
        AppIcon.CAT_HEAD to "me.rerere.rikkahub.IconCatHead",
        AppIcon.EARTH to "me.rerere.rikkahub.IconEarth",
        AppIcon.CAT_CUTE to "me.rerere.rikkahub.IconCatCute",
        AppIcon.GOOGLE to "me.rerere.rikkahub.IconGoogle",
    )

    fun apply(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        val target = aliasNames[icon] ?: return
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        aliasNames.values.filter { it != target }.forEach { alias ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

// 设置页预览用（兔盔取原版 legacy png，避免 adaptive xml 在 painter 里解析失败）
@get:DrawableRes
val AppIcon.previewRes: Int
    get() = when (this) {
        AppIcon.RABBIT -> R.mipmap.ic_launcher_rabbit
        AppIcon.CAT_BODY -> R.mipmap.ic_launcher_cat_body
        AppIcon.CAT_HEAD -> R.mipmap.ic_launcher_cat_head
        AppIcon.EARTH -> R.mipmap.ic_launcher_earth
        AppIcon.CAT_CUTE -> R.mipmap.ic_launcher_cat_cute
        AppIcon.GOOGLE -> R.mipmap.ic_launcher_google
    }

// 设置页展示用的图标名，跟随系统语言
@get:StringRes
val AppIcon.labelRes: Int
    get() = when (this) {
        AppIcon.RABBIT -> R.string.setting_app_icon_rabbit
        AppIcon.CAT_BODY -> R.string.setting_app_icon_cat_body
        AppIcon.CAT_HEAD -> R.string.setting_app_icon_cat_head
        AppIcon.EARTH -> R.string.setting_app_icon_earth
        AppIcon.CAT_CUTE -> R.string.setting_app_icon_cat_cute
        AppIcon.GOOGLE -> R.string.setting_app_icon_google
    }
