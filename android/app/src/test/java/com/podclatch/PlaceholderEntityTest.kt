package com.podclatch

import com.podclatch.data.db.PlaceholderEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderEntityTest {
    @Test
    fun defaultIdIsZero() {
        assertEquals(0L, PlaceholderEntity().id)
    }

    @Test
    fun copyKeepsId() {
        assertEquals(7L, PlaceholderEntity(id = 7).copy().id)
    }
}
