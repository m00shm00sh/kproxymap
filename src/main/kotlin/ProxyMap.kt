package com.moshy

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.util.SortedMap
import kotlin.reflect.*
import kotlin.reflect.full.*

private val PropsPack.serializablePropertyNames: Set<String>
    get() = propIndicesByName.keys

/** Originally a tag type for (de)serialization as update map for data class [T] but now generalized
 *  for general lensing. Immutable and presents a `Map<String, Any?>` view.
 *
 * The following ways of producing instances are supported:
 * 1. [fromDataclass]`(obj: T)`
 * 2. [fromDataclass]`(obj, kType)`
 * 3. [fromLensMap]`<T>(map: Map<String, Any?>)`
 * 4. [fromLensMap]`<T>(vararg items: Pair<String, Any?>)`
 * 5. [fromLensMap]`<T>(map, kType)`
 * 6. `serializerEngine.decodeFromXxx<ProxyMap<T>>(message)` (e.g. `Json.decodeFromString`)
 * 7. `pm1 - pm2`, where `pm1` and `pm2` are both [ProxyMap]`<T>` (this form is useful for creating lenses)
 *
 * Applying the lensing onto an object is done by calling [applyToObject] and supplying an object to return an
 * updated copy of. This can be done out of the box with [ProxyMap.plus].
 * Creating a lens can be done with existing ProxyMaps by using [ProxyMap.minus]
 *
 * @throws IllegalArgumentException if a generic class is supplied
 * @throws IllegalArgumentException if a data class has a [ProxyMap] member property
 */
@Serializable(with = ProxyMapSerializer::class)
class ProxyMap<T: Any>
internal constructor (
    private val kClass: KClass<*>,
    private val map: Map<String, Any?>
): Map<String, Any?> by map {
    private val propsP = PROPS_PACK_CACHE.getOrPutEntry(SerialType(kClass.createType()))
    private val serializableNames = propsP.serializablePropertyNames
    private val primaryCtor =
        requireNotNull(kClass.primaryConstructor) { "no primary constructor" }
    // TODO: is this cacheable? Is the KClass a usable caching key or do we need something else?
    private val copyMethod =
        requireNotNull(kClass.memberFunctions.singleOrNull() { it.name == "copy" }) { "no copy method" }

    /** Recursive [applyToObject]. Assumes [kClass] != null because it relies on that for all type safety. */
    private fun applyToObjectImpl(data: Any): Any {
        val dataClass = data::class
        require(kClass == dataClass) {
            "attempted to apply ProxyMap for ${kClass.className} onto instance of ${dataClass.className}"
        }
        val argMap = copyMethod.parameters.mapNotNull {
            when {
                it.kind == KParameter.Kind.INSTANCE -> it to data
                it.kind == KParameter.Kind.VALUE && it.name in serializableNames -> {
                    val lensVal = this[it.name]
                    val prop = propsP.propsByIndex[propsP.propIndicesByName[it.name] ?: -1]
                    // ignore unwanted null
                    if (lensVal == null) {
                        if (it.name in this) {
                            return@mapNotNull it to null
                        } else
                            return@mapNotNull null
                    }
                    // recurse when the property is a data class having a non-null value
                    val propType = prop.returnType
                    val propClass = propType.kClass
                    val isDataclass = propClass.isData
                    val propOldValue = prop.getter.call(data)
                    /* If object is not an active data class, a ProxyMap (that was generated from recursive lens
                     * parsing) makes no sense here. Omit the argument from callBy.
                     */
                    if (propOldValue == null && propClass != ProxyMap::class && lensVal is ProxyMap<*>) {
                        // TODO: emit warning about [it.name] being ignored due to !dataClass
                        return@mapNotNull null
                    }
                    val value = when {
                        isDataclass && propOldValue != null ->
                            requireNotNull(lensVal as? ProxyMap<*>).applyToObjectImpl(propOldValue)
                        else -> lensVal
                    }
                    return@mapNotNull it to value
                }
                else ->
                    /* Non-serializable or unsupported KParameter Kind
                     * This happens when there's a @Transient property in the copy method params.
                     * This argument is ignored so there's no need to coverage test its handling.
                     */
                    null
            }
        }.toMap()
        return reflectionCallBy(copyMethod, argMap)
    }
    /** Apply the lensing contained in this [ProxyMap] to an object [data] having equal class, recursively. */
    @Suppress("UNCHECKED_CAST")
    fun applyToObject(data: T): T =
        applyToObjectImpl(data) as T

    /** Recursive [createObject]. */
    private fun createObjectImpl(): Any {
        val argMap = primaryCtor.parameters.mapNotNull {
            when {
                it.kind == KParameter.Kind.VALUE && it.name in serializableNames -> {
                    val lensVal = this[it.name]
                    val prop = propsP.propsByIndex[propsP.propIndicesByName[it.name] ?: -1]
                    // ignore unwanted null
                    if (lensVal == null) {
                        if (it.name in this) {
                            return@mapNotNull it to null
                        } else
                            return@mapNotNull null
                    }
                    // recurse when the property is a data class having a non-null value
                    val propType = prop.returnType
                    val propClass = propType.kClass
                    val isDataclass = propClass.isData
                    /* A ProxyMap object makes no sense here if it's not proxying a sub-object or isn't an
                     * expressly desired ProxyMap.
                     * Unsure if this condition is even reachable.
                     */
                    if (!isDataclass && propClass != ProxyMap::class && lensVal is ProxyMap<*>) {
                        throw IllegalArgumentException("key ${it.name}: unexpected ProxyMap")
                    }
                    val value = when {
                        isDataclass -> requireNotNull(lensVal as? ProxyMap<*>).createObjectImpl()
                        else -> lensVal
                    }
                    return@mapNotNull it to value
                }
                else ->
                    /* Non-serializable or unsupported KParameter Kind
                     * This happens when there's a @Transient property in the copy method params.
                     * This argument is ignored so there's no need to coverage test its handling.
                     */
                    null
            }
        }.toMap()
        val missing = primaryCtor.parameters.mapNotNull { p -> p.name.takeUnless { p.isOptional } } - this.keys
        if (missing.isNotEmpty()) {
            val numMissing = missing.size
            throw IllegalArgumentException(
                "$numMissing required parameter(s) missing: ${missing.joinToString(", ")}"
            )
        }
        return reflectionCallBy(primaryCtor, argMap)
    }
    /** Create a new object using the values in this [ProxyMap]. */
    @Suppress("UNCHECKED_CAST")
    fun createObject(): T =
        createObjectImpl() as T

    companion object {
        /** Create a ProxyMap from a given object and its applicable type information.
         *
         * This can be useful for creating map objects used to query differences.
         */
        fun <T_: Any> fromDataclass(data: T_, dataType: KType): ProxyMap<T_> {
            checkType(dataType)
            val propsP = PROPS_PACK_CACHE.getOrPutEntry(SerialType(dataType))
            val serializableNames = propsP.serializablePropertyNames
            return buildMap {
                for (prop in data::class.memberProperties) {
                    val name = prop.name
                    val propValue = prop.getter.call(data)
                    // recurse when the property is a data class having a non-null value
                    val isDataclass = prop.returnType.kClass.isData
                    val value = when {
                        propValue != null && isDataclass -> fromDataclass(propValue, prop.returnType)
                        else -> propValue
                    }
                    if (name in serializableNames)
                        this[name] = value
                    else
                        warnIgnoredMapKeyDuringSerialization(logger, name)
                }
            }.let { ProxyMap(dataType.kClass, it) }
        }
        /** Create a [ProxyMap] from a given object.
         *
         * This can be useful for creating map objects used to query differences.
         */
        inline fun <reified T_: Any> fromDataclass(data: T_) =
            fromDataclass(data, typeOf<T_>())

        /** Create a [ProxyMap] given an update lens represented as a [map] and the applicable type information.
         *  The caller is expected to do an unchecked cast from `ProxyMap<*>` to the appropriate proxied type.
         */
        fun fromLensMap(data: Map<String, Any?>, dataType: KType): ProxyMap<*> {
            checkType(dataType)
            val kClass = dataType.kClass
            val propsP = PROPS_PACK_CACHE.getOrPutEntry(SerialType(dataType))
            val serializableNames = propsP.serializablePropertyNames
            return buildMap {
                for ((key, value) in data) {
                    if (key !in serializableNames) {
                        warnIgnoredMapKeyDuringSerialization(logger, key)
                        continue
                    }
                    /* Here, it doesn't matter if we get IndexOutOfBoundsException or IllegalStateException;
                     * either means the continue a couple of lines above didn't trigger.
                     */
                    val propType = propsP.propsByIndex[propsP.propIndicesByName[key] ?: -1].returnType
                    val propClass = propType.kClass
                    if (value == null && !propType.isMarkedNullable)
                        throw IllegalArgumentException("key $key: null value for non-null type $propType")
                    // ProxyMap (delegately) implements map so we need to take some care in the recursion check
                    if (propClass.isData && value is Map<*, *> && value !is ProxyMap<*>) {
                        /* The type argument T'' gets erased at run-time anyway and projections are disallowed
                         * so use Any as a dummy.
                         */
                        @Suppress("UNCHECKED_CAST")
                        this[key] = fromLensMap(value as Map<String, Any?>, propType)
                        continue
                    }
                    else if (value is ProxyMap<*>) {
                        if (value.kClass != propClass)
                            throw IllegalArgumentException("key $key: incompatible ProxyMap: <$propClass>")
                    // do instanceof-based type check; generics (if any) should not be in the serializable name set
                    } else if (value != null && !propClass.isInstance(value)) {
                        throw IllegalArgumentException(
                            "key $key: expected class: ${propClass.className}; got class: ${value::class.className}"
                        )
                    }
                    this[key] = value
                }
            }.let { ProxyMap<Any>(kClass, it) }
        }
        /** Create a [ProxyMap] given an update lens represented as a [map] and type [T_]. */
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T_: Any> fromLensMap(data: Map<String, Any?>): ProxyMap<T_> =
            fromLensMap(data, typeOf<T_>()) as ProxyMap<T_>
        /** Create a [ProxyMap] given an update lens represented as map [items] and type [T_]. */
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T_: Any> fromLensMap(vararg items: Pair<String, Any?>): ProxyMap<T_> =
            fromLensMap(mapOf(*items), typeOf<T_>()) as ProxyMap<T_>

        /** Pseudo-constructor for data class input.
         * @see fromDataclass
         */
        inline operator fun <reified T_: Any> invoke(data: T_): ProxyMap<T_> =
            fromDataclass(data)
        /** Pseudo-constructor for map and <type> input.
         * @see fromLensMap
         */
        inline operator fun <reified T_: Any> invoke(data: Map<String, Any?> = emptyMap()): ProxyMap<T_> =
            fromLensMap<T_>(data)
        /** Pseudo-constructor for map items and <type> input.
         * @see fromLensMap
         */
        inline operator fun <reified T_: Any> invoke(vararg items: Pair<String, Any?>): ProxyMap<T_> =
            fromLensMap<T_>(*items)

        private fun checkType(type: KType) {
            val isGeneric = type.arguments.isNotEmpty()
            val kClass = type.kClass
            require(!isGeneric) {
                "class ${kClass.className} is generic; support is missing"
            }
            require(kClass.isData) {
                "a non-data class ${kClass.className} was supplied"
            }
            // TODO: is it worth being able to downgrade this from error to warning?
            for (prop in kClass.memberProperties) {
                require(prop.returnType.kClass != ProxyMap::class) {
                    "property ${prop.name} has disallowed type ProxyMap"
                }
            }
        }

        private val logger by lazy { LoggerFactory.getLogger("ProxyMap") }
    }

    // overload toSortedMap to use property declaration order instead of Comparator<String> natural order
    // TODO: there is overhead for primaryCtor, copyMethod, propertyNames; is it worth creating another CHM
    //       to hold those values?
    fun toSortedMap() =
        if (map is SortedMap<String, *>) this
        else ProxyMap(kClass, sortedMap())

    private fun sortedMap() =
        if (map is SortedMap<String, *>) map
        else map.toSortedMap { a, b -> propsP.propIndicesByName[a]!! - propsP.propIndicesByName[b]!! }


    override fun toString() = "ProxyMap<${kClass.className}>" + sortedMap().toString()

    /** Override of [Map]`.plus` to preserve `ProxyMap<T>` type invariants.
     *
     * @throws IllegalArgumentException if ProxyMaps aren't compatible
     */
    /* Use generic to match arbitrary ProxyMap plus so that Map<String, Any?>.plus(other: Map<String, Any?>)
     * doesn't get matched as a fallback.
     */
    operator fun <U: Any> plus(other: ProxyMap<U>): ProxyMap<T> {
        // TODO: this is excessively strict; can we relax this constraint so that one side can be
        //       a subset of the other?
        require(kClass == other.kClass) {
            "incompatible KClass: <${kClass.className}> and <${other.kClass.className}>"
        }
        // Kotlin's stdlib converts this to HashMap($receiver).apply { putAll(other) },
        // which reduces to HashMap().apply { putAll($receiver); putAll(other) };
        // presumably this is faster than buildMap { putAll($receiver); putAll(other) }
        val map = map + other.map
        return ProxyMap(kClass, map)
    }

    /**
     * Get difference between two [ProxyMap]s.
     * Here, "difference" includes not only added values, but changed ones, too, so that the resulting ProxyMap is
     * a lens that transforms the object that this came from into something matching the output.
     *
     * @throws IllegalArgumentException if ProxyMaps aren't compatible
     */
    /* Use generic to match arbitrary ProxyMap minus so that Map<String, Any?>.minus(other: Map<String, Any?>)
     * doesn't get matched as a fallback.
     */
    operator fun <U: Any> minus(other: ProxyMap<U>): ProxyMap<T> {
        // TODO: this is excessively strict; can we relax this constraint so that one side can be
        //       a subset of the other?
        require(kClass == other.kClass) {
            "incompatible KClass: <${kClass.className}> and <${other.kClass.className}>"
        }

        val map = buildMap {
            for ((key, value) in map) {
                if (value != other.map[key] || key !in other.map)
                    put(key, value)
            }
        }
        return ProxyMap(kClass, map)
    }

    /** Type checked cast from erased to actual type. */
    @Suppress("UNCHECKED_CAST")
    infix fun <U: Any> castTo(clazz: KClass<U>): ProxyMap<U> =
        if (kClass == clazz) this as ProxyMap<U>
        else throw ClassCastException("Cannot cast ProxyMap due to incompatible type")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProxyMap<*>) return false

        if (kClass != other.kClass) return false
        if (map != other.map) return false

        return true
    }

    override fun hashCode(): Int {
        var result = kClass.hashCode()
        result = 31 * result + map.hashCode()
        return result
    }
}

/** Apply [lens] onto a receiver and return the transformed object. */
operator fun <T: Any> T.plus(lens: ProxyMap<T>): T = lens.applyToObject(this)

/** Call [f] with [argMap] and propagate any thrown exceptions. */
private fun reflectionCallBy(f: KFunction<*>, argMap: Map<KParameter, Any?>) =
    try {
        checkNotNull(f.callBy(argMap))
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }