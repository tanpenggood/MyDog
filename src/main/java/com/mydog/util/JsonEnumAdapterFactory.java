package com.mydog.util;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/** Serializes enums by name() and reads unknown enum values back as null (forward compatible). */
public final class JsonEnumAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<? super T> raw = type.getRawType();
        if (!Enum.class.isAssignableFrom(raw)) return null;
        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                out.value(value == null ? null : ((Enum) value).name());
            }

            @Override
            public T read(JsonReader in) throws IOException {
                String s = in.nextString();
                if (s == null) return null;
                for (Object c : raw.getEnumConstants()) {
                    if (((Enum) c).name().equals(s)) return (T) c;
                }
                return null;
            }
        };
    }
}
