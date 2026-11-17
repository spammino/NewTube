package org.schabi.newpipe.cast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A message of the Cast v2 protocol ({@code CastMessage} in Chromium's cast_channel.proto).
 * <p>
 * Only text payloads are supported. The (tiny) protobuf wire format is written by hand, so that
 * no protobuf library is needed.
 * </p>
 */
final class CastMessage {

    // protobuf field tags: (field number << 3) | wire type
    private static final int TAG_PROTOCOL_VERSION = 0x08;
    private static final int TAG_SOURCE_ID = 0x12;
    private static final int TAG_DESTINATION_ID = 0x1a;
    private static final int TAG_NAMESPACE = 0x22;
    private static final int TAG_PAYLOAD_TYPE = 0x28;
    private static final int TAG_PAYLOAD_UTF8 = 0x32;

    private static final int FIELD_PROTOCOL_VERSION = 1;
    private static final int FIELD_SOURCE_ID = 2;
    private static final int FIELD_DESTINATION_ID = 3;
    private static final int FIELD_NAMESPACE = 4;
    private static final int FIELD_PAYLOAD_UTF8 = 6;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED32 = 5;

    final String sourceId;
    final String destinationId;
    final String namespace;
    /** The text payload, or {@code null} for messages with a binary payload. */
    final String payload;

    CastMessage(final String sourceId, final String destinationId, final String namespace,
                final String payload) {
        this.sourceId = sourceId;
        this.destinationId = destinationId;
        this.namespace = namespace;
        this.payload = payload;
    }

    byte[] encode() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarintField(out, TAG_PROTOCOL_VERSION, 0); // CASTV2_1_0
        writeStringField(out, TAG_SOURCE_ID, sourceId);
        writeStringField(out, TAG_DESTINATION_ID, destinationId);
        writeStringField(out, TAG_NAMESPACE, namespace);
        writeVarintField(out, TAG_PAYLOAD_TYPE, 0); // STRING
        writeStringField(out, TAG_PAYLOAD_UTF8, payload);
        return out.toByteArray();
    }

    static CastMessage decode(final byte[] data) throws IOException {
        String sourceId = null;
        String destinationId = null;
        String namespace = null;
        String payload = null;

        int pos = 0;
        while (pos < data.length) {
            final long[] tag = readVarint(data, pos);
            pos = (int) tag[1];
            final int field = (int) (tag[0] >>> 3);
            final int wireType = (int) (tag[0] & 7);

            switch (wireType) {
                case WIRE_VARINT:
                    pos = (int) readVarint(data, pos)[1]; // value not needed
                    break;
                case WIRE_FIXED64:
                    pos = skip(data, pos, 8);
                    break;
                case WIRE_FIXED32:
                    pos = skip(data, pos, 4);
                    break;
                case WIRE_LENGTH_DELIMITED:
                    final long[] length = readVarint(data, pos);
                    pos = (int) length[1];
                    final int size = (int) length[0];
                    if (length[0] < 0 || length[0] > data.length - pos) {
                        throw new IOException("Truncated Cast message");
                    }
                    final String text = new String(data, pos, size, StandardCharsets.UTF_8);
                    pos += size;
                    if (field == FIELD_SOURCE_ID) {
                        sourceId = text;
                    } else if (field == FIELD_DESTINATION_ID) {
                        destinationId = text;
                    } else if (field == FIELD_NAMESPACE) {
                        namespace = text;
                    } else if (field == FIELD_PAYLOAD_UTF8) {
                        payload = text;
                    }
                    // other fields (e.g. the binary payload) are ignored
                    break;
                default:
                    throw new IOException("Unsupported protobuf wire type " + wireType);
            }
        }

        if (sourceId == null || destinationId == null || namespace == null) {
            throw new IOException("Incomplete Cast message");
        }
        return new CastMessage(sourceId, destinationId, namespace, payload);
    }

    private static void writeVarintField(final ByteArrayOutputStream out, final int tag,
                                         final long value) {
        out.write(tag);
        writeVarint(out, value);
    }

    private static void writeStringField(final ByteArrayOutputStream out, final int tag,
                                         final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(tag);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(final ByteArrayOutputStream out, final long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    /** @return {value, position after the varint} */
    private static long[] readVarint(final byte[] data, final int start) throws IOException {
        long result = 0;
        int shift = 0;
        int pos = start;
        while (pos < data.length && shift < 64) {
            final byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new long[] {result, pos};
            }
            shift += 7;
        }
        throw new IOException("Malformed varint in Cast message");
    }

    private static int skip(final byte[] data, final int pos, final int count)
            throws IOException {
        if (pos + count > data.length) {
            throw new IOException("Truncated Cast message");
        }
        return pos + count;
    }
}
