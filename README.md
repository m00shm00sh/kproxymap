# KProxyMap

## Motivation
The project [kpropmap](https://github.com/rocketraman/kpropmap) provides the option to use a map to update
a data class as if `copy` was invoked with an unpacked map. This allows us to do something like
```kotlin
data class C(val a: Int)
val c = C(1)
val updateMap = mapOf("a" to 2)
val cNew = propMapOf(updateMap).applyProps(c)
assert(cNew == c.copy(a = 2))
```

What if we want to use [kotinx.serialization](https://github.com/Kotlin/kotlinx.serialization) to serialize/deserialize
a property map for `class C`? The approach of `@Serializable Map<String, Any?>` is obviously not going to work because
`Any?` is not a serializable type so the collection serializer is inapplicable. What if we could use a proxy
serializer that replays the (de)serialization for `class C` to build a property map so that for object member `a` it
uses the serialization for `C.a`? This is what this library accomplishes.

## Usage
We produce a class `ProxyMap<T>`, which is serialized by `ProxyMapSerializer<T>(KSerializer<T>)`,
that can be used for (de)serializing from a string or stream along the orm of
```kotlin
@Serializable
data class C// (...)
val jsonStr = getJson()
val asMap = Json.decodeFromString<ProxyMap<C>>(jsonStr)
```
Then `asMap` can be used with the `propMapOf` construction to update instances just like in the first example.

## Limitations
1. Reflection is necessary to query the serializable members and create the serialization index mappings.
   This requires the kotlin-reflection library. Caching is used to store the reflective querying but there is still the
   cost *of* doing the querying at run-time.
2. kx-serialization `@Polymorphic` and `@Contextual` untested. The author doesn't see these being useful enough
   for lens types to handle issues arising from use of them.
