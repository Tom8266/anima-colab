package com.tom.animacolab.data

/**
 * 外观主题模式。
 *
 * - [System] 跟随系统深色/浅色
 * - [Light] 始终浅色
 * - [Dark] 始终深色
 */
enum class ThemeMode(val id: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色"),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}
