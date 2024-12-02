package com.moshy

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.*
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.util.TreeMap
import java.util.concurrent.ConcurrentMap
import kotlin.reflect.*
import kotlin.reflect.full.*

@Suppress("UNCHECKED_CAST")
private fun <T> KSerializer<*>.cast() = this as KSerializer<T>

/** This is a dummy data class for the serializer to create child ProxyMaps. */
@Serializable
private data class Dummy(val data: Nothing)

/**
 * Holds mappings and special objects used by the actual serialization functions.
 */
/* If we have an autogenerated data class with >245 parameters, copy() will be non-functional due to JVM method arg
 * limitations. Check during serializer generation that this problem will not occur.
 * NOTE: this is a JVM limitation. Once this code uses KSP and therefore can work with multiplatform, this shouldn't be
 *       an issue.
 */
private const val KT_JVM_ALL_PARAMS_OPTIONAL_ARG_COUNT_LIMIT = 245
internal class PropsPack private constructor(
    val descriptor: SerialDescriptor,
    val propsByIndex: List<KProperty1<*, *>>,
    val propIndicesByName: Map<String, Int>,
    val propsSerializers: List<KSerializer<Any?>>,
    val recurseAtIndex: List<Boolean>,
) {
    companion object {
        fun fromSerialType(sType: SerialType): PropsPack {
            val kClass = sType.type.kClass
            val isGeneric = sType.type.arguments.isNotEmpty()
            if (isGeneric)
                throw IllegalArgumentException("class ${sType.type.kClass.className} is generic; support is missing")
            require(kClass.isData) {
                "class ${kClass.className} is not a data class"
            }
            /* Non-private var properties declared in the data class body are serializable but are not available as
             * parameters to copy. This is a problem because our goal is to use copy() for lensed updates instead of
             * invoking the constructor to set the body-declared var's (which is what the underlying deserializer does).
             * To emit a warning that a serializable property is not copy method accessible as necessary, get a list of
             * parameter names.
             */
            val copyAccessiblePropertyNames =
                kClass.memberFunctions.single { it.name == "copy" }.parameters.mapNotNull { it.name }.toSet()

            val indexToProperty = mutableListOf<KProperty1<*, *>>()
            val nameToIndex = mutableMapOf<String, Int>()
            val indexToSerializer = mutableListOf<KSerializer<Any?>>()
            val recursionNeeded = mutableListOf<Boolean>()
            // make a mutable list of descriptors since we can't create our own ClassSerialDescriptorBuilder instance
            val descriptors = mutableListOf<Triple<String, SerialDescriptor, List<Annotation>>>()
            var serialIndex = 0
            for (prop in kClass.memberProperties) {
                val propName = prop.name
                // the kx-serialization compiler plugin makes sure that all prop serial names per kClass are unique
                val propSerialName = prop.findAnnotation<SerialName>()?.value ?: propName
                // use class serial descriptor to query serializability
                @OptIn(ExperimentalSerializationApi::class)
                val sTypeSerialIndex = sType.descriptor.getElementIndex(propSerialName)
                if (sTypeSerialIndex == CompositeDecoder.UNKNOWN_NAME) {
                    if (propName in copyAccessiblePropertyNames) {
                        warnNonSerializableParameterInCopyMethod(kClass, propName)
                    }
                    continue
                }
                if (propName !in copyAccessiblePropertyNames) {
                    warnParameterInaccessibleInCopyMethod(kClass, propName)
                    continue
                }
                val propType = prop.returnType
                /* Strip the nullability off the property's type so we can get the serializer for its underlying
                 * type; this will allow us to attach our proxy serializer to the type then add the nullability
                 * handler over it.
                 */
                val propTypeDenullabled = propType.withNullability(false)
                val isDataclass = propTypeDenullabled.kClass.isData
                val isNullable = propType.isMarkedNullable
                val isNullableDataclass = isNullable && isDataclass
                // we only care about re-wrapping nullability for data classes
                val underlyingType =
                    if (isNullableDataclass)
                        propTypeDenullabled
                    else
                        propType
                indexToProperty += prop
                nameToIndex[prop.name] = serialIndex
                recursionNeeded += isDataclass
                indexToSerializer += elementSerializer(prop, underlyingType, isDataclass, isNullableDataclass).cast()
                @OptIn(ExperimentalSerializationApi::class)
                descriptors += Triple(
                        propSerialName, sType.descriptor.getElementDescriptor(sTypeSerialIndex), prop.annotations
                )
                ++serialIndex
                check (serialIndex <= KT_JVM_ALL_PARAMS_OPTIONAL_ARG_COUNT_LIMIT) {
                    val limit = KT_JVM_ALL_PARAMS_OPTIONAL_ARG_COUNT_LIMIT
                    "Encountered data class with >$limit eligible properties; this should be impossible on JVM"
                }
            }

            val proxyDescriptor = buildClassSerialDescriptor("ProxyMap<${kClass.className}>") {
                for ((name, descriptor, annotations) in descriptors)
                    element(name, descriptor, annotations, isOptional = true)
            }

            return PropsPack(
                proxyDescriptor,
                indexToProperty, nameToIndex, indexToSerializer,
                recursionNeeded
            )
        }
    }
}

private fun elementSerializer(
    prop: KProperty1<*, *>,
    underlyingType: KType,
    isDataclass: Boolean,
    isNullableDataclass: Boolean
): KSerializer<out Any?> {
    /* @Serializable(with=X) requires X to be a compile-time KClass so use
     *      ?.objectInstance to unpack a runtime-usable object
     */
    val propActualSerializer =
        prop.findAnnotation<Serializable>()?.with?.objectInstance
            ?: serializer(underlyingType)

    if (isDataclass) {
        // unwrap the nullability then rewrap it if necessary
        val dataclassDeserializer = ProxyMapSerializer(propActualSerializer.cast())
        if (isNullableDataclass)
            return dataclassDeserializer.nullable
        return dataclassDeserializer
    }
    return propActualSerializer
}

/**
 * Actual serializer for [propsP].
 */
private fun serializeProxyMap(propsP: PropsPack, encoder: Encoder, value: Map<String, Any?>) {
    val descriptor = propsP.descriptor
    val propsIndices = propsP.propIndicesByName
    val propsSerializers = propsP.propsSerializers

    /* Extract the keys to serialize from [value] and produce a map in serialization index order so that
     * we encode the elements in the same order the proxied class does.
     * Use TreeMap().apply because buildMap produces a hash map instead of a sorted map and we need ordering
     * by key value.
     */
    val toSerialize = TreeMap<Int, Any?>().apply {
        for ((propName, propValue) in value) {
            propsIndices[propName]?.let {
                this[it] = propValue
            } ?: warnIgnoredMapKeyDuringSerialization(propName)
        }
    }

    encoder.encodeStructure(descriptor) {
        for ((serialIndex, elementValue) in toSerialize) {
            val elem = when {
                elementValue == null -> null
                propsP.recurseAtIndex[serialIndex] ->
                    @Suppress("UNCHECKED_CAST")
                    ProxyMap<Dummy>(Dummy::class, elementValue as Map<String, Any?>)
                else -> elementValue
            }
            encodeSerializableElement(descriptor, serialIndex, propsSerializers[serialIndex], elem)
        }
    }
}

/**
 * Actual deserializer for [propsP].
 */
private fun deserializeProxyMap(propsP: PropsPack, decoder: Decoder): Map<String, Any?> {
    val descriptor = propsP.descriptor
    val propsByIndex = propsP.propsByIndex
    val propsSerializers = propsP.propsSerializers

    return buildMap {
        decoder.decodeStructure(descriptor) {
            /* We do not support decodeSequentially because it only makes sense for ordered full objects, not
             * named lens partial objects.
             */
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    else -> {
                        val serializer = propsSerializers[index]
                        val deserialized = decoder.decodeSerializableValue(serializer)
                        put(propsByIndex[index].name, deserialized)
                    }
                }
            }
        }
    }
}

internal val PROPS_PACK_CACHE = ConcurrentHashMap<SerialType, PropsPack>()
internal fun ConcurrentMap<SerialType, PropsPack>.getOrPutEntry(sType: SerialType) =
    getOrPut(sType) { PropsPack.fromSerialType(sType) }

/**
 * Provides a serializer to encode/decode a Map<String, Any?> as if it were a @Serializable (data) class C.
 */
class ProxyMapSerializer<T: Any>(classSerializer: KSerializer<T>): KSerializer<ProxyMap<T>> {
    // use reflection to find the run-time type of T
    // TODO: is this cacheable? Is the KSerializer a usable caching key or do we need something else?
    private val type = classSerializer::class.memberFunctions.single { it.name == "deserialize" }.returnType
        .also {
            require(it.kClass.isData) {
                "(de)serializer synthesis only supported for data classes"
            }
        }
    private val sType = SerialType(type, classSerializer.descriptor)
    private val propsP = PROPS_PACK_CACHE.getOrPutEntry(sType)
    override val descriptor = propsP.descriptor

    val serializableMemberPropertyCount = propsP.propsByIndex.size
    override fun deserialize(decoder: Decoder): ProxyMap<T> =
        ProxyMap(type.kClass, deserializeProxyMap(propsP, decoder))
    override fun serialize(encoder: Encoder, value: ProxyMap<T>) =
        serializeProxyMap(propsP, encoder, value)
}