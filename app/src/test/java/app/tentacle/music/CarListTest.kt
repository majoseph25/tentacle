// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import org.junit.Assert.assertEquals
import org.junit.Test

class CarListTest {

    @Test
    fun chunkStartIsReadFromTheId() {
        assertEquals(0, Library.carChunkStart("artists@0"))
        assertEquals(200, Library.carChunkStart("artists@200"))
        assertEquals(400, Library.carChunkStart("albums@400"))
    }

    @Test
    fun malformedOrAbsurdChunkIdsStartAtTheBeginning() {
        // Ids come from Android Auto, so anything odd falls back to the first chunk.
        listOf("artists", "artists@", "artists@-200", "artists@x", "artists@1.5", "artists@99999999999", "albums@2000001")
            .forEach { assertEquals(it, 0, Library.carChunkStart(it)) }
    }

    @Test
    fun fullListIsTheDefaultStyle() {
        assertEquals(CarListStyle.FULL, CarListStyle.parse(null))
        assertEquals(CarListStyle.FULL, CarListStyle.parse("SOMETHING"))
        assertEquals(CarListStyle.INDEX, CarListStyle.parse("INDEX"))
    }
}
