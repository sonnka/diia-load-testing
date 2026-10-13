package ua.gov.diia.loadtest.grpc

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs

/*
 * JSON <-> google.protobuf.Struct/Value, for request payloads that carry free-form JSON (JWKs, JWK
 * Sets, key attestations, wallet metadata...) the way Diia gRPC contracts commonly do.
 */

public fun JsonObject.toStruct(): Struct =
    Struct.newBuilder().also { builder -> forEach { (key, value) -> builder.putFields(key, value.toValue()) } }.build()

public fun JsonElement.toValue(): Value =
    when (this) {
        is JsonNull -> {
            Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
        }

        is JsonObject -> {
            Value.newBuilder().setStructValue(toStruct()).build()
        }

        is JsonArray -> {
            Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(map { it.toValue() })).build()
        }

        is JsonPrimitive -> {
            val bool = booleanOrNull
            when {
                isString -> Value.newBuilder().setStringValue(content).build()
                bool != null -> Value.newBuilder().setBoolValue(bool).build()
                else -> Value.newBuilder().setNumberValue(content.toDouble()).build()
            }
        }
    }

public fun Struct.toJsonObject(): JsonObject = buildJsonObject { fieldsMap.forEach { (key, value) -> put(key, value.toJsonElement()) } }

public fun Value.toJsonElement(): JsonElement =
    when (kindCase) {
        Value.KindCase.STRUCT_VALUE -> {
            structValue.toJsonObject()
        }

        Value.KindCase.LIST_VALUE -> {
            buildJsonArray { listValue.valuesList.forEach { add(it.toJsonElement()) } }
        }

        Value.KindCase.STRING_VALUE -> {
            JsonPrimitive(stringValue)
        }

        Value.KindCase.BOOL_VALUE -> {
            JsonPrimitive(boolValue)
        }

        // Struct numbers are doubles; keep integral ones integral so e.g. `1` does not come back as `1.0`.
        Value.KindCase.NUMBER_VALUE -> {
            numberValue.let { if (it % 1.0 == 0.0 && abs(it) < MAX_SAFE_INTEGER) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }
        }

        Value.KindCase.NULL_VALUE, Value.KindCase.KIND_NOT_SET, null -> {
            JsonNull
        }
    }

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_992.0 // 2^53
