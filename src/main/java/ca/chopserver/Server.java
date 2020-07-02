package ca.chopserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.*;

public class Server implements Runnable {

    public static void main(String[] args) throws IOException {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        rootLogger.addHandler(new ImmediateStreamHandler(System.out, new LogFormatter()));

        InetSocketAddress source = new InetSocketAddress("127.0.0.1", 50001);
        Server instance = new Server(source, 255);
        Thread thread = new Thread(instance);
        thread.start();
    }

    public ReentrantLock operationLock;
    public Selector selector;
    public ServerSocketChannel socket;
    public SelectionKey selectorKey;
    public List<Client> clientList;
    public Logger logger;

    public int window;
    public int incomingFlag;
    public int outgoingFlag;

    /**
     * Start as serverside host, automatically registers to start accepting and communicating with clients
     */
    public Server(SocketAddress bind, int window) throws IOException {
        socket = ServerSocketChannel.open();
        socket.configureBlocking(false);
        selector = Selector.open();

        operationLock = new ReentrantLock();
        clientList = new LinkedList<>();
        logger = Logger.getLogger(bind.toString());

        this.window = window;
        incomingFlag = 0;
        outgoingFlag = 0;

        socket.bind(bind);
        selectorKey = socket.register(selector, SelectionKey.OP_ACCEPT);
        logger.info("Bound and accepting connections on " + bind.toString());
    }

    // Serverside runner
    @Override
    public void run() {
        while (true) {
            try {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();

                    if (key.isAcceptable()) {
                        logger.info("New connection incoming");
                        accept();
                    }

                    if (key.isReadable()) {
                        Client subject = (Client) key.attachment();
                        logger.info("Client " + subject.socket.getRemoteAddress() + " data incoming");

                        try {
                            subject.read();
                        } catch (IOException ioe) {
                            logger.info("Client " + subject.socket.getRemoteAddress() + " disconnected");
                            subject.shutdown();
                            clientList.remove(subject);
                        }
                    } else if (key.isWritable()) {
                        Client subject = (Client) key.attachment();
                        logger.info("Client " + subject.socket.getRemoteAddress() + " data outgoing");
                        subject.write();
                    }

                    keyIterator.remove();
                }
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    void accept() throws IOException {
        operationLock.lock();

        SocketChannel incoming = socket.accept();
        if (incoming != null) {
            logger.info("New client " + incoming.getRemoteAddress());

            Client wrapper = new Client(selector, incoming, window);
            clientList.add(wrapper);
        } else {
            logger.info("No connection available");
        }

        operationLock.unlock();
    }
}
