package com.moshy.proxymap

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1


class Validator<T : Any>
    internal constructor (private val m: Map<String, (Any?) -> Unit>)
    : Map<String, (Any?) -> Unit> by m

/** Wrapper factory for a validator.
 *
 * If we're using a ProxyMap to send to database handling logic that updates based on changed fields,
 * we want to check that the new values are valid without a database round-trip or relying on the cache of
 * old values. This can be achieved by single-variable validation.
 */

fun <T : Any> validator(vararg validators: Pair<KProperty1<T, *>, Function1<*, Unit>>) =
    buildMap(validators.size) {
        validators.forEach { (p, v) ->
            @Suppress("UNCHECKED_CAST")
            put(p.name, v as (Any?) -> Unit)
        }
    }.let { Validator<T>(it) }

internal val VALIDATORS = ConcurrentHashMap<KClass<*>, Map<String, (Any?) -> Unit>>()

fun <T : Any> registerValidator(kc: KClass<T>, v: Validator<T>) {
    VALIDATORS[kc] = v
}

inline fun <reified T : Any> registerValidator(vararg validators: Pair<KProperty1<T, *>, Function1<*, Unit>>) {
    registerValidator(T::class, validator(*validators))
}
