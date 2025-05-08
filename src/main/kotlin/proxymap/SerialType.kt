package com.moshy.proxymap

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.reflect.KType

/**
 * Serializing type consisting of a type and its associated serializer.
 * This is a distinct object so it can be used as a cache key, given the cost of reflection.
 */
internal data class SerialType(
    val type: KType,
    val descriptor: SerialDescriptor,
    val caseFold: Boolean
) {
    constructor(type: KType, caseFold: Boolean = false)
        : this(type, serializer(type).descriptor, caseFold)
}