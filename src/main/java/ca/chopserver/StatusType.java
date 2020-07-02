package ca.chopserver;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;

import static ca.chopserver.Packet.*;
import static ca.chopserver.Packet.NULL;

public enum StatusType {

    NULL,

    START_HEADER,

    START_TEXT {
        @Override
        void parse(Client client) throws IOException {
            client.logger.info("Text section incoming");

            byte segmentCount = client.inBuffer.get(PACKET_CONTROL1);
            byte segmentWidth = client.inBuffer.get(PACKET_CONTROL2);
            byte[] ret;

            if (segmentCount == 0 && segmentWidth == 0) {
                // unknown length read
                client.logger.info("Unknown length section declared");

                // assemble a pipe to send data into
                PipedInputStream inStream = new PipedInputStream();
                PipedOutputStream outStream = new PipedOutputStream(inStream);
                outStream.write(client.inBuffer.array(), 0, HEADER_LEN);

                // keep reading until stop signal is found
                boolean found = false;
                int total = HEADER_LEN;
                client.inBuffer.limit(client.window);
                while (!found) {
                    client.inBuffer.clear();
                    int read = client.socket.read(client.inBuffer);

                    for (byte b: client.inBuffer.array()) {
                        if (b == Packet.END_TEXT) {
                            found = true;
                            break;
                        }
                    }

                    total += read;
                    outStream.write(client.inBuffer.array(), 0, read);
                }

                // read piped data into solid buffer
                ret = new byte[total];
                if (inStream.read(ret) != total) {
                    throw new IOException();
                }
            } else {
                // regular known-length read
                int remaining = segmentCount * segmentWidth;
                int bufferCount = remaining / client.window + (remaining % client.window != 0 ? 1 : 0);
                client.logger.info("Known length section declared in " + segmentCount + " segments " + segmentWidth +  " wide, totalling " + remaining + " long, fits in " + bufferCount + " buffers");

                // make return array, and load header
                ret = new byte[HEADER_LEN + remaining];
                client.inBuffer.rewind();
                client.inBuffer.get(ret, 0, HEADER_LEN);

                int total = HEADER_LEN;
                for (int i = 0; i < bufferCount; i++) {
                    // calculate how much should be put in the n-th buffer, prepare it to hold exactly that number
                    int expected = (client.window > remaining) ? remaining : client.window;
                    client.inBuffer.clear();
                    client.inBuffer.limit(expected);

                    // read incoming data into buffer, check to make sure it's the right amount
                    int read = client.socket.read(client.inBuffer);
                    if (read != expected) {
                        throw new IOException();
                    }

                    // place new data in return buffer
                    client.inBuffer.rewind();
                    client.inBuffer.get(ret, total, read);

                    // update trackers
                    total += read;
                    remaining -= read;
                }
            }

            // report and offload received text
            client.logger.info("Downstream: \"" + new String(ret).substring(HEADER_LEN, ret.length) + "\"");
            client.incoming.offer(ret);

            // confirm text section
            client.send(new byte[] {Packet.NULL, Packet.ACKNOWLEDGE, Packet.START_TEXT, Packet.NULL});
        }

        @Override
        byte[] bytes(byte[] data) {
            // create container for text data
            byte[] ret = new byte[HEADER_LEN + data.length];
            ret[0] = Packet.NULL;
            ret[1] = Packet.START_TEXT;

            // calculate control signals to hold text
            if (data.length > Byte.MAX_VALUE) {
                for (int i = 1; i < Math.sqrt(data.length); i++) {
                    if (data.length % i == 0) {
                        if (i <= Byte.MAX_VALUE && data.length / i <= Byte.MAX_VALUE) {
                            ret[2] = (byte) i;
                            ret[3] = (byte) (data.length / i);
                            break;
                        }
                    }
                }
                if (ret[2] < 0) {
                    throw new IllegalArgumentException("Data section length cannot be held within header flags");
                }
            } else {
                ret[2] = 1;
                ret[3] = (byte) data.length;
            }

            // copy text ahead of header
            System.arraycopy(data, 0, ret, HEADER_LEN, data.length);

            return ret;
        }
    },

    END_TEXT,

    END_TRANSMISSION,

    ENQUIRY {
        @Override
        void parse(Client client) throws IOException {
            EnquiryType object = EnquiryType.fromOrdinal(client.inBuffer.get(PACKET_CONTROL1));
            object.parse(client);
        }

        byte[] bytes(EnquiryType type) { // TODO: how would you even call this
            return type.bytes();
        }
    },

    ACKNOWLEDGE {
        @Override
        void parse(Client client) throws IOException {
            StatusType confirm = fromOrdinal(client.inBuffer.get(PACKET_CONTROL1));
            client.logger.info(confirm.name() + " acknowledge received");
            switch (confirm) {
                case WAKEUP:
                    client.logger.info("Downstream is now asleep");
                    client.incomingFlag = NULL.ordinal();
                    break;
                case IDLE:
                    client.logger.info("Downstream is now awake");
                    client.incomingFlag = IDLE.ordinal();
                    break;
                case ESCAPE:
                    client.logger.info("Client confirmed shutdown");
                    client.incomingFlag = CANCEL.ordinal();
                    client.outgoingFlag = CANCEL.ordinal();
                    break;
            }
        }

        byte[] bytes(StatusType confirm) { // TODO: how would you even call this
            return new byte[]{Packet.NULL, Packet.ACKNOWLEDGE, (byte) confirm.ordinal(), Packet.NULL};
        }
    },

    WAKEUP {
        @Override
        void parse(Client client) throws IOException {
            client.logger.info("Wakeup requested");
            if (client.incomingFlag == Packet.IDLE) {
                client.incomingFlag = Packet.NULL;
                client.send(StatusType.ACKNOWLEDGE.bytes(Packet.WAKEUP));
                client.logger.info("Upstream now awake");
            } else {
                client.send(StatusType.NEG_ACKNOWLEDGE.bytes(Packet.WAKEUP));
                client.logger.info("Wakeup refused");
            }
        }
    },

    UN1,

    UN2,

    UN3,

    UN4,

    UN5,

    UN6,

    SHIFT_OUT,

    SHIFT_IN,

    START_DATA,

    CONTROL_ONE,

    CONTROL_TWO,

    CONTROL_THREE,

    CONTROL_FOUR,

    NEG_ACKNOWLEDGE {
        @Override
        void parse(Client client) throws IOException {
            StatusType confirm = fromOrdinal(client.inBuffer.get(PACKET_CONTROL1));
            client.logger.info(confirm.name() + " refused");
        }
    },

    IDLE {
        @Override
        void parse(Client client) throws IOException {
            client.logger.info("Sleep requested");
            if (client.incomingFlag == Packet.NULL) {
                client.incomingFlag = Packet.IDLE;
                client.send(StatusType.ACKNOWLEDGE.bytes(Packet.IDLE));
                client.logger.info("Upstream now asleep");
            } else {
                client.send(StatusType.NEG_ACKNOWLEDGE.bytes(Packet.IDLE));
                client.logger.info("Sleep refused");
            }
        }
    },

    END_TRANSMISSION_BLOCK,

    CANCEL,

    END_OF_MEDIUM,

    SUBSTITUTE,

    ESCAPE {
        @Override
        void parse(Client client) throws IOException {
            // confirm, no functionality yet
            client.logger.info("Disconnect requested");
            client.forceSend(StatusType.ACKNOWLEDGE.bytes(Packet.ESCAPE));
            client.shutdown();
        }
    },

    FILE_SEPARATOR,

    GROUP_SEPARATOR,

    RECORD_SEPARATOR,

    UNIT_SEPARATOR;

    public static StatusType fromOrdinal(int ordinal) {
        return values()[ordinal];
    }

    void parse(Client client) throws IOException {
        throw new UnsupportedOperationException("Invalid status value encountered");
    }

    byte[] bytes() {
        return new byte[]{Packet.NULL, (byte) this.ordinal(), Packet.NULL, Packet.NULL};
    }

    byte[] bytes(byte control1) {
        return new byte[]{Packet.NULL, (byte) this.ordinal(), control1, Packet.NULL};
    }

    byte[] bytes(byte control1, byte control2) {
        return new byte[]{Packet.NULL, (byte) this.ordinal(), control1, control2};
    }

    byte[] bytes(byte[] data) {
        throw new UnsupportedOperationException("Packet type does not have data section");
    }

}
