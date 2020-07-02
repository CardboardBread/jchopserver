package ca.chopserver;

import java.nio.ByteBuffer;

public abstract class Packet {

    static final Byte HEAD = 0;
    static final Byte STATUS = 0;
    static final Byte CONTROL1 = 0;
    static final Byte CONTROL2 = 0;

    static final int HEADER_LEN = HEAD.BYTES + STATUS.BYTES + CONTROL1.BYTES + CONTROL2.BYTES;

    static final byte PACKET_HEAD = 0;
    static final byte PACKET_STATUS = 1;
    static final byte PACKET_CONTROL1 = 2;
    static final byte PACKET_CONTROL2 = 3;

    static final byte NULL = 0;
    static final byte START_HEADER = 1;
    static final byte START_TEXT = 2;
    static final byte END_TEXT = 3;
    static final byte ENQUIRY = 5;
    static final byte ACKNOWLEDGE = 6;
    static final byte WAKEUP = 7;
    static final byte NEG_ACKNOWLEDGE = 21;
    static final byte IDLE = 22;
    static final byte CANCEL = 24;
    static final byte ESCAPE = 27;

    static final byte NORMAL = 0;
    static final byte RETURN = 1;
    static final byte TIME = 2;
    static final byte RETURN_TIME = 3;

    byte head;
    byte status;
    byte control1;
    byte control2;
    byte[] data;

    public int style() {
        return ByteBuffer.wrap(new byte[]{head, status, control1, control2}).getInt();
    }

    public ByteBuffer out() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LEN + data.length);
        buffer.put(head);
        buffer.put(status);
        buffer.put(control1);
        buffer.put(control2);
        if (data != null) buffer.put(data);
        return buffer;
    }

}
