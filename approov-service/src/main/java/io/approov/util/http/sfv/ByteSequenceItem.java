package io.approov.util.http.sfv;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Represents a Byte Sequence.
 * 
 * @see <a href= "https://www.rfc-editor.org/rfc/rfc8941.html#binary">Section
 *      3.3.5 of RFC 8941</a>
 */
public class ByteSequenceItem implements Item<ByteBuffer> {

    private final byte[] value;
    private final Parameters params;

    private ByteSequenceItem(byte[] value, Parameters params) {
        this.value = Objects.requireNonNull(value, "value must not be null");
        this.params = Objects.requireNonNull(params, "params must not be null");
    }

    /**
     * Creates a {@link ByteSequenceItem} instance representing the specified
     * {@code byte[]} value.
     * 
     * @param value
     *            a {@code byte[]} value.
     * @return a {@link ByteSequenceItem} representing {@code value}.
     */
    public static ByteSequenceItem valueOf(byte[] value) {
        return new ByteSequenceItem(value, Parameters.EMPTY);
    }

    @Override
    public ByteSequenceItem withParams(Parameters params) {
        if (Objects.requireNonNull(params, "params must not be null").isEmpty()) {
            return this;
        } else {
            return new ByteSequenceItem(this.value, params);
        }
    }

    @Override
    public Parameters getParams() {
        return params;
    }

    @Override
    public StringBuilder serializeTo(StringBuilder sb) {
        sb.append(':');
        sb.append(Base64.encodeToString(this.value, Base64.NO_WRAP));
        sb.append(':');
        params.serializeTo(sb);
        return sb;
    }

    @Override
    public String serialize() {
        return serializeTo(new StringBuilder()).toString();
    }

    @Override
    public ByteBuffer get() {
        // this returns a wrapper around a copy so that the object itself
        // stays immutable
        return ByteBuffer.wrap(this.value.clone());
    }
}
