package com.moshy

import com.moshy.util.*
import com.moshy.util.PropVal.Companion.toMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

/**
 * Define a "basic" test as one that tests non-recursive cases, where a recursive case is a property D1.P of type D2,
 * where D1 and D2 are data classes.
 */
class BasicSerializationTests {
    @Test
    fun `handle basic`() {
        val p1 = PropVal(RegularClass::prop1, "test")
        val m1 = p1.toList().toMap()
        val expJson = JsonExpr(p1).toString()
        val actualJson = serializeMapToString<RegularClass>(m1)
        assertEquals(expJson, actualJson)
    }

    @Test
    fun `handle null`() {
        val m1 = null
        val expJson = JsonExpr(null).toString()
        val actualJson = serializeMapToString<RegularClass>(m1,
            specialArgs = SpecialArgs(expectNullable = true))
        assertEquals(expJson, actualJson)
    }

    @Test
    fun `handle multiple`() {
        val p1 = PropVal(ClassWithSerialNameProperty::prop1, 42)
        val p2 = PropVal(ClassWithSerialNameProperty::prop2, "test")
        /* Because we have a property having a @SerialName property, we need to maintain a list of PropVal's so that
        * the json renderer emits the serialized key names but the proxy-map emits the normal property names.
        */
        val pList = listOf(p1, p2)
        val m1 = pList.toMap()
        val expJson = JsonExpr(pList).toString()
        val actualJson = serializeMapToString<ClassWithSerialNameProperty>(m1)
        assertEquals(expJson, actualJson)
    }

    @Test
    fun `handle property with @Serializer`() {
        val p1 = PropVal(ClassWithPropertySerializer::prop1, 9)
        val m1 = p1.toList().toMap()
        val expJson = JsonExpr(p1.copy(value = CustomPropertySerializer.VALUE)).toString()
        val actualJson = serializeMapToString<ClassWithPropertySerializer>(m1)
        assertEquals(expJson, actualJson)
    }

    @Test
    fun `handle nullable property`() {
        val p1 = PropVal(ClassWithNullableMember::prop1, null)
        val m1 = p1.toList().toMap()
        val expJson = JsonExpr(p1).toString()
        val actualJson = serializeMapToString<ClassWithNullableMember>(m1)
        assertEquals(expJson, actualJson)
    }

    /*
     * This tests serialization using the explicit serializer instance. kx-serialization internally converts
     * encodeToXxx<T>(data) to encoderToXXX(serializer(typeOf<T>()), data) but we explicitly test the behavior here.
     */
    @Test
    fun `use as explicit KSerializer`() {
        val kSerializer = PMSerializer(RegularClass.serializer())
        val p1 = PropVal(RegularClass::prop1, "z")
        val m1 = p1.toList().toMap()
        val expJson = JsonExpr(p1).toString()
        val actualJson = serializeMapToString<RegularClass>(m1,
            specialArgs = SpecialArgs(serializer = kSerializer))
        assertEquals(expJson, actualJson)
    }

    @Test
    fun `reject non-data class`() {
        assertThrows<IllegalArgumentException> {
            serializeMapToString<NonData>(emptyMap())
        }
    }

    @Test
    fun `verify 'extra' values are not inserted`() {
        val p1 = PropVal(RegularClass::prop1, "a")
        val p2 = PropVal(ClassWithSerialNameProperty::prop2, "abc")
        val m1 = listOf(p1, p2).toMap()
        val expJson = JsonExpr(p1).toString()
        val actualJson = serializeMapToString<RegularClass>(m1)
        assertEquals(expJson, actualJson)
    }

    // FIXME: determine flip-flop
    @Test
    fun `warn when using data class with body-declared serializable property`() = withLogCheck(logLines) {
        serializeMapToString<ClassWithBodyDeclaredProperty>(emptyMap())
    }

    @Test
    fun `verify non-serializable elements aren't processed and emit warning`() = withLogCheck(logLines) {
        val kSerializer =
                PMSerializer(ClassWithTransientProperty.serializer())
        assertTrue(
            kSerializer.serializableMemberPropertyCount == ClassWithTransientProperty.serializableMemberCount
        )
    }

    @Test
    fun `reject generics`() {
        val p1 = PropVal( Box<Int>::elem, 5)
        val m1 = listOf(p1).toMap()
        assertThrows<IllegalArgumentException> {
            serializeMapToString<Box<Int>>(m1)
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