package com.moshy

import com.moshy.ProxyMap
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

/*
 * Types under test.
 */

@Serializable
data class RegularClass(
    val prop1: String
)

@Serializable
data class RegularClass2(
    val prop1: String,
    val prop2: Int
)

@Serializable
data class ClassWithPropertySerializer(
    @Serializable(with = CustomPropertySerializer::class) val prop1: Int,
    val prop2: List<Double> = listOf(1.0)
)

/* Use a serializer that (de)serializes a constant to check that our proxy serializer handles @Serializable
 * as expected.
 */
object CustomPropertySerializer : KSerializer<Int> {
    const val VALUE = 42
    override val descriptor = PrimitiveSerialDescriptor("Int42", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int = VALUE.also { decoder.decodeInt() /* consume */ }
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(VALUE)
}

@Serializable
data class ClassWithSerialNameProperty(
    val prop1: Int,
    @SerialName("serial2") val prop2: String,
)

@Serializable
data class ClassWithDataclassMember(
    val prop1: Int,
    val prop2: RegularClass
)

@Serializable
data class ClassWithNullableMember(
    val prop1: Double?
)

@Serializable
data class ClassWithNullableNestedDataclassMember(
    val prop1: Nested?,
    val prop2: String
) {
    @Serializable
    data class Nested(
        val prop1: Int,
        val prop2: Double
    )
}

@Serializable
class NonData

@Serializable
data class ClassWithBodyDeclaredProperty(
    val prop1: Int
) {
    var prop2: String = prop1.toString()
}

@Serializable
data class Box<T>(
    val elem: T
)

@Serializable
data class ClassWithTransientProperty(
    val prop1: Int,
    @Transient val prop2: String = prop1.toString()
) {
    companion object {
        val serializableMemberCount: Int
            get() = 1
    }
}

@Serializable
data class ClassWithPMProperty(
    val pm: ProxyMap<RegularClass>
)

@Serializable
data class WithOptional(
    val p1: String = "a",
    val p2: Int
)

@Serializable
data class Rejectable(
    val `p1 abc`: String, // use one with spaces
    val p2: Int
)

@Serializable
data class ThrowsExceptionInInitializer(
    val a: Int
) {
    init {
        require(a > 0)
    }
}

@Serializable
data class TestCasefold(
    val theProp: String
)

@Serializable
data class TestCasefoldReject(
    val theProp: String,
    val tHeProp: String
)