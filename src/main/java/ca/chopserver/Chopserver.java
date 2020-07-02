package ca.chopserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class Chopserver {

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

    public static void main(String[] args) throws IOException {

        int port = 50001;
        int bufsize = 255;
        int max_connections = 20;
        int connection_queue = 5;

        SelectionKey[] clients = new SelectionKey[max_connections];

        Selector selector = Selector.open();

        ServerSocketChannel serverSocket;
        serverSocket = ServerSocketChannel.open();

        serverSocket.bind(new InetSocketAddress("127.0.0.1", 50001));

        SelectionKey acceptKey = serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        boolean run = true;
        while (run) {
            System.out.printf("\n");

            selector.select();

            for (SelectionKey clientKey : clients) {
                if (clientKey.isReadable()) {

                }
            }

            if (acceptKey.isAcceptable()) {
                accept_new_client(serverSocket, clients, selector);
            }
        }
    }

    static void accept_new_client(ServerSocketChannel serverSocket, SelectionKey[] clientKeys, Selector selector) throws IOException {
        SocketChannel newClient = serverSocket.accept();
        newClient.configureBlocking(false);

        SelectionKey newKey = newClient.register(selector, SelectionKey.OP_READ);

        for (int i = 0; i < clientKeys.length; i++) {
            if (clientKeys[i] == null) {
                clientKeys[i] = newKey;
                return;
            }
        }

        newKey.cancel();
        newClient.close();
    }

    static void process_request() {

    }

    static void send(Client client, byte[] data) throws ClosedChannelException {
        int previousOps = client.selectorKey.interestOps();
        client.selectorKey = client.socket.register(client.selector, previousOps | SelectionKey.OP_WRITE);
        client.outgoing.add(data);
    }

    static void write(Client client) throws IOException {
        SocketChannel target = client.socket;
        byte[] incoming = client.outgoing.poll();

        // nothing to write, unregister writing and return
        if (incoming == null) {
            client.selectorKey = target.register(client.selector, SelectionKey.OP_READ);
            return;
        }

        ByteBuffer buffer = ByteBuffer.allocate(incoming.length);
        buffer.clear();
        buffer.put(incoming);
        buffer.flip();

        // we have to write the entire segment of data
        while (buffer.hasRemaining()) {
            target.write(buffer);
        }

        // nothing left to write, unregister for writing
        if (client.outgoing.isEmpty()) {
            client.selectorKey = target.register(client.selector, SelectionKey.OP_READ);
        }
    }

    static void read(Client client) throws IOException {
        SocketChannel source = client.socket;

        ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_LEN);
        int headerRead = source.read(headerBuf);
        if (headerRead < 0) {
            throw new IOException("Client connection closed");
        } else if (headerRead < HEADER_LEN) {
            throw new IOException("Incomplete header received");
        }

        StatusType stat = StatusType.values()[headerBuf.get(PACKET_STATUS)];
        //stat.parse(client, headerBuf);
        //parse_header(client, headerBuf);
    }

    static void parse_header(Client client, ByteBuffer header) throws IOException {
        byte status = header.get(PACKET_STATUS);
        switch (status) {
            case NULL:
                break;
            case START_HEADER:
                parse_long_header(client, header);
                break;
            case START_TEXT:
                parse_text(client, header);
                break;
            case ENQUIRY:
                parse_enquiry(client, header);
                break;
            case ACKNOWLEDGE:
                parse_acknowledge(client, header);
                break;
            case WAKEUP:
                parse_wakeup(client, header);
                break;
            case NEG_ACKNOWLEDGE:
                parse_neg_acknowledge(client, header);
                break;
            case IDLE:
                parse_idle(client, header);
                break;
            case ESCAPE:
                parse_escape(client, header);
            default:
                break;
        }
    }

    static void parse_long_header(Client client, ByteBuffer header) {

    }

    static void parse_text(Client client, ByteBuffer header) throws IOException {
        byte control1 = header.get(PACKET_CONTROL1);
        byte control2 = header.get(PACKET_CONTROL2);

        if (control1 == 0 && control2 == 0) {
            List<ByteBuffer> section = new ArrayList<>();
            ByteBuffer drop = ByteBuffer.allocate(client.window);
            section.add(drop);

            boolean found = false;
            while (!found) {
                client.socket.read(drop);

                for (byte b : drop.array()) {
                    if (b == END_TEXT) found = true;
                }

                drop = ByteBuffer.allocate(client.window);
                section.add(drop);
            }

            System.out.print("Unknown Length Text Section: ");
            for (ByteBuffer buffer : section) {
                System.out.print(new String(buffer.array()));
            }
            System.out.println();
        } else {
            int bodyLength = control1 * control2;
            int bufferCount = bodyLength / client.window + (bodyLength % client.window != 0 ? 1 : 0);

            ByteBuffer[] buffers = new ByteBuffer[bufferCount];
            for (int i = 0; i < bufferCount; i++) {
                buffers[i] = ByteBuffer.allocate(client.window);
            }

            long bodyRead = client.socket.read(buffers);

            System.out.print("Text Section: ");
            for (int i = 0; i < bufferCount; i++) {
                System.out.print(new String(buffers[i].array()));
            }
            System.out.println();
        }

        send(client, new byte[] {NULL, ACKNOWLEDGE, START_TEXT, NULL});
    }

    static void parse_enquiry(Client client, ByteBuffer header) throws IOException {
        switch (header.get(PACKET_CONTROL1)) {
            case NORMAL:
                System.out.println("Normal ping");
                send(client, new byte[] {NULL, ACKNOWLEDGE, ENQUIRY, NULL});
                break;
            case RETURN:
                System.out.println("Return ping");
                send(client, new byte[] {NULL, ENQUIRY, NULL, NULL});
                break;
            case TIME:
                System.out.println("Time ping");
                send(client, new byte[] {NULL, ACKNOWLEDGE, ENQUIRY, NULL});
                break;
            case RETURN_TIME:
                System.out.println("Return time ping");
                ByteBuffer buffer = ByteBuffer.allocate(HEADER_LEN + Long.BYTES);
                buffer.put(new byte[] {NULL, ENQUIRY, TIME, Long.BYTES});
                buffer.putLong(System.currentTimeMillis());
                send(client, buffer.array());
                break;
        }
    }

    static void parse_acknowledge(Client client, ByteBuffer header) {
        System.out.println(StatusType.values()[header.get(PACKET_STATUS)].name() + " confirmed");

        byte control1 = header.get(PACKET_CONTROL1);
        switch (control1) {
            case WAKEUP:
                client.incomingFlag = NULL;
                break;
            case IDLE:
                client.incomingFlag = IDLE;
                break;
            case ESCAPE:
                client.incomingFlag = CANCEL;
                client.outgoingFlag = CANCEL;
                break;
        }
    }

    static void parse_wakeup(Client client, ByteBuffer header) throws IOException {
        System.out.println("wakeup");
        if (client.incomingFlag == IDLE) {
            client.incomingFlag = NULL;
            send(client, new byte[]{NULL, ACKNOWLEDGE, WAKEUP, NULL});
            System.out.println("now awake");
        } else {
            send(client, new byte[]{NULL, NEG_ACKNOWLEDGE, WAKEUP, NULL});
            System.out.println("refusing wakeup");
        }
    }

    static void parse_neg_acknowledge(Client client, ByteBuffer header) {
        System.out.println(StatusType.values()[header.get(PACKET_STATUS)].name() + "refused");
    }

    static void parse_idle(Client client, ByteBuffer header) throws IOException {
        System.out.println("sleep requested");
        if (client.incomingFlag == NULL) {
            client.incomingFlag = IDLE;
            send(client, new byte[]{NULL, ACKNOWLEDGE, IDLE, NULL});
            System.out.println("now asleep");
        } else {
            send(client, new byte[]{NULL, NEG_ACKNOWLEDGE, IDLE, NULL});
            System.out.println("refusing sleep");
        }
    }

    static void parse_escape(Client client, ByteBuffer header) {
        System.out.println("disconnect");
    }


}
