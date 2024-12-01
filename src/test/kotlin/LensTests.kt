package com.moshy

import com.moshy.util.*
import com.moshy.util.PropVal.Companion.toMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LensTests {
    @Test
    fun `test fromDataclass map`() {
        val o1 = RegularClass("abc")
        val expMap = PropVal(RegularClass::prop1, "abc").toList().toMap()
        val actualMap = ProxyMap.fromDataclass(o1)
        assertEquals(expMap, actualMap)
    }
    @Test
    fun `test fromDataclass map recursively`() {
        val o1 = ClassWithDataclassMember(1, RegularClass("two"))
        val expMap = listOf(
            PropVal(ClassWithDataclassMember::prop1, 1),
            PropVal(ClassWithDataclassMember::prop2,
                PropVal(RegularClass::prop1, "two")
            )
        ).toMap()
        val actualMap = ProxyMap.fromDataclass(o1)
        assertEquals(expMap, actualMap)
    }
    @Test
    fun `fromDataclass reject non-data class`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromDataclass(NonData())
        }
    }
    @Test
    fun `fromDataclass map`() {
        val o1 = RegularClass("abc")
        val expMap = PropVal(RegularClass::prop1, "abc").toList().toMap()
        val actualMap = ProxyMap.fromDataclass(o1)
        assertEquals(expMap, actualMap)
    }
    @Test
    fun `fromDataclass warn non-serializable property`() {
        val o1 = ClassWithTransientProperty(42, "a")
        interceptErrorStreamTest {
            ProxyMap.fromDataclass(o1)
        }
    }
    @Test
    fun `fromDataclass reject generic`() {
        val o1 = Box(5)
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromDataclass(o1)
        }
    }
    @Test
    fun `fromDataclass reject class with ProxyMap member`() {
        val pm1 = ProxyMap.fromDataclass(RegularClass("a"))
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromDataclass(ClassWithPMProperty(pm1))
        }
    }

    @Test
    fun `test fromLensMap`() {
        val prop = PropVal(RegularClass::prop1, "test")
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<RegularClass>(map)
        assertEquals(map, proxy)
    }
    @Test
    fun `test fromLensMap recursively`() {
        val map = listOf(
            PropVal(ClassWithNullableNestedDataclassMember::prop1,
                PropVal(ClassWithNullableNestedDataclassMember.Nested::prop2, 1.23)
            )
        ).toMap()
        val proxy = ProxyMap.fromLensMap<ClassWithNullableNestedDataclassMember>(map)
        assertEquals(map, proxy)
    }
    @Test
    fun `test fromLensMap key rejection`() {
        val prop = PropVal(RegularClass::prop1, "test")
        val expectedMap = prop.toList().toMap()
        val inputMap = expectedMap + mapOf("\$_\$" to null)
        val proxy = ProxyMap.fromLensMap<RegularClass>(inputMap)
        assertEquals(expectedMap, proxy)
    }
    @Test
    fun `fromLensMap reject non-data class type argument`() {
        val map = emptyMap<String, Any?>()
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromLensMap<NonData>(map)
        }
    }
    @Test
    fun `fromLensMap reject incompatible value`() {
        val prop: PropVal<*> = PropVal(RegularClass::prop1, 42)
        val inputMap = prop.toList().toMap()
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromLensMap<RegularClass>(inputMap)
        }
    }
    @Test
    fun `fromLensMap reject incompatible ProxyMap value`() {
        // this is a relatively very verbose construction but necessary to create the partially processed state
        val map =
            mapOf(ClassWithDataclassMember::prop2.name to
                    ProxyMap.fromLensMap<ClassWithNullableMember>(
                        mapOf(ClassWithNullableMember::prop1.name to 42.0))
            )
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromLensMap<ClassWithDataclassMember>(map)
        }
    }
    @Test
    fun `fromLensMap reject ProxyMap value for non-dataclass member`() {
        val pm1 = ProxyMap.fromDataclass(RegularClass("a"))
        val map = PropVal(ClassWithPMProperty::pm, pm1).toList().toMap()
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromLensMap<ClassWithPMProperty>(map)
        }
    }
    @Test
    fun `fromLensMap reject undesired null`() {
        val prop: PropVal<*> = PropVal(RegularClass::prop1, null)
        val inputMap = prop.toList().toMap()
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromLensMap<RegularClass>(inputMap)
        }
    }

    @Test
    fun `test applyToObject`() {
        val prop = PropVal(RegularClass::prop1, "test")
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<RegularClass>(map)
        val o1 = RegularClass("abc")
        val o2 = proxy.applyToObject(o1)
        assertEquals("test", o2.prop1)
    }
    @Test
    fun `test applyToObject recursively`() {
        val o1 = ClassWithNullableNestedDataclassMember(
            ClassWithNullableNestedDataclassMember.Nested(0, 0.0),
            "a"
        )
        val map = listOf(
            PropVal(ClassWithNullableNestedDataclassMember::prop1,
                PropVal(ClassWithNullableNestedDataclassMember.Nested::prop1, 1)
            ),
            PropVal(ClassWithNullableNestedDataclassMember::prop2, "b")
        ).toMap()
        val proxy = ProxyMap.fromLensMap<ClassWithNullableNestedDataclassMember>(map)
        val o2 = proxy.applyToObject(o1)
        assertEquals(1, o2.prop1?.prop1)
        assertEquals("b", o2.prop2)
    }
    @Test
    fun `applyToObject skip null entries when replacement is ProxyMap`() {
        val o1 = ClassWithNullableNestedDataclassMember(null, "a")
        val map = listOf(
            PropVal(ClassWithNullableNestedDataclassMember::prop1,
                PropVal(ClassWithNullableNestedDataclassMember.Nested::prop1, 1)
            ),
            PropVal(ClassWithNullableNestedDataclassMember::prop2, "b")
        ).toMap()
        val proxy = ProxyMap.fromLensMap<ClassWithNullableNestedDataclassMember>(map)
        val o2 = proxy.applyToObject(o1)
        assertEquals(null, o2.prop1)
        assertEquals("b", o2.prop2)
    }
    @Test
    fun `applyToObject reject incompatible object`() {
        val prop = PropVal(RegularClass::prop1, "test")
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<RegularClass>(map)
        val o1 = ClassWithNullableMember(42.0)
        assertThrows(IllegalArgumentException::class.java) {
            (proxy as ProxyMap<ClassWithNullableMember>).applyToObject(o1)
        }
    }
    @Test
    fun `test applyToObject handle null`() {
        val prop = PropVal(ClassWithNullableMember::prop1, null)
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<ClassWithNullableMember>(map)
        val o1 = ClassWithNullableMember(42.0)
        val o2 = proxy.applyToObject(o1)
        assertEquals(null, o2.prop1)
    }

    @Test
    fun `test DataClass + ProxyMap = applyToObject`() {
        val o1 = RegularClass2("test", 1)
        val pm = ProxyMap.fromLensMap<RegularClass2>(PropVal(RegularClass2::prop2, 2).toList().toMap())
        val o2g = o1 + pm
        val o2x = pm.applyToObject(o1)
        assertEquals(o2x, o2g)
    }

    @Test
    fun `test PM minus PM = lens`() {
        val o1 = RegularClass2("test", 1)
        val o2 = RegularClass2("test", 2)
        val pmMinus = listOf(o2, o1).map { ProxyMap.fromDataclass(it) }.reduce { a, b -> a - b }
        val lensMapExpected = PropVal(RegularClass2::prop2, 2).toList().toMap()
        assertEquals(lensMapExpected, pmMinus)
    }
    @Test
    fun `reject incompatible PM minus PM`() {
        val o1 = RegularClass("a")
        val o2 = RegularClass2("a", 1)
        assertThrows(IllegalArgumentException::class.java) {
            ProxyMap.fromDataclass(o1) - ProxyMap.fromDataclass(o2)
        }
    }
}