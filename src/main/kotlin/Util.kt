package com.moshy

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KType
internal val KType.kClass: KClass<*>
    get() = when (val t = classifier) {
        is KClass<*> -> t
        /* This line will only get triggered if we made an internal mistake unpacking types;
         * thus it should be excluded from coverage analysis.
         * File a bug report if this gets triggered in normal use!
         */
        else -> throw IllegalStateException("Type $t is not a class")
    }

/**
 * Serializing type consisting of a type and its associated serializer.
 * This is a distinct object so it can be used as a cache key, given the cost of reflection.
 */
internal data class SerialType(
    val type: KType,
    val descriptor: SerialDescriptor
) {
    constructor(type: KType): this(type, serializer(type).descriptor)
}

private const val KCLASS_STRING_PREFIX = "class ".length
internal val KClass<*>.className
    // strip the "class " part from KClass<*>.toString() and return the reflected class name.
    get() = this.toString().substring(KCLASS_STRING_PREFIX)
