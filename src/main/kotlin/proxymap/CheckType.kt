package com.moshy.proxymap

import com.moshy.ProxyMap
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties

internal fun checkType(type: KType) {
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