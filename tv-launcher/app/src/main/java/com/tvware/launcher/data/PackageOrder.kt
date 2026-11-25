package com.tvware.launcher.data

internal fun movePackage(order: List<String>, pkg: String, delta: Int): List<String>? {
    val from = order.indexOf(pkg)
    if (from < 0) return null
    val to = (from.toLong() + delta).coerceIn(0, order.lastIndex.toLong()).toInt()
    if (from == to) return null
    return order.toMutableList().apply { add(to, removeAt(from)) }
}
