// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

/**
 * First item index of a page, clamped so huge page numbers can't overflow into a negative index
 * (a negative StartIndex would be sent to the server).
 */
fun pageStart(page: Int, pageSize: Int): Int {
    if (page <= 0 || pageSize <= 0) return 0
    return (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * One page of an already-built list. Int.MAX_VALUE (or a non-positive size) means "no paging".
 * Never returns more than [pageSize] items: Media3 treats a larger page as a fatal error.
 */
fun <T> pageOf(list: List<T>, page: Int, pageSize: Int): List<T> {
    if (pageSize !in 1 until Int.MAX_VALUE) return list
    val from = pageStart(page, pageSize)
    if (from >= list.size) return emptyList()
    return list.subList(from, minOf(list.size, from + pageSize))
}
