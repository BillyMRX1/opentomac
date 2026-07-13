package dev.opentomac.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {

    @Test
    fun greetingIncludesName() {
        assertEquals("Hello, world, from opentomac shared!", greeting("world"))
    }
}
