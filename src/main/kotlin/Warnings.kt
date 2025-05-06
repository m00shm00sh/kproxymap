package com.moshy

import org.slf4j.Logger
import kotlin.reflect.KClass

// FIXME: this could use an internal errorCallbackTriggered
internal fun warnParameterInaccessibleInCopyMethod(logger: Logger, kClass: KClass<*>, propName: String) =
    logger.warn("Serializable property {} of class {} is not accessible from copy method",
        propName, kClass.className
    )

internal fun warnNonSerializableParameterInCopyMethod(logger: Logger, kClass: KClass<*>, propName: String) =
    logger.warn("Property {} of class {} is a param of copy method but not serializable",
        propName, kClass.className
    )

internal fun warnIgnoredMapKeyDuringSerialization(logger: Logger, keyName: String) =
    logger.warn("Ignored key \"{}\" because it is not a viable serialization candidate", keyName)

internal fun casefoldNameCollision(kClass: KClass<*>, propName: String, collisionName: String): Nothing {
    val className = kClass.className
    throw IllegalArgumentException(
        "class $className property $propName collides with casefolded property name $collisionName"
    )
}