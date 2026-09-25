package cs1302.tracer.batch;

import cs1302.tracer.execution.NestingException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;

/** Streaming depth check before Gson constructs a request object. */
final class JsonNesting {
    /** Maximum number of simultaneously open JSON arrays and objects. */
    static final int MAX_DEPTH = 64;

    /** Prevents utility construction. */
    private JsonNesting() {} // JsonNesting

    /**
     * Checks all tokens, including unknown request properties, without recursive skipValue.
     * @param json Raw request line.
     * @throws IOException If the JSON cannot be read.
     */
    static void validate(String json) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            int depth = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                switch (reader.peek()) {
                case BEGIN_ARRAY, BEGIN_OBJECT -> {
                    if (++depth > MAX_DEPTH) {
                        throw new NestingException("json_nesting_limit");
                    } // if
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray();
                    } else {
                        reader.beginObject();
                    } // if
                } // case
                case END_ARRAY -> {
                    reader.endArray();
                    depth--;
                } // case
                case END_OBJECT -> {
                    reader.endObject();
                    depth--;
                } // case
                case NAME -> reader.nextName();
                case BOOLEAN -> reader.nextBoolean();
                case NULL -> reader.nextNull();
                default -> reader.nextString();
                } // switch
            } // while
        } // try
    } // validate
} // JsonNesting
