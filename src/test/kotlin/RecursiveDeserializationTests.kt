package com.moshy

import com.moshy.util.*
import org.junit.jupiter.api.Test

class RecursiveDeserializationTests {
    @Test
    fun `handle dataclass member`() {
        val p2p1 =
            PropVal(
                ClassWithDataclassMember::prop2,
                PropVal(RegularClass::prop1, "test")
            )
        val json = JsonExpr(p2p1)
        testDeserialization<ClassWithDataclassMember>(json, MapCheck(p2p1))
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
        val json = JsonExpr(p1p1)
        testDeserialization<ClassWithNullableNestedDataclassMember>(json, MapCheck(p1p1))
    }

    @Test
    fun `handle null nullable dataclass member`() {
        val p1 =
            PropVal(
                ClassWithNullableNestedDataclassMember::prop1,
                null
            )
        val json = JsonExpr(p1)
        testDeserialization<ClassWithNullableNestedDataclassMember>(json, MapCheck(p1))
    }
}