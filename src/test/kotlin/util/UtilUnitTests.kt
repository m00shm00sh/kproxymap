package com.moshy.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/*
 * Unit tests for JsonExpr and FunctionsKt.
 * Because this code is under src/test/, IntelliJ coverage analysis is not available, so use
 * bugs to find missing coverage.
 */

class JsonExprUnitTests {
    @Test
    fun `string encoding`() {
        // Use Dingbats block emoji to avoid issues with representing non-BMP unicode characters
        val s0 = JsonExpr.Str("✅")
        assertEquals(quoteStr("✅"), s0.toString(), "utf8 without escapes")
        // there are seven \\X symbols to escape: \b \f[=u000C] \n \r \t \\ "
        val s1 = JsonExpr.Str("A\bB\u000cC\nD\rE\tF\\G\"H")
        assertEquals(quoteStr("A\\bB\\fC\\nD\\rE\\tF\\\\G\\\"H"), s1.toString(), "\\X escaped symbols")
        /* [\u0000 - \u001F] shall be escaped; excluding the \\X symbols, this leaves us
         * [\u0000 - \u0007], [\u000b], [\u000e - \u001f]
         */
        val s2 = JsonExpr.Str("e\u001Fg")
        val hex1F = when (HEX.last()) {
            'F' -> "\\u001F"
            'f' -> "\\u001f"
            else -> error("HEX is not a valid string")
        }
        assertEquals(quoteStr("e${hex1F}g"), s2.toString(), "\\uXXXX escaped symbols")
    }

    @Test
    fun `primitive printing`() {
        val n0 = JsonExpr.Num(1)
        assertEquals("1", n0.toString(), "positive int")
        // we choose a floating point number that should not be at risk of rounding
        val n1 = JsonExpr.Num(-4.25)
        assertEquals("-4.25", n1.toString(), "negative float")
        val b0 = JsonExpr.Bool(false)
        assertEquals("false", b0.toString(), "boolean")
        val p0 = JsonExpr.Null()
        assertEquals("null", p0.toString(), "null")
    }

    @Test
    fun `auto type-handling of primitives`() {
        val str = JsonExpr("str")
        assertTrue(str is JsonExpr.Str, "Str deduced")
        val num = JsonExpr(1)
        assertTrue(num is JsonExpr.Num, "Num deduced")
        val bool = JsonExpr(true)
        assertTrue(bool is JsonExpr.Bool, "Bool deduced")
        val primitive = JsonExpr(null)
        assertTrue(primitive is JsonExpr.Null, "Null deduced")
        val passThrough = JsonExpr(primitive)
        assertTrue(passThrough == primitive, "passthrough succeeded")
        // throw an anonymous object at JsonExpr.invoke(Any?) to test the else case of unknown type -> string
        val objAsStr = JsonExpr(object {})
        assertTrue(objAsStr is JsonExpr.Str, "unregisterable type deduced as Str")
    }

    @Test
    fun `collection printing`() {
        val c0 = JsonExpr.Coll(JsonExpr(true), JsonExpr("①"))
        assertEquals("[true,\"①\"]", c0.toString())
    }

    @Test
    fun `object printing`() {
        val o0 = JsonExpr.Obj("问" to JsonExpr(42))
        assertEquals("{\"问\":42}", o0.toString())
    }

    @Test
    fun `auto type-handling structures`() {
        val c0 = JsonExpr(null, "")
        assertTrue(c0 is JsonExpr.Coll)
        val o0 = JsonExpr("test" to false)
        assert(o0 is JsonExpr.Obj)
    }
    @Test
    fun `auto type-handling of registered types`() {
        class C
        JsonExpr.registerType(C::class) { _ -> JsonExpr(null) }
        JsonExpr.registerTypeForHomogenousCollection(C::class) { _ -> JsonExpr(42) }
        val objAsNull = JsonExpr(C())
        val objAsInt = JsonExpr(listOf(C()))
        assertTrue(objAsNull is JsonExpr.Null, "registered (<local type>)->JsonExpr(.Null)")
        assertTrue(objAsInt is JsonExpr.Num, "registered (Collection<<local type>>)->JsonExpr(.Num)")
    }
}

class FuncUnitTests {
    @Test
    fun `jsonExpr invoke propVal`() {
        data class C(val a: Int)
        val p = PropVal(C::a, 1)
        val pJson = JsonExpr(p)
        assertTrue(pJson is JsonExpr.Obj, "registered (PropVal)->JsonExpr(.Obj)")
    }
}