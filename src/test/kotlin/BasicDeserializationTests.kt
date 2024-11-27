package com.moshy

import com.moshy.util.*
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Define a "basic" test as one that tests non-recursive cases, where a recursive case is a property D1.P of type D2,
 * where D1 and D2 are data classes.
 */
class BasicDeserializationTests {
    @Test
    fun `handle basic`() {
        val p1 = PropVal(RegularClass::prop1, "test")
        val json = JsonExpr(p1)
        testDeserialization<RegularClass>(json, MapCheck(p1))
    }

    @Test
    fun `handle nullable`() {
        val json = JsonExpr(null)
        deserializeToMap<RegularClass>(json, specialArgs = SpecialArgs(expectNullable = true))
    }

    @Test
    fun `handle property with @Serializer`() {
        val p1 = PropVal(ClassWithPropertySerializer::prop1, 9)
        val json = JsonExpr(p1)
        testDeserialization<ClassWithPropertySerializer>(
            json,
            MapCheck(p1.name, CustomPropertySerializer.VALUE)
        )
    }

    @Test
    fun `handle extra keys`() {
        val invalidPropName = "q"
        val json = JsonExpr.Obj(invalidPropName to JsonExpr("a"))
        assertThrows(
            SerializationException::class.java,
            { deserializeToMap<RegularClass>(json) },
            "without ignoring")
        assertDoesNotThrow(
            {
                val map = deserializeToMap<RegularClass>(json,
                    SpecialArgs( builder = { ignoreUnknownKeys = true })
                )
                assertTrue(map!!.isEmpty())
            },
            "with ignoring")
    }
    @Test
    fun `handle extra key (prop declared in body)`() {
        val json = JsonExpr(PropVal(ClassWithBodyDeclaredProperty::prop2, "test"))
        assertThrows(
            SerializationException::class.java,
            { deserializeToMap<ClassWithBodyDeclaredProperty>(json) },
            "without ignoring")
        assertDoesNotThrow(
            {
                val map = deserializeToMap<ClassWithBodyDeclaredProperty>(json,
                    SpecialArgs( builder = { ignoreUnknownKeys = true })
                )
                assertTrue(map!!.isEmpty())
            },
            "with ignoring")
    }

    @Test
    fun `handle nullable property`() {
        val p1 = PropVal(ClassWithNullableMember::prop1, null)
        val json = JsonExpr(p1)
        testDeserialization<ClassWithNullableMember>(json, MapCheck(p1))
    }

    @Test
    fun `handle @SerialName`() {
        val p2 = PropVal(ClassWithSerialNameProperty::prop2, "abc")
        val json = JsonExpr(p2)
        testDeserialization<ClassWithSerialNameProperty>(json, MapCheck(p2))
    }

    /*
     * This tests deserialization using the explicit serializer instance. kx-serialization internally converts
     * decodeFromXxx<T>(data) to decodeFromXxx(serializer(typeOf<T>()), data) but we explicitly test the behavior here.
     */
    @Test
    fun `use as explicit KSerializer`() {
        val kSerializer = ProxyMapSerializer(RegularClass.serializer())
        val p1 = PropVal(RegularClass::prop1, "z")
        val json = JsonExpr(p1)
        testDeserialization<RegularClass>(json, MapCheck(p1), specialArgs = SpecialArgs(serializer = kSerializer))
    }

    @Test
    fun `reject non-data class`() {
        assertThrows(IllegalArgumentException::class.java) {
            testDeserialization<NonData>(JsonExpr(null))
        }
    }

    @Test
    fun `verify 'missing' values are not inserted`() {
        val p1 = PropVal(ClassWithSerialNameProperty::prop1, 42)
        val p2 = PropVal(ClassWithSerialNameProperty::prop2, "abc")
        val json = JsonExpr(p1)
        testDeserialization<ClassWithSerialNameProperty>(json, MapCheck(p2, shouldBeMissing = true))
    }

    @Test
    fun `reject generics`() {
        val p1 = PropVal( Box<Int>::elem, 5)
        val json = JsonExpr(p1)
        assertThrows(IllegalArgumentException::class.java) {
            testDeserialization<Box<Int>>(json, MapCheck(p1))
        }
    }

}