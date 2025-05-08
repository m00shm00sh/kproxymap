package com.moshy

import com.moshy.ProxyMap
import com.moshy.util.*
import com.moshy.util.PropVal.Companion.toMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test


class ProxyMapMiscTests {

    @Test
    fun `test ProxyMap toString`() {
        val o1 = RegularClass("abc")
        val expMapData = PropVal(RegularClass::prop1, "abc").toList().toMap().toString()
        val map = ProxyMap.fromDataclass(o1)
        assertEquals("ProxyMap<com.moshy.RegularClass>$expMapData", map.toString())
    }
}