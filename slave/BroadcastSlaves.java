package slave;

import utils.FileTransferLoggerSlave;
import utils.NetworkConfig;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class BroadcastSlaves extends Thread {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int serverPort;
    private DatagramSocket socket;

    public BroadcastSlaves(int serverPort) {
        super("BroadcastSlaves-Thread");
        this.serverPort = serverPort;
        setDaemon(true); // Le thread s'arrêtera quand tous les threads non-daemon seront terminés
    }

    @Override
    public void run() {
        try (DatagramSocket broadcastSocket = new DatagramSocket()) {
            this.socket = broadcastSocket;
            socket.setBroadcast(true);
            
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    broadcastDiscoveryMessage();
                    Thread.sleep(NetworkConfig.BROADCAST_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    FileTransferLoggerSlave.logError("Erreur lors de la diffusion", e);
                    // Attendre un peu avant de réessayer
                    Thread.sleep(1000);
                }
            }
        } catch (Exception e) {
            FileTransferLoggerSlave.logError("Erreur fatale dans BroadcastSlaves", e);
        } finally {
            cleanup();
        }
    }

    private void broadcastDiscoveryMessage() throws IOException {
        byte[] buffer = createDiscoveryMessage();
        DatagramPacket packet = new DatagramPacket(
            buffer, 
            buffer.length,
            InetAddress.getByName(NetworkConfig.BROADCAST_ADDRESS), 
            NetworkConfig.BROADCAST_PORT
        );
        socket.send(packet);
    }

    private byte[] createDiscoveryMessage() {
        return String.format("%s:%d:%d", 
            NetworkConfig.READY_SLAVES_MESSAGE, 
            NetworkConfig.DEFAULT_SLAVE_PORT,
            System.currentTimeMillis()
        ).getBytes();
    }

    public void shutdown() {
        running.set(false);
        interrupt();
        cleanup();
    }

    private void cleanup() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}