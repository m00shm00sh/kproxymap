package com.moshy

import com.moshy.proxymap.PROPS_PACK_CACHE
import com.moshy.proxymap.PropsPack
import com.moshy.proxymap.SerialType
import com.moshy.proxymap.getOrPutEntry
import com.moshy.proxymap.kClass
import com.moshy.proxymap.warnIgnoredMapKeyDuringSerialization
import kotlinx.serialization.*
import kotlinx.serialization.encoding.*
import org.slf4j.LoggerFactory
import java.util.TreeMap
import kotlin.reflect.full.*

/** This is a dummy data class for the serializer to create child ProxyMaps. */
@Serializable
private data class Dummy(val data: Nothing)

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
            } ?: warnIgnoredMapKeyDuringSerialization(logger, propName)
        }
    }

    encoder.encodeStructure(descriptor) {
        for ((serialIndex, elementValue) in toSerialize) {
            val elem = when {
                elementValue == null -> null
                propsP.recurseAtIndex[serialIndex] ->
                    @Suppress("UNCHECKED_CAST")
                    (ProxyMap<Dummy>(Dummy::class, elementValue as Map<String, Any?>))
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
                            val deserialized = decodeSerializableElement(descriptor, index,serializer)
                        put(propsByIndex[index].name, deserialized)
                    }
                }
            }
        }
    }
}

/**
 * Provides a serializer to encode/decode a Map<String, Any?> as if it were a @Serializable (data) class C.
 */
class PMSerializer<T: Any>(classSerializer: KSerializer<T>): KSerializer<ProxyMap<T>> {
    // use reflection to find the run-time type of T
    // TODO: is this cacheable? Is the KSerializer a usable caching key or do we need something else?
    private val type = classSerializer::class.memberFunctions.single { it.name == "deserialize" }.returnType
        .also {
            require(it.kClass.isData) {
                "(de)serializer synthesis only supported for data classes"
            }
        }
    private val sType = SerialType(type, classSerializer.descriptor, false)
    private val propsP = PROPS_PACK_CACHE.getOrPutEntry(sType)
    override val descriptor = propsP.descriptor

    val serializableMemberPropertyCount = propsP.propsByIndex.size
    override fun deserialize(decoder: Decoder): ProxyMap<T> =
        ProxyMap(type.kClass, deserializeProxyMap(propsP, decoder))
    override fun serialize(encoder: Encoder, value: ProxyMap<T>) =
        serializeProxyMap(propsP, encoder, value)
}

private val logger by lazy { LoggerFactory.getLogger("ProxyMap.Serializer") }
