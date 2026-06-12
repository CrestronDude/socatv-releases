package com.socatv.nova.utils

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Lenient Gson that survives Xtream Codes quirks:
 *  - backdrop_path is a JSON array but our model field is String → take first element
 *  - numeric fields sometimes arrive as quoted strings
 */
object NovaGson {

    val instance: Gson by lazy {
        GsonBuilder()
            .registerTypeAdapterFactory(LenientStringAdapterFactory)
            .registerTypeAdapterFactory(LenientDoubleAdapterFactory)
            .serializeNulls()
            .create()
    }

    private object LenientStringAdapterFactory : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != String::class.java) return null
            @Suppress("UNCHECKED_CAST")
            return LenientStringAdapter as TypeAdapter<T>
        }
    }

    private object LenientStringAdapter : TypeAdapter<String?>() {
        override fun write(out: JsonWriter, value: String?) {
            if (value == null) out.nullValue() else out.value(value)
        }

        override fun read(reader: JsonReader): String? {
            return when (reader.peek()) {
                JsonToken.NULL -> { reader.nextNull(); null }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val first = if (reader.hasNext()) readScalar(reader) else null
                    while (reader.hasNext()) reader.skipValue()
                    reader.endArray()
                    first
                }
                JsonToken.BEGIN_OBJECT -> { reader.skipValue(); null }
                else -> readScalar(reader)
            }
        }

        private fun readScalar(reader: JsonReader): String? = try {
            reader.nextString()
        } catch (e: Exception) {
            try { reader.skipValue() } catch (_: Exception) {}
            null
        }
    }

    private object LenientDoubleAdapterFactory : TypeAdapterFactory {
        override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (type.rawType != Double::class.javaObjectType &&
                type.rawType != Double::class.java) return null
            @Suppress("UNCHECKED_CAST")
            return LenientDoubleAdapter as TypeAdapter<T>
        }
    }

    private object LenientDoubleAdapter : TypeAdapter<Double?>() {
        override fun write(out: JsonWriter, value: Double?) {
            if (value == null) out.nullValue() else out.value(value)
        }

        override fun read(reader: JsonReader): Double? {
            return when (reader.peek()) {
                JsonToken.NULL -> { reader.nextNull(); null }
                JsonToken.STRING -> reader.nextString().toDoubleOrNull() ?: 0.0
                JsonToken.NUMBER -> reader.nextDouble()
                JsonToken.BOOLEAN -> if (reader.nextBoolean()) 1.0 else 0.0
                else -> { reader.skipValue(); null }
            }
        }
    }
}
