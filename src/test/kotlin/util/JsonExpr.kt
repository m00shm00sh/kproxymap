package com.moshy.util

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

private const val ESCAPE = "\"\\\b\u000C\n\r\t"
private const val ESCAPE_SUBST = "\"\\bfnrt"
// promoted from private to internal because it's used in the unit test(s)
internal const val HEX = "0123456789ABCDEF"
// JSON uses UTF-8 with UTF-16 uXXXX escape sequences; however, only values representable as one byte in UTF-8
// need to be escaped (we ignore UTF-16 surrogate pairs).
private fun hexStr(charVal: Int): String {
    require(charVal in 0..0x7F)
    val a = HEX[(charVal and 0xF0) shr 4]
    val b = HEX[(charVal and 0x0F)]
    return "\\u00$a$b"
}
private fun escape(ch: Char): String {
    when (val escapeIdx = ESCAPE.indexOf(ch)) {
        -1 -> {}
        else -> {
            val subst = ESCAPE_SUBST[escapeIdx]
            return "\\$subst"
        }
    }
    when (val charVal = ch.code) {
        in 0..0x1F -> {
            return hexStr(charVal)
        }
    }
    return ch.toString()
}

// promoted from private to internal because it's used in the unit test(s)
internal fun quoteStr(s: String) = "\"$s\""
private fun encodeStr(strContent: String): String {
    val escaped =
        /* Take the slow path if we have any characters to escape.
         * Yes, this is slow by not tokenizing to each case of an escapable, but we don't really care about
         * performance here, only that a valid JSON is produced.
         */
        if (strContent.any { ESCAPE.indexOf(it) != -1 || it.code in 0..0x1F })
            buildString {
                for (ch in strContent)
                    append(escape(ch))
            }
        else strContent
    return quoteStr(escaped)
}
/**
 * Manual and minimal json serialization.
 */
sealed class JsonExpr {

    /** String printer. Arbitrary objects supported as the result of calling their `toString` will be quoted. */
    class Str(private val strData: String): JsonExpr() {
        constructor(anyData: Any): this(anyData.toString())
        override fun toString()  = encodeStr(strData)
    }

    /* FIXME: to support the unsigned types (UByte, UInt, ULong, UShort),
     *        the class will have to be rewritten as
     *        class Num private constructor(private val n: Comparable<*>): JsonExpr()
     *        with overload constructors for Number and each of the unsigned types
     */
    /** Number printer. Unsigned values not yet supported. */
    class Num(private val numData: Number): JsonExpr() {
        override fun toString() = numData.toString()
    }
    /** Boolean printer. */
    class Bool(private val boolData: Boolean): JsonExpr() {
        override fun toString() = boolData.toString()
    }
    /** Null printer. */
    data object Null: JsonExpr() {
        // this is for visual consistency so we have uniform JsonExpr.Xxx()
        operator fun invoke() = this
        override fun toString() = null.toString()
    }
    /** Collection printer. */
    class Coll(private val collData: Collection<JsonExpr>): JsonExpr() {
        constructor(vararg elements: JsonExpr): this(elements.toList())
        override fun toString(): String =
            collData.joinToString(separator = ",", prefix="[", postfix="]")
    }
    /** Object printer. */
    class Obj(private val objData: Map<String, JsonExpr>): JsonExpr() {
        constructor(vararg elements: Pair<String, JsonExpr>): this(elements.toMap())
        override fun toString(): String =
            objData.entries.joinToString(separator = ",", prefix="{", postfix="}") {
                (k, v) ->
                    encodeStr(k) + ":" + v
                }
    }

    companion object {
        private val typeRegistry = ConcurrentHashMap<KClass<*>, (Any) -> JsonExpr>()
        private val homogenousCollectionTypeRegistry = ConcurrentHashMap<KClass<*>, (Collection<*>) -> JsonExpr>()
        /** Register a type for a custom (T)->[JsonExpr] transformation (default is (T)->JsonExpr.Str).
         *
         * Usage: `JsonExpr.registerType(T::class) { x: T -> ... }`
         */
        @Suppress("UNCHECKED_CAST")
        fun <T: Any> registerType(kClass: KClass<T>, evaluator: (T) -> JsonExpr) {
            // we lose compile time type safety but retain run time type safety as T is associated with the key
            typeRegistry[kClass] = evaluator as (Any) -> JsonExpr
        }
        /** Register a type for a custom (Collection<T>)->[JsonExpr] transformation
         *  (default is (Collection<*>) -> JsonExpr.Coll(map(JsonExpr::invoke))).
         *
         * Usage: `JsonExpr.registerTypeForHomogenousCollection(T::class) { x: Collection<T> -> ... }`
         */
        @Suppress("UNCHECKED_CAST")
        fun <T: Any> registerTypeForHomogenousCollection(kClass: KClass<T>, evaluator: (Collection<T>) -> JsonExpr) {
            // we lose compile time type safety but retain run time type safety as T is associated with the key
            homogenousCollectionTypeRegistry[kClass] = evaluator as (Collection<*>) -> JsonExpr
        }

        /** Automagically choose the [JsonExpr] type for [data] given its run-time type. */
        operator fun invoke(data: Any?): JsonExpr =
            when (data) {
                is JsonExpr -> data
                is String -> Str(data)
                /* FIXME: unsigned type checks would need to go here;
                 *        see comment for `class Num`
                 */
                is Number -> Num(data)
                is Boolean -> Bool(data)
                is Collection<*> -> run {
                    val dType = data.homogenousType
                    if (dType != null)
                        homogenousCollectionTypeRegistry[dType]?.let { return@run it(data) }
                    return@run Coll(data.map(JsonExpr::invoke))
                }
                is Map<*, *> -> {
                    for (k in data.keys)
                        require(k is String)
                    @Suppress("UNCHECKED_CAST")
                    Obj(data.mapValues { (k, v) -> JsonExpr(v) } as Map<String, JsonExpr>)
                }

                else -> run {
                    if (data == null)
                        return@run Null()
                    typeRegistry[data::class]?.let { return@run it(data) }
                    return@run Str(data)
                }
            }
        /** Automagically choose the [JsonExpr] type, recursively, for a list of [elems] given their
         * run-time types. */
        operator fun invoke(vararg elems: Any?) = JsonExpr(elems.toList())
        /** Automagically choose the [JsonExpr] type, recursively, for a list of pairs of [elems] given their
         * run-time types. */
        operator fun invoke(vararg elems: Pair<String, Any?>) = JsonExpr(elems.toMap())

    }
}