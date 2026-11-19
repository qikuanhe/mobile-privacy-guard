package com.daweiba.privacyguard

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * targetSdk 35 强制边到边显示，内容会绘制到状态栏/导航栏下面。
 * 统一在这里补偿系统栏高度，避免标题被状态栏"吃掉"。
 */
object UiUtil {

    fun applySystemBarInsets(v: View, baseTopDp: Int, baseBottomDp: Int) {
        val density = v.resources.displayMetrics.density
        val baseTop = (baseTopDp * density).toInt()
        val baseBottom = (baseBottomDp * density).toInt()
        val left = v.paddingLeft
        val right = v.paddingRight
        v.setOnApplyWindowInsetsListener { view, insets ->
            var top = 0
            var bottom = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val sb = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = sb.top
                bottom = sb.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(left, baseTop + top, right, baseBottom + bottom)
            insets
        }
        v.requestApplyInsets()
    }
}
