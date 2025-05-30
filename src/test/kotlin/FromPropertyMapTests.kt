package com.moshy

import com.moshy.util.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FromPropertyMapTests {
    @Test
    fun `test basic `() {
        val m = mapOf("prop2" to "42")
        val pm = ProxyMap.fromProps<RegularClass2>(m)
        val m2 = mapOf("prop2" to 42)
        assertEquals(m2, pm)
    }

    @Test
    fun `test basic casefold`() {
        val m = mapOf("PROP2" to "42")
        val pm = ProxyMap.fromProps<RegularClass2>(m, caseFold = true)
        val m2 = mapOf("prop2" to 42)
        assertEquals(m2, pm)
    }

    @Test
    fun `test basic casefold collision`() {
        val m = mapOf("prop2" to "7", "PROP2" to "42")
        val msg = assertThrows<IllegalArgumentException> {
            ProxyMap.fromProps<RegularClass2>(m, caseFold = true)
        }.message
        assertTrue(msg?.contains("prop2 collides with casefolded property name PROP2") == true)
    }

    @Test
    fun `test recursive`() {
        val m = mapOf("prop2.prop1" to "aaa")
        val pm = ProxyMap.fromProps<ClassWithDataclassMember>(m)
        val m2 = mapOf("prop2" to mapOf("prop1" to "aaa"))
        assertEquals(m2, pm)
    }

    @Test
    fun `test recursive casefold`() {
        val m = mapOf("PROP2.PROP1" to "aaa")
        val pm = ProxyMap.fromProps<ClassWithDataclassMember>(m, caseFold = true)
        val m2 = mapOf("prop2" to mapOf("prop1" to "aaa"))
        assertEquals(m2, pm)
    }

    @Test
    fun `test invalid prop`() {
        val m = mapOf("..a..b" to "1")
        val msg = assertThrows<java.lang.IllegalArgumentException> {
            ProxyMap.fromProps<RegularClass2>(m, caseFold = true)
        }.message
        assertTrue(msg?.contains("empty component in key") == true)
    }
}