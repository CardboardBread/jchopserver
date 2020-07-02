package ca.chopserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static ca.chopserver.Packet.*;

public enum EnquiryType {
    NORMAL {
        @Override
        void parse(Client client) throws IOException {
            client.logger.info("Normal enquiry");

            // confirm enquiry
            client.send(new byte[] {NULL, ACKNOWLEDGE, ENQUIRY, NULL});
        }
    },

    RETURN {
        @Override
        void parse(Client client) throws IOException {
            client.logger.info("Return enquiry");

            // return with an enquiry
            client.send(new byte[] {NULL, ENQUIRY, NULL, NULL});
        }
    },

    TIME {
        @Override
        void parse(Client client) throws IOException {
            client.logger.info("Time enquiry");

            // determine how big the data section is, read it into the buffer
            byte payload = client.inBuffer.get(PACKET_CONTROL2);
            client.logger.info("Time payload is " + payload + " bytes long");
            client.inBuffer.clear();

            client.inBuffer.limit(payload);
            if (client.socket.read(client.inBuffer) != payload) {
                throw new IOException("Provided data section is not at specified length");
            }

            // rewind position to end of header, pull data as value
            client.inBuffer.rewind();
            long value = client.inBuffer.getLong();

            // rewind again, pull data as byte array
            client.inBuffer.rewind();
            byte[] data = new byte[payload];
            client.inBuffer.get(data);

            // report value and bytes
            client.logger.info("Reported time at client is " + value + " as " + Arrays.toString(data));

            // confirm time enquiry
            client.send(new byte[] {NULL, ACKNOWLEDGE, ENQUIRY, NULL});
        }
    },

    RETURN_TIME {
        @Override
        void parse(Client client) throws IOException {
            client.logger.info("Return time enquiry");

            // assemble time header plus payload into buffer, send to client
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_LEN + Long.BYTES);
            buffer.put(new byte[] {NULL, ENQUIRY, Packet.TIME, (byte) Long.BYTES});
            buffer.putLong(System.currentTimeMillis());

            client.send(buffer.array());
        }
    };

    public static EnquiryType fromOrdinal(int ordinal) {
        return values()[ordinal];
    }

    void parse(Client client) throws IOException {
        throw new UnsupportedOperationException("Invalid status value encountered");
    }

    byte[] bytes() {
        return new byte[]{NULL, ENQUIRY, (byte) this.ordinal(), NULL};
    }

    byte[] bytes(byte control2) {
        return new byte[]{NULL, ENQUIRY, (byte) this.ordinal(), control2};
    }

}
