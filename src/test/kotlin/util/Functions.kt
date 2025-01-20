package com.moshy.util

import com.moshy.ProxyMap
import com.moshy.errorStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.nullable
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable
import java.io.PrintStream
import kotlin.reflect.KClass

internal val Collection<*>.homogenousType
    get() = if (isEmpty()) null else first()?.let { firstV ->
        val firstType = firstV::class
        if (all { it != null && it::class == firstType })
            firstType
        else null

    }

private val KProperty1<*, *>.serialName
    get() = findAnnotation<SerialName>()?.value ?: name

/** Type fixture for associating a property name with value. Typing gets messy when
 *  nested properties (1) are involved so expect to use `PropVal<*>` or `as PropVal<T>`
 *  as needed.
 *  (1) e.g. for `PropVal("x", PropVal("y", "z"))`, we want the terminal `<T>` to be `String` but
 *      that's not feasible to express in Kotlin's type system.
 */
data class PropVal<T>(
    val name: String,
    val serialName: String,
    val value: T,
    val useSerialName: Boolean = false
) {
    constructor(prop: KProperty1<*, T>, value: T)
        : this(prop.name, prop.serialName, value, prop.name != prop.serialName)

    companion object {
        init {
            JsonExpr.registerType(PropVal::class) {
                JsonExpr(it.asSerialPair)
            }
            JsonExpr.registerTypeForHomogenousCollection(PropVal::class) {
                JsonExpr(it.associate { pv -> pv.asSerialPair })
            }
        }
        fun List<PropVal<*>>.toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
            for (prop in this@toMap) {
                this[prop.name] = when (val v = prop.value) {
                    is List<*> -> {
                        val dType = v.homogenousType
                        if (dType == PropVal::class)
                            @Suppress("UNCHECKED_CAST")
                            (v as List<PropVal<*>>).toMap()
                        else
                            v
                    }
                    is PropVal<*> ->
                        listOf(v).toMap()
                    else ->
                        v
                }
            }
        }
    }

    fun toList() = listOf(this)

    val asPair: Pair<String, T>
        get() = name to value
    val asSerialPair: Pair<String, T>
        get() = (if (useSerialName) serialName else name) to value
}

/** Generalize Map<K, V>.operator fun get(K): V? to handle a list of K recursively, so that
 *  m["a"]["b"] == m.getByKeySequence<V>(listOf("a", "b")).
 *  Note: to support wanted null value, if any key is invalid, we throw a [NoSuchElementException]
 *  [keyIndex] is a recursion helper and should not be set by the caller.
 */
@Suppress("UNCHECKED_CAST")
fun <K : Any, V> Map<K, *>.getByKeySequence(keys: List<K>, keyIndex: Int = 0): V? {
    val currentKey = keys[keyIndex]
    this[currentKey]?.let { v ->
        if (keyIndex == keys.size - 1)
            return v as V?
        if (v is Map<*, *>)
            return (v as Map<K, V>).getByKeySequence(keys, keyIndex + 1)
    }
    if (currentKey !in this)
        throw NoSuchElementException("key [$keyIndex]=$currentKey")
    return null
}

/**
 * Check that the key sequence [propNames] exists in [map] via [getByKeySequence] and has value [expectedPropVal].
 * @throws NoSuchElementException if any key in the sequence is missing or invalid
 * @throws AssertionError if the value doesn't match
 */
fun <PropType> checkMap(map: Map<String, Any?>, propNames: List<String>, expectedPropVal: PropType) {
    val actualPropVal = map.getByKeySequence<String, PropType>(propNames)
    assertEquals(expectedPropVal, actualPropVal)
}

/** Test fixture for prop validation. */
data class MapCheck<PropType>(
    val propNames: List<String>,
    val expectedPropVal: PropType,
    val shouldBeMissing: Boolean = false
)  {
    constructor(propName: String, expectedPropVal: PropType, shouldBeMissing: Boolean = false)
        : this(listOf(propName), expectedPropVal,shouldBeMissing)
    constructor(vararg propName: String, expectedPropVal: PropType, shouldBeMissing: Boolean = false)
        : this(propName.toList(), expectedPropVal, shouldBeMissing)
    companion object {
        /** Quasi-constructor for PropVal.
         * Walks the [PropVal] to create a list of keys and returns it in [MapCheck] form.
         */
        operator fun <PropT> invoke(propVal: PropVal<PropT>, shouldBeMissing: Boolean = false): MapCheck<PropT> {
            var tailV: PropT
            val propNames = buildList {
                var pv = propVal
                add(pv.name)
                @Suppress("UNCHECKED_CAST")
                while (pv.value is PropVal<*>) {
                    pv = pv.value as PropVal<PropT>
                    add(pv.name)
                }
                tailV = pv.value
            }
            return MapCheck(propNames, tailV, shouldBeMissing)
        }
    }
}

/**
 * Args for testing special behavior cases of (de)serialization, like custom serializer, nullability, custom config.
 */
data class SpecialArgs<T: Any>(
    val serializer: KSerializer<ProxyMap<T>>? = null,
    val expectNullable: Boolean = false,
    val builder: JsonBuilder.()->Unit = {}
)

/** Create a list of Executables to feed into assertAll for props checking. */
private fun createPropCheckers(props: List<MapCheck<*>>, map: ProxyMap<*>): List<Executable> {
    return props.map { (names, expected, shouldBeMissing) ->
        Executable {
            when (shouldBeMissing) {
                false -> checkMap(map, names, expected)
                true ->
                    assertThrows<NoSuchElementException> {
                        checkMap(map, names, expected)
                    }
            }
        }
    }
}

/** Verify the list of [props] is decoded from [jsonStr], with optional [context]. */
internal inline fun <reified T: Any> testDeserialization(
    jsonStr: String,
    props: List<MapCheck<*>>,
    context: String? = null,
    specialArgs: SpecialArgs<T> = SpecialArgs()
) {
    val asMap = requireNotNull(deserializeToMap<T>(jsonStr, specialArgs))
    val checkFuncs = createPropCheckers(props, asMap)
    assertAll(context, checkFuncs)
}

/** Verify the list of [props] is decoded from [jsonStr], with optional [context]. */
internal inline fun <reified T: Any> testDeserialization(
    jsonStr: String,
    vararg props: MapCheck<*>,
    context: String? = null,
    specialArgs: SpecialArgs<T> = SpecialArgs()
) =
    testDeserialization<T>(jsonStr, props.toList(), context, specialArgs)
/** Verify the list of [props] is decoded from [json], with optional [context]. */
internal inline fun <reified T: Any> testDeserialization(
    json: JsonExpr,
    vararg props: MapCheck<*>,
    context: String? = null,
    specialArgs: SpecialArgs<T> = SpecialArgs()
) =
    testDeserialization<T>(json.toString(), props.toList(), context, specialArgs)

/** Decode [jsonStr] to [ProxyMap], with optional [specialArgs]. */
inline fun <reified T: Any> deserializeToMap(
    jsonStr: String,
    specialArgs: SpecialArgs<T> = SpecialArgs()
): ProxyMap<T>? {
    val json = Json(builderAction = specialArgs.builder)
    return specialArgs.serializer
        /* We could do `serializer ?: serializer(typeOf<ProxyMap<T>(?)>) as KSerializer<ProxyMap<T>(?)>` but that
         * would skip the code path of letting the (de)serializer itself call
         * `serializer(typeOf<ProxyMap<T>>)(.nullable).`
         * FIXME: this logic is duplicated in serializeMapToString; can we remove the redundancy?
         */
        ?.let {
            when (specialArgs.expectNullable) {
                true -> json.decodeFromString(it.nullable, jsonStr)
                false -> json.decodeFromString(it, jsonStr)
            }
        } ?: when (specialArgs.expectNullable) {
            true -> json.decodeFromString<ProxyMap<T>?>(jsonStr)
            false -> json.decodeFromString<ProxyMap<T>>(jsonStr)
            }
}
/** Decode [json] to [ProxyMap], with optional [specialArgs]. */
inline fun <reified T: Any> deserializeToMap(json: JsonExpr, specialArgs: SpecialArgs<T> = SpecialArgs()) =
    deserializeToMap<T>(json.toString(), specialArgs)

/** Serialize [map] to json string representing an object using [T] as the recipe for each key
 * and optional [specialArgs].
 */
internal inline fun <reified T: Any> serializeMapToString(
    map: Map<String, Any?>?,
    specialArgs: SpecialArgs<T> = SpecialArgs()
): String {
    val json = Json(builderAction = specialArgs.builder)
    // this test fixture is for serializing, not lensing; kClass can be null here
    val pMap = map?.let { ProxyMap<T>(kClass = T::class, it) }
    if (!specialArgs.expectNullable)
        requireNotNull(pMap) { "map is null "}
    return specialArgs.serializer
        /* We could do `serializer ?: serializer(typeOf<ProxyMap<T>(?)>) as KSerializer<ProxyMap<T>(?)>` but that
         * would skip the code path of letting the (de)serializer itself call
         * `serializer(typeOf<ProxyMap<T>>)(.nullable).`
         */
        ?.let {
            when (specialArgs.expectNullable) {
                true -> json.encodeToString(it.nullable, pMap)
                false -> json.encodeToString(it, pMap!!)
            }
        }
        ?: when (specialArgs.expectNullable) {
            true -> json.encodeToString<ProxyMap<T>?>(pMap)
            false -> json.encodeToString(pMap!!)
        }
}

internal inline fun interceptErrorStreamTest(block: ()->Unit) {
    var didCallPrintln = false
    val streamInterceptor = object : PrintStream(System.err) {
        override fun println(x: String?) {
            didCallPrintln = true
        }
        override fun println(x: Any?) {
            didCallPrintln = true
        }
    }
    errorStream = streamInterceptor
    block()
    assertTrue(didCallPrintln)
    errorStream = System.err
}

private typealias FailureCallback = (List<String>, Any?, Any?, String) -> Nothing // shall throw
private fun doCallUnequal(
    a: Any?, b: Any?,
    reverseOrder: Boolean, keyChain: List<String>,
    lazyContextMessage: () -> Any,
    onUnequal: FailureCallback
): Nothing {
    val context = lazyContextMessage().toString()
    if (reverseOrder)
        onUnequal(keyChain, b, a, context)
    else
        onUnequal(keyChain, a, b, context)
}

/** Recursive equality checker. This is necessary because equals() with non-equal types is problematic.
 *
 * NOTE: TestNG has `fun <E> assertEquals(a: Set<E>, b: Set<E>)`. It would still be an incorrect match because we need
 *       to recurse to compare .entries() correctly.
 */
/* This is not a method of ProxyMapSerializerKt.ProxyMap<*> because we need to mark *which* value failed
 * in the exception. Parsing toString() output to compare which value failed would introduce unnecessary
 * complexity, especially when we don't care for key *order*.
 */
private fun ProxyMap<*>.equalityChecker(
    /* $receiver = pmapA, */
    mapB: Map<String, Any?>,
    reverseOrder: Boolean = false,
    keyChain: List<String> = listOf(),
    lazyMessage: () -> Any,
    onUnequal: FailureCallback? = null
): Boolean {
    val seen = mutableSetOf<String>()
    for ((key, value) in entries) {
        val mapValue = mapB[key]
        val mapValueExists = mapValue != null || key in mapB
        val contextKey = keyChain + key
        if (value == null && !mapValueExists) {
            onUnequal?.let {
                doCallUnequal(a = null, b = "<MISSING>", reverseOrder, contextKey, lazyMessage, onUnequal)
            }
            return false
        }
        /* Combining these two expressions into ((a !is ProxyMap && a != b) || (a != Map || !a.eq(b)) negatively
         * affects readability as calling eq needs an explicit cast so leave the copy-pasted failure checker
         * invocations alone.
         */
        if (value !is ProxyMap<*>) {
            if (value != mapValue) {
                onUnequal?.let {
                    doCallUnequal(a = value, b = mapValue, reverseOrder, contextKey, lazyMessage, onUnequal)
                }
                return false
            }
        } else {
            if (mapValue !is Map<*, *> ||
                !value.equalityChecker(
                    @Suppress("UNCHECKED_CAST")
                    (mapValue as Map<String, Any?>), reverseOrder, contextKey,
                    lazyMessage, onUnequal
                )
            ) {
                onUnequal?.let {
                    doCallUnequal(a = value, b = mapValue, reverseOrder, contextKey, lazyMessage, onUnequal)
                }
                return false
            }
        }
        seen += key
    }
    val missingKeysLHS = mapB.keys - seen
    missingKeysLHS.firstOrNull()?.let {
        onUnequal?.let { _ ->
            doCallUnequal(a = "<MISSING>", b = mapB[it], reverseOrder, keyChain + it, lazyMessage, onUnequal)
        }
        return false
    }
    return true
}

// Kotlinized version of Junit 5 failure formatter
private fun Any?.toQualifiedString(): String {
    val kClass = this?.let { this::class }
    val hash = hashCode()
    val message = if (this !is KClass<*>) toString() else ""
    return "<${kClass.toString()}@$hash>" + message
}
private fun failUnequal(context: List<String>, a: Any?, b: Any?, message: String): Nothing {
    val keyChain = context.joinToString(separator = ".")
    val prefixMessage = if (message.isNotEmpty()) "$message ==> " else ""
    val reason = "for key sequence $keyChain: got ${a.toQualifiedString()} but expected ${b.toQualifiedString()}"
    throw AssertionError(prefixMessage + reason)
}

/** Overload of Junit 5 assertEquals to correctly compare (expected: [Map], actual: [ProxyMap]). */
fun assertEquals(expectedMap: Map<String, Any?>, actualProxy: ProxyMap<*>, lazyMessage: () -> Any) =
    actualProxy.equalityChecker(
        expectedMap, reverseOrder = true, lazyMessage = lazyMessage,
        onUnequal = ::failUnequal
    )

/** Overload of Junit 5 assertEquals to correctly compare (expected: [Map], actual: [ProxyMap]). */
fun assertEquals(expectedMap: Map<String, Any?>, actualProxy: ProxyMap<*>, message: String = "") {
    assertEquals(expectedMap, actualProxy) { message }
}
/** Overload of Junit 5 assertEquals to correctly compare (expected: [ProxyMap], actual: [Map]). */
fun assertEquals(expectedProxy: ProxyMap<*>, actualMap: Map<String, Any?>, lazyMessage: () -> Any) =
    expectedProxy.equalityChecker(
        actualMap, reverseOrder = false, lazyMessage = lazyMessage,
        onUnequal = ::failUnequal
    )

/** Overload of Junit 5 assertEquals to correctly compare (expected: [ProxyMap], actual: [Map]). */
fun assertEquals(expectedProxy: ProxyMap<*>, actualMap: Map<String, Any?>, message: String = "") =
    assertEquals(expectedProxy, actualMap) { message }
