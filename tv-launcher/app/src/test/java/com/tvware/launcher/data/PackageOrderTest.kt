package com.tvware.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageOrderTest {
    private val original = listOf("a", "b", "c")

    @Test fun movesInBothDirectionsWithoutChangingOriginal() {
        assertEquals(listOf("b", "a", "c"), movePackage(original, "b", -1))
        assertEquals(listOf("a", "c", "b"), movePackage(original, "b", 1))
        assertEquals(listOf("a", "b", "c"), original)
    }

    @Test fun clampsAndIgnoresUnavailableMoves() {
        assertEquals(listOf("b", "c", "a"), movePackage(original, "a", 100))
        assertNull(movePackage(original, "a", -1))
        assertNull(movePackage(original, "c", 1))
        assertNull(movePackage(original, "missing", 1))
        assertNull(movePackage(emptyList(), "a", 1))
    }
}
