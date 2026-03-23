/*
 * Copyright (C) 2025-2026  Aethel-Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aethel.aecardtools.ui.navigation

/**
 * 导航路由定义
 */
sealed class NavigationRoute(val route: String) {
    object Home : NavigationRoute("home")
    object KeyVault : NavigationRoute("key_vault")
    object Settings : NavigationRoute("settings")
    object CardDetail : NavigationRoute("card_detail/{uid}") {
        fun createRoute(uid: String) = "card_detail/$uid"
    }
    object ReadWizard : NavigationRoute("read_wizard")
    object WriteWizard : NavigationRoute("write_wizard")
    object AEFSFormatter : NavigationRoute("aefs_formatter")
}

/**
 * 导航抽屉项定义
 */
enum class DrawerMenuItem(val labelKey: String, val route: NavigationRoute) {
    HOME("nav_home", NavigationRoute.Home),
    KEY_VAULT("nav_key_vault", NavigationRoute.KeyVault),
    SETTINGS("nav_settings", NavigationRoute.Settings)
}

/**
 * 底部栏按钮类型
 */
enum class BottomBarButtonType {
    IDENTIFY,    // 首页 - 识别
    TOOLKIT,     // 首页 - 工具
    IMPORT,      // 密钥库 - 导入
    EXPORT,      // 密钥库 - 导出
    SCAN         // 密钥库 - 扫描
}
