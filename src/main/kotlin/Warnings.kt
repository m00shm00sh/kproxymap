package com.moshy

import kotlin.reflect.KClass

/* Not exactly a reliable mechanism when the full test suite is ran, but it'll stay until the logging refactor is done.
 */
internal var errorStream = System.err

// FIXME: this should be refactored to slf4j calls and use of an internal errorCallbackTriggered
internal fun warnParameterInaccessibleInCopyMethod(kClass: KClass<*>, propName: String) {
    val className = kClass.className
    errorStream.println(
        "w: Serializable property $propName of class $className is not accessible from copy method"
    )
}
internal fun warnNonSerializableParameterInCopyMethod(kClass: KClass<*>, propName: String) {
    val className = kClass.className
    errorStream.println(
        "w: Property $propName of class $className is a param of copy method but not serializable"
    )
}
internal fun warnIgnoredMapKeyDuringSerialization(keyName: String) {
    errorStream.println(
        "w: Ignored key \"$keyName\" because it is not a viable serialization candidate"
    )
}