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
        fun List<PropVal<*>>.toMap(): Map<String, Any?> = buildMap {
            for (prop in this@toMap) {
                this[prop.name] = when (val v = prop.value) {
                    is List<*> -> {
                        val dType = v.homogenousType
                        if (dType == PropVal::class)
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
fun <V> checkMap(map: Map<String, Any?>, propNames: List<String>, expectedPropVal: V) {
    val actualPropVal = map.getByKeySequence<String, V>(propNames)
    assertEquals(expectedPropVal, actualPropVal)
}

/** Test fixture for prop validation. */
data class MapCheck<V>(
    val propNames: List<String>,
    val expectedPropVal: V,
    val shouldBeMissing: Boolean = false
)  {
    constructor(propName: String, expectedPropVal: V, shouldBeMissing: Boolean = false)
        : this(listOf(propName), expectedPropVal,shouldBeMissing)
    constructor(vararg propName: String, expectedPropVal: V, shouldBeMissing: Boolean = false)
        : this(propName.toList(), expectedPropVal, shouldBeMissing)
    companion object {
        /** Quasi-constructor for PropVal.
         * Walks the [PropVal] to create a list of keys and returns it in [MapCheck] form.
         */
        operator fun <V> invoke(propVal: PropVal<V>, shouldBeMissing: Boolean = false): MapCheck<V> {
            var tailV: V
            val propNames = buildList {
                var pv = propVal
                add(pv.name)
                @Suppress("UNCHECKED_CAST")
                while (pv.value is PropVal<*>) {
                    pv = pv.value as PropVal<V>
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
/** Verify the list of [props] is decoded from [jsonStr], with optional [context]. */
inline fun <reified T: Any> testDeserialization(
    jsonStr: String,
    props: List<MapCheck<*>>,
    context: String? = null,
    specialArgs: SpecialArgs<T> = SpecialArgs()
) {
    val asMap = requireNotNull(deserializeToMap<T>(jsonStr, specialArgs))
    val checkFuncs =
        props.map { (names, expected, shouldBeMissing) ->
            Executable {
                when (shouldBeMissing) {
                    false -> checkMap(asMap, names, expected)
                    true ->
                        assertThrows(NoSuchElementException::class.java) {
                            checkMap(asMap, names, expected)
                        }
                }
            }
        }
    assertAll(context, checkFuncs)
}
/** Verify the list of [props] is decoded from [jsonStr], with optional [context]. */
inline fun <reified T: Any> testDeserialization(
    jsonStr: String,
    vararg props: MapCheck<*>,
    context: String? = null,
    specialArgs: SpecialArgs<T> = SpecialArgs()
) =
    testDeserialization<T>(jsonStr, props.toList(), context, specialArgs)
/** Verify the list of [props] is decoded from [json], with optional [context]. */
inline fun <reified T: Any> testDeserialization(
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
    val pMap = map?.let { ProxyMap<T>(kClass = null, it) }
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
