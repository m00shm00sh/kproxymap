
package com.moshy.proxymap

import com.moshy.ProxyMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import org.slf4j.LoggerFactory

/** Deserialize a property map to a ProxyMap.
 *
 * Each key has the form `prop1(.propN)`, with lists and `<K,V>` maps having a numerical index.
 */
inline fun <reified T: Any> Map<String, String>.fromPropertyMap(caseFold: Boolean = false): ProxyMap<T> =
    fromPropertyMap(typeOf<T>(), PMSerializer(serializer<T>()), caseFold)

fun <T: Any> Map<String, String>.fromPropertyMap(
    dataType: KType,
    serializer: KSerializer<ProxyMap<T>>,
    caseFold: Boolean
): ProxyMap<T> {
    val propsP = PROPS_PACK_CACHE.getOrPutEntry(SerialType(dataType, caseFold))
    val map = buildMap {
        for ((key_, value) in this@fromPropertyMap) {
            val keySequence = key_.split('.').toMutableList()
            var pp = propsP
            if (caseFold) {
                for (i in 0..<keySequence.size) {
                    val ki = keySequence[i]
                    require(ki.isNotEmpty()) {
                        "empty component in key $key_"
                    }
                    // stop casefold transformation at array since it's opaque to PMSerializer
                    if (ki[0].isDigit())
                        break
                    val propName = pp.propNamesLowercase[ki.lowercase()]
                    if (propName == null) {
                        warnIgnoredMapKeyDuringSerialization(logger, ki)
                        continue
                    }
                    keySequence[i] = propName
                    val propIdx = checkNotNull(pp.propIndicesByName[propName])
                    val propType = pp.propsByIndex[propIdx].returnType

                    // stop casefold transformation at non-dataclass for same reason as array
                    if (!pp.recurseAtIndex[propIdx])
                        break

                    @OptIn(ExperimentalSerializationApi::class)
                    val propDescriptor = pp.descriptor.getElementDescriptor(propIdx)
                    pp = PROPS_PACK_CACHE.getOrPutEntry(SerialType(propType, propDescriptor, true))
                }
            }
            val key = keySequence.joinToString(".")
            if (caseFold && key in this)
                casefoldNameCollision(dataType.kClass, key, key_)
            put(key, value)
        }
    }
    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalSerializationApi::class)
    return Properties.decodeFromStringMap(serializer, map) as ProxyMap<T>
}

private val logger by lazy { LoggerFactory.getLogger("ProxyMap.casefold") }
