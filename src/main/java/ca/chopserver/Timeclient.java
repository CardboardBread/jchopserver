package ca.chopserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static ca.chopserver.Packet.ENQUIRY;
import static ca.chopserver.Packet.NULL;

public class Timeclient {

    public static void main(String[] args) throws IOException, InterruptedException {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        rootLogger.addHandler(new ImmediateStreamHandler(System.out, new LogFormatter()));

        InetSocketAddress target = new InetSocketAddress("127.0.0.1", 50001);
        Client instance = new Client(target, 255);
        Thread thread = new Thread(instance);
        thread.start();

        while (true) {
            // calculate how long until the start of the next minute
            long time = System.currentTimeMillis();
            long curr_minute = (time / 1000 ) / 60;
            long next_minute = curr_minute + 1;
            long next_time = (next_minute * 1000) * 60;
            long delta = next_time - time;
            instance.logger.info("Current time is " + time + ", next minute is " + next_minute + ", delta is " + delta);

            // wait until the beginning of the next minute, and send a ping on wakeup
            Thread.sleep(delta);
            instance.send(new byte[] {NULL, ENQUIRY, NULL, NULL});
        }
    }

}
