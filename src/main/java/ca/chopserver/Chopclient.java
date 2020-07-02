package ca.chopserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;

public class Chopclient {

    public static void main(String[] args) throws IOException {

        int window = 255;
        SocketChannel socketChannel = SocketChannel.open();
        Client client = new Client(Selector.open(), socketChannel, window);
        client.socket.configureBlocking(false);
        client.socket.connect(new InetSocketAddress("127.0.0.1", 2000));
        client.selectorKey = client.socket.register(client.selector, SelectionKey.OP_READ, client);

        Scanner console = new Scanner(System.in);

        Runnable inout = new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        int nReady = client.selector.select();
                        Set<SelectionKey> selectedKeys = client.selector.selectedKeys();
                        Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                        while (keyIterator.hasNext()) {
                            SelectionKey key = keyIterator.next();

                            if (key.isReadable()) {
                                Chopserver.read((Client) key.attachment());
                            }

                            if (key.isWritable()) {
                                Chopserver.write((Client) key.attachment());
                            }

                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        Thread runner = new Thread(inout);
        runner.start();

        while (true) {
            String input = console.nextLine();
            byte[] text = input.getBytes();
            ByteBuffer data;

            switch (input) {
                case "exit":
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.ESCAPE, Chopserver.NULL, Chopserver.NULL});
                    break;
                case "ping":
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.ENQUIRY, Chopserver.NORMAL, Chopserver.NULL});
                    break;
                case "pingret":
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.ENQUIRY, Chopserver.RETURN, Chopserver.NULL});
                    break;
                case "pingtime":
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.ENQUIRY, Chopserver.TIME, Chopserver.NULL});
                    break;
                case "pingtimeret":
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.ENQUIRY, Chopserver.RETURN_TIME, Long.BYTES});
                    data.putLong(System.currentTimeMillis());
                    break;
                case "sleep":
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.IDLE, Chopserver.NULL, Chopserver.NULL});
                    break;
                case "wake":
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.WAKEUP, Chopserver.NULL, Chopserver.NULL});
                    break;
                default:
                    data = ByteBuffer.allocate(Chopserver.HEADER_LEN + text.length);
                    data.put(new byte[]{Chopserver.NULL, Chopserver.START_TEXT, 1, (byte) text.length});
                    data.put(text);
                    break;
            }

            client.operationLock.lock();
            Chopserver.send(client, data.array());
            client.operationLock.unlock();
        }

    }

}
