package ca.chopserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class ReadServer {

    public static void main(String[] args) throws IOException {

        List<SelectionKey> clientKeys = new ArrayList<>();

        Selector selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 5656);

        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.configureBlocking(false);

        serverSocket.bind(address);

        String threadName = Thread.currentThread().getName();
        System.out.println(threadName + " hosting on: " + address);
        SelectionKey serverKey = serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            selector.select();

            if (serverKey.isAcceptable()) {
                SocketChannel client = serverSocket.accept();

                if (client != null) {
                    client.configureBlocking(false);
                    SocketAddress remoteAddress = client.getRemoteAddress();
                    System.out.println("Accepted connection from: " + remoteAddress);
                    clientKeys.add(client.register(selector, SelectionKey.OP_READ));
                }
            }

            for (SelectionKey clientKey : clientKeys) {
                if (clientKey.isReadable()) {
                    SocketChannel client = (SocketChannel) clientKey.channel();
                    StringBuilder raw = new StringBuilder();
                    StringBuilder text = new StringBuilder();

                    int bytesRead;
                    do {
                        ByteBuffer buffer = ByteBuffer.allocate(48);
                        bytesRead = client.read(buffer);
                        byte[] out = new byte[bytesRead];
                        buffer.rewind();
                        buffer.get(out, 0, bytesRead);
                        text.append(new String(out));
                        raw.append(Arrays.toString(out));
                    } while (bytesRead > 0);

                    SocketAddress remoteAddress = client.getRemoteAddress();
                    if (bytesRead == -1) {
                        System.out.println("Connection Closed by: " + remoteAddress);
                        client.close();
                    } else {
                        System.out.println("Received from: " + remoteAddress);
                        System.out.println(raw.toString());
                        System.out.println(text.toString());
                    }
                }
            }
        }
    }
}
