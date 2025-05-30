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
uses the serialization for `C.a`? This is what this library accomplishes. In the process, the proxy map has been
expanded into a fully featured lensing map so that it can update objects itself but also create the updater transformer.

## Usage
We produce a class `ProxyMap<T>`, which is serialized by `ProxyMapSerializer<T>(KSerializer<T>)`,
that can be used for (de)serializing from a string or stream along the form of
```kotlin
@Serializable
data class C// (...)
val original = C(...)
val jsonStr = getJson()
val lensMap = Json.decodeFromString<ProxyMap<C>>(jsonStr)
val new = original + lensMap
```

Case folding can be enabled with constructions from lens maps:
```
@Serializable
data class C2 (val aA: ..., ...)
val original = C2(...)
val myMap = mapOf("aa" to ...)
val lensMap2 = ProxyMap<C2>(myMap, caseFold = true)
val new = original + lensMap2
```

A third possible construction for a ProxyMap is from a property map, where values are deserialized as applicable:
```
@Serializable
data class C3 (val A: A01, val B: List<A02>, ...)
@Serializable
data class C4 (val a: C3, ...)
val myMap = mapOf("a.a" to "abc", "a.b.0" to "def, ...)
val pm = ProxyMap.fromProps<C4>(myMap, caseFold = true)
```
Note that in a property map key, the syntax is as follows:
```[property1][.property2]...```, or for lists,
```[property1].[index]```, with sub-properties if necessary for list element construction.

## Limitations
1. Reflection is necessary to query the serializable members and create the serialization index mappings.
   This requires the kotlin-reflection library. Caching is used to store the reflective querying but there is still the
   cost *of* doing the querying at run-time.
2. kx-serialization `@Polymorphic` and `@Contextual` untested. The author doesn't see these being useful enough
   for lens types to handle issues arising from use of them.
3. Generics are unsupported. Attempting (de)serialization with them will throw an IllegalArgumentException.
4. ProxyMap cannot be used as a property of a data class which is itself lensed with a ProxyMap. This is because
   lensing a lens is nonsense so we guard against that.