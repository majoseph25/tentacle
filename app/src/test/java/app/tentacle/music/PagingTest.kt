// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingTest {

    private val list = (0 until 250).toList()

    @Test
    fun pageNeverExceedsRequestedSize() {
        // Media3 crashes the app if a page is bigger than requested (the v0.3.0 Songs crash).
        for (size in listOf(1, 7, 100, 249, 250, 1000)) {
            for (page in 0..3) assertTrue("size=$size page=$page", pageOf(list, page, size).size <= size)
        }
    }

    @Test
    fun slicesPages() {
        assertEquals((0 until 100).toList(), pageOf(list, 0, 100))
        assertEquals((200 until 250).toList(), pageOf(list, 2, 100))
        assertEquals(emptyList<Int>(), pageOf(list, 3, 100))
    }

    @Test
    fun unpagedRequestsGetEverything() {
        assertEquals(list, pageOf(list, 0, Int.MAX_VALUE))
        assertEquals(list, pageOf(list, 5, 0))
    }

    @Test
    fun hugePageNumbersDoNotOverflow() {
        assertEquals(Int.MAX_VALUE, pageStart(Int.MAX_VALUE, 200))
        assertEquals(0, pageStart(-5, 200))
        assertEquals(emptyList<Int>(), pageOf(list, Int.MAX_VALUE, 200))
    }
}
