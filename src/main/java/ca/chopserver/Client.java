package ca.chopserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Client implements Runnable {

    public static void main(String[] args) throws IOException {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        rootLogger.addHandler(new ImmediateStreamHandler(System.out, new LogFormatter()));

        InetSocketAddress target = new InetSocketAddress("127.0.0.1", 50001);
        Client instance = new Client(target, 255);
        Thread thread = new Thread(instance);
        thread.start();

        Scanner console = new Scanner(System.in);
        while (true) {
            String input = console.nextLine();
            byte[] text = input.getBytes();
            ByteBuffer data = ByteBuffer.allocate(Packet.HEADER_LEN);

            switch (input) {
                case "exit":
                    data.put(StatusType.ESCAPE.bytes());
                    break;
                case "ping":
                    data.put(EnquiryType.NORMAL.bytes());
                    break;
                case "pingret":
                    data.put(EnquiryType.RETURN.bytes());
                    break;
                case "pingtime":
                    data = ByteBuffer.allocate(Packet.HEADER_LEN + Long.BYTES);
                    data.put(EnquiryType.TIME.bytes((byte) Long.BYTES));
                    data.putLong(System.currentTimeMillis());
                    break;
                case "pingtimeret":
                    data.put(EnquiryType.RETURN_TIME.bytes());
                    break;
                case "sleep":
                    data.put(StatusType.IDLE.bytes());
                    break;
                case "wake":
                    data.put(StatusType.WAKEUP.bytes());
                    break;
                case "break":
                    data.put(new byte[]{Packet.NULL, Packet.START_TEXT, (byte) 1, (byte) 4});
                    break;
                default:
                    data = ByteBuffer.allocate(Packet.HEADER_LEN + text.length);
                    data.put(StatusType.START_TEXT.bytes(text));
                    break;
            }

            instance.send(Arrays.copyOf(data.array(), data.array().length));
            instance.selector.wakeup();
        }
    }

    public ReentrantLock operationLock;
    public Selector selector;
    public SocketChannel socket;
    public SelectionKey selectorKey;
    public Logger logger;

    ByteBuffer inBuffer;
    ByteBuffer outBuffer;
    public Queue<byte[]> incoming;
    public Queue<byte[]> outgoing;

    public int window;
    public int incomingFlag;
    public int outgoingFlag;

    /**
     * Start as clientside, automatically connects and registers with personal selector.
     * Needs to be fed to a thread in order to run independently.
     */
    public Client(SocketAddress host, int window) throws IOException {
        socket = SocketChannel.open();
        socket.configureBlocking(false);

        selector = Selector.open();

        operationLock = new ReentrantLock();
        incoming = new LinkedList<>();
        outgoing = new LinkedList<>();
        logger = Logger.getLogger(host.toString());

        this.window = window;
        incomingFlag = 0;
        outgoingFlag = 0;

        inBuffer = ByteBuffer.allocate(window);
        outBuffer = ByteBuffer.allocate(window);

        logger.info("Connecting to " + host);
        if (!socket.connect(host)) {
            selectorKey = socket.register(selector, SelectionKey.OP_CONNECT);
            logger.info("Instant connection failed, queueing to finish connection.");
        }
    }

    /**
     * Start as serverside, automatically registers with serverside selector.
     */
    public Client(Selector selector, SocketChannel socket, int window) throws IOException {
        this.socket = socket;
        socket.configureBlocking(false);
        this.selector = selector;

        operationLock = new ReentrantLock();
        incoming = new LinkedList<>();
        outgoing = new LinkedList<>();
        logger = Logger.getLogger(socket.getRemoteAddress().toString());

        this.window = window;
        incomingFlag = 0;
        outgoingFlag = 0;

        inBuffer = ByteBuffer.allocate(window);
        outBuffer = ByteBuffer.allocate(window);

        selectorKey = socket.register(selector, SelectionKey.OP_READ, this);
        logger.info("Client " + socket.getRemoteAddress() + " registered as serverside object");
    }

    // Clientside runner
    @Override
    public void run() {
        while (true) {
            try {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();

                    if (key.isConnectable()) {
                        logger.info("Attempting to establish connection");
                        establish();
                    }

                    if (key.isReadable()) {
                        logger.info("Incoming data");
                        try {
                            read();
                        } catch (IOException ioe) {
                            logger.info("Server " + socket.getRemoteAddress() + " disconnected");
                            shutdown();
                            System.exit(1);
                        }
                    }

                    if (key.isWritable()) {
                        logger.info("Outgoing data");
                        write();
                    }

                    keyIterator.remove();
                }
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    void read() throws IOException {
        operationLock.lock();
        // clear any previous use, ensure only head is read
        inBuffer.clear();
        inBuffer.limit(Packet.HEADER_LEN);

        // read incoming header
        int bytesRead = socket.read(inBuffer);
        if (bytesRead < 0) {
            logger.info("Reached end of stream, downstream disconnected");
            return;
        } else if (bytesRead != Packet.HEADER_LEN) {
            logger.info("Received header is not expected length");
            return;
        }

        // print packet style without modifying any buffer trackers
        inBuffer.rewind();
        logger.info("Incoming packet style " + inBuffer.getInt());

        byte packStatus = inBuffer.get(Packet.PACKET_STATUS);

        try {
            StatusType status = StatusType.fromOrdinal(packStatus);
            status.parse(this);
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            logger.info("Undefined packet type provided, notifying downstream");
            send(StatusType.NEG_ACKNOWLEDGE.bytes(packStatus));
        } catch (UnsupportedOperationException uoe) {
            logger.info("Unsupported packet type provided, notifying downstream");
            send(StatusType.NEG_ACKNOWLEDGE.bytes(packStatus));
        }

        operationLock.unlock();
    }

    void write() throws IOException {
        operationLock.lock();
        byte[] sending = outgoing.poll();

        // nothing to write, unregister writing and return
        if (sending == null) {
            logger.info("No messages left, returning to read operations");
            selectorKey = socket.register(selector, SelectionKey.OP_READ, this);
            return;
        }

        // if a body is present, print its bytes
        if (sending.length > Packet.HEADER_LEN) {
            byte[] body = Arrays.copyOfRange(sending, Packet.HEADER_LEN, sending.length);
            logger.info("Outgoing bytes: " + Arrays.toString(body));
        }

        // load data into buffer, print the style, prepare for writing
        outBuffer.clear();
        outBuffer.put(sending);
        logger.info("Outgoing packet style " + outBuffer.getInt(0) + " ");

        // iterating over however many buffers it takes to send all the data
        int total = 0;
        int remaining = sending.length;
        int bufferCount = sending.length / window + (sending.length % window != 0 ? 1 : 0);
        for (int i = 0; i < bufferCount; i++) {
            // calculate how much for the n-th buffer, prepare exactly that amount to be sent
            int expected = (window > remaining) ? remaining : window;
            outBuffer.clear();
            outBuffer.put(sending, total, expected);
            outBuffer.flip();

            // continue calling write until the buffer is 'emptied'
            int sent = 0;
            while (outBuffer.hasRemaining()) {
                sent += socket.write(outBuffer);
            }

            remaining -= sent;
            total += sent;
        }

        // nothing left to write, unregister for writing
        if (outgoing.isEmpty()) {
            logger.info("Sending queue exhausted, returning to read operations");
            selectorKey = socket.register(selector, SelectionKey.OP_READ, this);
        }
        operationLock.unlock();
    }

    void establish() throws IOException {
        operationLock.lock();

        // non-blocking attempt to finish connecting, should be returned to on failure as the registry is not changed
        if (socket.finishConnect()) {
            logger.info("Connected to " + socket.getRemoteAddress());
            selectorKey = socket.register(selector, SelectionKey.OP_READ, this);
        }

        operationLock.unlock();
    }

    public void send(byte[] data) throws IOException {
        operationLock.lock();

        outgoing.offer(data);
        int previousOps = selectorKey.interestOps();
        selectorKey = socket.register(selector, previousOps | SelectionKey.OP_WRITE, this);
        logger.info("Data registered and queued for sending");

        operationLock.unlock();
    }

    public void forceSend(byte[] data) throws IOException {
        send(data);
        write();
    }

    public void shutdown() throws IOException {
        selectorKey.cancel();
        socket.close();
    }
}
