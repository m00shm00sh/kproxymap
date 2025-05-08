package com.moshy.proxymap

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

private const val KCLASS_STRING_PREFIX = "class ".length
internal val KClass<*>.className
    // strip the "class " part from KClass<*>.toString() and return the reflected class name.
    get() = this.toString().substring(KCLASS_STRING_PREFIX)
