package com.moshy

import com.moshy.proxymap.plus
import com.moshy.util.*
import com.moshy.util.PropVal.Companion.toMap
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.assertThrows
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
        assertThrows<IllegalArgumentException> {
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
    fun `fromDataclass warn non-serializable property`() = withLogCheck(logLines) {
        val o1 = ClassWithTransientProperty(42, "a")
        ProxyMap.fromDataclass(o1)
    }
    @Test
    fun `fromDataclass reject generic`() {
        val o1 = Box(5)
        assertThrows<IllegalArgumentException> {
            ProxyMap.fromDataclass(o1)
        }
    }
    @Test
    fun `fromDataclass reject class with ProxyMap member`() {
        val pm1 = ProxyMap.fromDataclass(RegularClass("a"))
        assertThrows<IllegalArgumentException> {
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
        assertThrows<IllegalArgumentException> {
            ProxyMap.fromLensMap<NonData>(map)
        }
    }
    @Test
    fun `fromLensMap reject incompatible value`() {
        val prop: PropVal<*> = PropVal(RegularClass::prop1, 42)
        val inputMap = prop.toList().toMap()
        assertThrows<IllegalArgumentException> {
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
        assertThrows<IllegalArgumentException> {
            ProxyMap.fromLensMap<ClassWithDataclassMember>(map)
        }
    }
    @Test
    fun `fromLensMap reject ProxyMap value for non-dataclass member`() {
        val pm1 = ProxyMap.fromDataclass(RegularClass("a"))
        val map = PropVal(ClassWithPMProperty::pm, pm1).toList().toMap()
        assertThrows<IllegalArgumentException> {
            ProxyMap.fromLensMap<ClassWithPMProperty>(map)
        }
    }
    @Test
    fun `fromLensMap reject undesired null`() {
        val prop: PropVal<*> = PropVal(RegularClass::prop1, null)
        val inputMap = prop.toList().toMap()
        assertThrows<IllegalArgumentException> {
            ProxyMap.fromLensMap<RegularClass>(inputMap)
        }
    }
    @Test
    fun `test fromLensMap variadic`() {
        val prop = PropVal(RegularClass::prop1, "test")
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<RegularClass>("prop1" to "test")
        assertEquals(map, proxy)
    }
    @Test
    fun `test fromLensMap casefold`() {
        val map = PropVal(TestCasefold::theProp, "test").toList().toMap()
        val proxy1 = ProxyMap.fromLensMap<TestCasefold>("theprop" to "test", caseFold = true)
        val proxy2 = ProxyMap.fromLensMap<TestCasefold>("THEPROP" to "test", caseFold = true)
        assertEquals(map, proxy1)
        assertEquals(map, proxy2)
    }
    @Test
    fun `test fromLensMap casefold collision in map`() {
        val exMsg = assertThrows<IllegalArgumentException> {
            ProxyMap.fromLensMap<TestCasefold>(
                "theprop" to "test",
                "THEPROP" to "test", caseFold = true
            )
        }.message ?: ""
        assertTrue(exMsg.matches(Regex("class [a-zA-Z.]+TestCasefold property THEPROP collides .* theProp")))
    }
    @Test
    fun `test fromLensMap casefold collision in class`() {
        val exMsg = assertThrows<IllegalArgumentException> {
            ProxyMap.fromLensMap<TestCasefoldReject>(caseFold = true)
        }.message ?: ""
        assertTrue(exMsg.matches(Regex("class [a-zA-Z.]+TestCasefoldReject property theProp collides .* theprop")))
    }
    @Test
    fun `test fromLensMap casefold in map opt-in`() {
        assertDoesNotThrow {
            ProxyMap.fromLensMap<TestCasefold>(
                "theprop" to "test",
                "THEPROP" to "test"
            )
        }
    }
    @Test
    fun `test fromLensMap casefold in class opt-in`() {
        assertDoesNotThrow {
            ProxyMap.fromLensMap<TestCasefoldReject>()
        }
    }

    @Test
    fun `test pseudo-constructors`() {
        assertAll(
            {
                val prop = PropVal(RegularClass::prop1, "test")
                val map = prop.toList().toMap()
                val proxy = ProxyMap<RegularClass>(map)
                assertEquals(map, proxy, "fromLensMap")
            }, {
                val obj = RegularClass("abc")
                val expMap = PropVal(RegularClass::prop1, "abc").toList().toMap()
                val actualMap = ProxyMap(obj)
                assertEquals(expMap, actualMap, "fromDataclass")
            }, {
                val pm = ProxyMap<RegularClass>()
                assertEquals(emptyMap(), pm, "fromLensMap(empty)")
            }
        )
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
        assertThrows<IllegalArgumentException> {
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
    fun `test createObject`() {
        val prop = PropVal(RegularClass::prop1, "test")
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<RegularClass>(map)
        val obj = proxy.createObject()
        assertEquals("test", obj.prop1)
    }
    @Test
    fun `test createObject recursively`() {
        val map = listOf(
            PropVal(ClassWithDataclassMember::prop1, 1),
            PropVal(ClassWithDataclassMember::prop2,
                PropVal(RegularClass::prop1, "b")
            )
        ).toMap()
        val proxy = ProxyMap.fromLensMap<ClassWithDataclassMember>(map)
        val obj = proxy.createObject()
        assertEquals(1, obj.prop1)
        assertEquals("b", obj.prop2.prop1)
    }
    @Test
    fun `test createObject withOptional`() {
        val prop = PropVal(WithOptional::p2, 1)
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<WithOptional>(map)
        val obj = proxy.createObject()
        assertEquals("a", obj.p1)
    }
    @Test
    fun `createObject reject partial object`() {
        val prop = PropVal(Rejectable::p2, 1)
        val map = prop.toList().toMap()
        val proxy = ProxyMap.fromLensMap<Rejectable>(map)
        val ex = assertThrows<IllegalArgumentException> {
            proxy.createObject()
        }
        assertEquals("1 required parameter(s) missing: p1 abc", ex.message)
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
    fun `test PM plus PM = lens`() {
        val o1 = RegularClass2("test", 1)
        val o2 = RegularClass2("test", 2)
        val pmPlus = listOf(o1, o2).map { ProxyMap.fromDataclass(it) }.reduce { a, b -> a + b }
        val lensMapExpected =
            listOf(
                PropVal(RegularClass2::prop1, "test"),
                PropVal(RegularClass2::prop2, 2)
            ).toMap()
        assertEquals(lensMapExpected, pmPlus)
    }
    @Test
    fun `reject incompatible PM plus PM`() {
        val o1 = RegularClass("a")
        val o2 = RegularClass2("a", 1)
        assertThrows<IllegalArgumentException> {
            ProxyMap.fromDataclass(o1) + ProxyMap.fromDataclass(o2)
        }
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
        assertThrows<IllegalArgumentException> {
            ProxyMap.fromDataclass(o1) - ProxyMap.fromDataclass(o2)
        }
    }

    @Test
    fun `test PM - element`() {
        val o1 = RegularClass2("test", 1)
        val o2 = RegularClass2("test", 2)
        val o3 = RegularClass2("aa", 1)
        val pm = ProxyMap.fromDataclass(o2) + ProxyMap.fromLensMap<RegularClass2>("prop1" to "aa")
        val minus1 = pm - "prop2"
        assertEquals(o3, minus1.applyToObject(o1))
    }

    @Test
    fun `test PM - collection`() {
        val o1 = RegularClass2("test", 1)
        val o2 = RegularClass2("test", 2)
        val o3 = RegularClass2("aa", 1)
        val pm = ProxyMap.fromDataclass(o2) + ProxyMap.fromLensMap<RegularClass2>("prop1" to "aa")
        val minus1 = pm - listOf("prop2")
        assertEquals(o3, minus1.applyToObject(o1))
    }


    @Test
    fun `test cast`() {
        val obj = RegularClass("abc")
        val map = ProxyMap(obj) as ProxyMap<*>
        assertAll(
            {   assertDoesNotThrow {
                    map castTo RegularClass::class
                }
            },
            {   assertThrows<ClassCastException> {
                    map castTo RegularClass2::class
                }
            }
        )
    }

    @Test
    fun `test equality`() {
        val prop = PropVal(RegularClass::prop1, "test")
        val map = prop.toList().toMap()
        val proxy = ProxyMap<RegularClass>(map)
        val proxy2 = ProxyMap<RegularClass>(map)
        Assertions.assertEquals(proxy2, proxy)
    }

    @Test
    fun `test toString order`() {
        val props = listOf(
            PropVal(RegularClass2::prop1, "s"),
            PropVal(RegularClass2::prop2, 1)
        ).toMap()
        val exp = "ProxyMap<com.moshy.RegularClass2>$props"
        val map = ProxyMap<RegularClass2>(props)
        assertEquals(exp, map.toString())
   }

    @Test
    fun `test exception propagation for copy`() {
        val props = listOf(
            PropVal(ThrowsExceptionInInitializer::a, -1)
        ).toMap()
        val map = ProxyMap<ThrowsExceptionInInitializer>(props)
        assertThrows<IllegalArgumentException> {
            map.applyToObject(ThrowsExceptionInInitializer(1))
        }
    }
    @Test
    fun `test exception propagation for ctor`() {
        val props = listOf(
            PropVal(ThrowsExceptionInInitializer::a, -1)
        ).toMap()
        val map = ProxyMap<ThrowsExceptionInInitializer>(props)
        assertThrows<IllegalArgumentException> {
            map.createObject()
        }
    }

    private companion object {
        lateinit var logLines: List<String>

        @BeforeAll
        @JvmStatic
        fun init() {
            logLines = getAppendLog()
        }
    }
}