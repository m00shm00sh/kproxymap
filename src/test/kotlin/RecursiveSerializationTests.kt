package com.moshy

import com.moshy.util.*
import com.moshy.util.PropVal.Companion.toMap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecursiveSerializationTests {
    @Test
    fun `handle dataclass member`() {
        val p2p1 =
            PropVal(
                ClassWithDataclassMember::prop2,
                PropVal(RegularClass::prop1, "test")
            )
        val m1 = p2p1.toList().toMap()
        val expJson = JsonExpr(p2p1).toString()
        val actualJson = serializeMapToString<ClassWithDataclassMember>(m1)
        assertEquals(expJson, actualJson)
    }

    @Test
    fun `handle nullable dataclass member`() {
        val p1p1 =
            PropVal(
                ClassWithNullableNestedDataclassMember::prop1,
                PropVal(
                    ClassWithNullableNestedDataclassMember.Nested::prop1, 42
                )
            )
        val expJson = JsonExpr(p1p1).toString()
        val actualJson = serializeMapToString<ClassWithNullableNestedDataclassMember>(p1p1.toList().toMap())
        assertEquals(expJson, actualJson)
    }

    @Test
    fun `handle null nullable dataclass member`() {
        val p1p1 =
            PropVal(
                ClassWithNullableNestedDataclassMember::prop1,
                null
            )
        val expJson = JsonExpr(p1p1).toString()
        val actualJson = serializeMapToString<ClassWithNullableNestedDataclassMember>(p1p1.toList().toMap())
        assertEquals(expJson, actualJson)
    }
}