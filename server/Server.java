package server;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

import utils.*;

public class Server {
    private static int DEFAULT_PORT = NetworkConfig.DEFAULT_SERVER_PORT;
    private static int SLAVE_PORT = NetworkConfig.DEFAULT_SLAVE_PORT;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    public ServerInterface serverInterface;
    private static int BUFFER_SIZE = NetworkConfig.BUFFER_SIZE;
    
    // Gestion des Slaves
    private final Map<String, SlaveInfo> connectedSlaves = new ConcurrentHashMap<>();
    private final ScheduledExecutorService slaveMonitor = Executors.newScheduledThreadPool(1);
    
    // Gestion des fichiers
    private final Map<String, FilePartitionInfo> filePartitions = new ConcurrentHashMap<>();

    public ServerInterface getServerInterface() {
        return serverInterface;
    }

    private static class SlaveInfo {
        String address;
        int port;
        long availableSpace;
        List<String> storedPartitions;
        long lastSeen;

        public SlaveInfo(String address, int port) {
            this.address = address;
            this.port = port;
            this.storedPartitions = new ArrayList<>();
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private static class FilePartitionInfo {
        String fileName;
        int totalParts;
        Map<Integer, String> partitionToSlave; // Partition number -> Slave address

        public FilePartitionInfo(String fileName) {
            this.fileName = fileName;
            this.partitionToSlave = new HashMap<>();
        }
    }

    public Server() {
        try {
            NetworkConfig.loadConfigurations();
            DEFAULT_PORT = NetworkConfig.DEFAULT_SERVER_PORT;
            SLAVE_PORT = NetworkConfig.DEFAULT_SLAVE_PORT;
            BUFFER_SIZE = NetworkConfig.BUFFER_SIZE;

            initializeServer();
            serverInterface = new ServerInterface(this);

        } catch (Exception e) {
            FileTransferLoggerServer.logError("Erreur d'initialisation du serveur", e);
        }
    }

    private void initializeServer() {
        // Démarrage du monitoring des slaves
        slaveMonitor.scheduleAtFixedRate(this::checkSlaveHealth, 30, 30, TimeUnit.SECONDS);
    }

    public void startServer() {
        if (isRunning) return;

        try {            
            serverSocket = new ServerSocket(DEFAULT_PORT);
            isRunning = true;
            
            // Démarre la découverte des slaves
            startSlaveDiscovery();
            
            // Démarre le traitement des requêtes clients
            new Thread(this::handleClientConnections).start();
            
            FileTransferLoggerServer.log("Serveur démarré sur le port " + DEFAULT_PORT);
        } catch (IOException e) {
            FileTransferLoggerServer.logError("Erreur de démarrage du serveur", e);
        }
    }

    private void startSlaveDiscovery() {
        new Thread(() -> {
                try {
                    broadcastDiscoveryMessage();
                    Thread.sleep(NetworkConfig.CONNECTION_TIMEOUT); // Découverte toutes les 5 secondes si CONNECTION_TIMEOUT = 5000
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
        }).start();
        notifyFileListUpdate();
        notifySlaveListUpdate();
    }

    private void broadcastDiscoveryMessage() throws InterruptedException {
        try (DatagramSocket socket = new DatagramSocket(NetworkConfig.BROADCAST_PORT)) {
            socket.setBroadcast(true);
            
            String discoveryMessage = NetworkConfig.DISCOVER_SLAVES_MESSAGE + ":" + DEFAULT_PORT;
            byte[] buffer = discoveryMessage.getBytes();
            
            // Broadcast sur le réseau local
            DatagramPacket packet = new DatagramPacket(
                buffer, buffer.length,
                InetAddress.getByName(NetworkConfig.BROADCAST_ADDRESS),
                SLAVE_PORT
            );
            
            socket.send(packet);

            // Attente des réponses
            listenForSlaveResponses(socket);
            
        } catch (IOException e) {
            FileTransferLoggerServer.logError("Erreur lors de la découverte des slaves", e);
        }
    }

    private void listenForSlaveResponses(DatagramSocket socket) throws InterruptedException {
        try {
            socket.setSoTimeout(NetworkConfig.BROADCAST_INTERVAL); // Timeout de 2.5 secondes pour les réponses
            byte[] receiveBuffer = new byte[1024];
            
            while (true) {
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                socket.receive(receivePacket);
                
                String response = new String(receivePacket.getData(), 0, receivePacket.getLength());
                if (response.startsWith(NetworkConfig.READY_SLAVES_MESSAGE)) {
                    String slaveAddress = receivePacket.getAddress().getHostAddress();
                    int slavePort = Integer.parseInt(response.split(":")[1]);
                    // Ajouter ou mettre à jour le slave
                    updateSlaveInfo(slaveAddress, slavePort);
                }
            }
        } catch (SocketTimeoutException e) {
            // Timeout normal, fin de la découverte
        } catch (IOException e) {
            FileTransferLoggerServer.logError("Erreur lors de la réception des réponses des slaves", e);
        }
    }

    private void updateSlaveInfo(String address, int port) throws InterruptedException {
        SlaveInfo slave = connectedSlaves.computeIfAbsent(
            address, k -> new SlaveInfo(address, port)
        );
        
        // Mise à jour du timestamp
        slave.lastSeen = System.currentTimeMillis();
        
        // Pour LIST_PARTITIONS
        try (Socket socket = new Socket(address, port);
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            dos.writeUTF("LIST_PARTITIONS");
            dos.flush();

            String partition;

            while (!(partition = dis.readUTF()).equals("END_OF_LIST")) {
                slave.storedPartitions.add(partition);

                FilePartitionInfo fileInfo = new FilePartitionInfo(partition);
                if(partition.contains(".part")){
                    fileInfo = new FilePartitionInfo(partition.split("\\.part")[1]);
                    int partitionNumber = Integer.parseInt(partition.split("\\.part")[1]);
                    fileInfo.partitionToSlave.put(partitionNumber , address);
                }
                filePartitions.put(partition , fileInfo);
            }
        }catch (IOException e) {
            FileTransferLoggerServer.logError("Erreur lors de la mise à jour de la liste des partitions du slave: " + address, e);
        }

        // Récupération de l'espace disponible
        try (Socket socket = new Socket(address, port);
        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
        DataInputStream dis = new DataInputStream(socket.getInputStream())) {

        dos.writeUTF("GET_SPACE");
        dos.flush();

        long availableSpace = dis.readLong();

        } catch (IOException e) {
            FileTransferLoggerServer.logError("Erreur lors de la mise à jour de l'espace disponible du slave: " + address, e);
        }
        notifySlaveListUpdate();
        notifyFileListUpdate();
    }

    private void checkSlaveHealth() {
        long now = System.currentTimeMillis();
        connectedSlaves.entrySet().removeIf(entry -> {
            boolean inactive = (now - entry.getValue().lastSeen) > NetworkConfig.SLAVE_TIMEOUT; // 60 secondes
            if (inactive) {
                handleSlaveDisconnection(entry.getKey(), entry.getValue());
            }
            return inactive;
        });
    }

    private void handleSlaveDisconnection(String address, SlaveInfo slave) {
        FileTransferLoggerServer.log("Slave déconnecté: " + address);
        // Mettre à jour la table des partitions
        for (FilePartitionInfo fileInfo : filePartitions.values()) {
            fileInfo.partitionToSlave.entrySet().removeIf(e -> e.getValue().equals(address));
        }
    }

    private void handleClientConnections() {
        while (isRunning) {
            try {
                Socket clientSocket = serverSocket.accept();
                new ClientHandler(clientSocket).start();
            } catch (IOException e) {
                if (isRunning) {
                    FileTransferLoggerServer.logError("Erreur d'acceptation de connexion client", e);
                }
            }
        }
    }    

    private class ClientHandler extends Thread {
        private final Socket clientSocket;
        private String command;

        public ClientHandler(Socket socket) {
            this.clientSocket = socket;
        }

        @Override
        public void run() {
            try (DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                 DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {

                command = in.readUTF();
                switch (command) {
                    case "UPLOAD":
                        handleFileUpload(in);
                        out.writeUTF("SUCCESS");
                        out.flush();
                        notifyFileListUpdate();
                        break;
                    case "DOWNLOAD":
                        handleFileDownload(in, out);
                        break;
                    case "LIST_FILES":
                        sendFileList(out);
                        break;
                    case "DELETE_FILE":
                        String fileToDelete = in.readUTF();
                        boolean deleted = deleteFileAndPartitions(fileToDelete);
                        out.writeUTF(deleted ? "SUCCESS" : "ERROR");
                        out.flush();
                        notifyFileListUpdate();
                        break;
                }
            } catch (IOException e) {
                FileTransferLoggerServer.logError("Erreur de traitement de la requête client", e);
            }
        }

        private void handleFileUpload(DataInputStream in) throws IOException {
            String fileName = in.readUTF();
            long fileSize = in.readLong();

            // Calculer le nombre de partitions en fonction des slaves disponibles
            List<SlaveInfo> availableSlaves = new ArrayList<>(connectedSlaves.values());

            if (availableSlaves.isEmpty()) {
                throw new IOException("Aucun slave disponible pour le stockage");
            }

            int numPartitions = availableSlaves.size(); 
            long partitionSize = fileSize / numPartitions;

            FilePartitionInfo fileInfo = new FilePartitionInfo(fileName);
            fileInfo.totalParts = numPartitions;

            // Distribuer les partitions aux slaves
            for (int i = 0; i < numPartitions; i++) {
                SlaveInfo slave = availableSlaves.get(i);
                if (slave == null) {
                    throw new IOException("Espace insuffisant sur les slaves");
                }

                // Envoyer la partition au slave
                try (Socket slaveSocket = new Socket(slave.address, slave.port)) {
                    DataOutputStream slaveOut = new DataOutputStream(slaveSocket.getOutputStream());
                    
                    slaveOut.writeUTF("STORE_PARTITION");
                    slaveOut.flush();
                    slaveOut.writeUTF(fileName);
                    slaveOut.flush();
                    slaveOut.writeInt(i + 1);
                    slaveOut.flush();
                    slaveOut.writeLong(partitionSize);
                    slaveOut.flush();
                    
                    // Copier les données de la partition
                    copyPartitionData(in, slaveOut, partitionSize);
                    fileInfo.partitionToSlave.put(i + 1, slave.address);

                    filePartitions.put(fileName + ".part" + (i + 1), fileInfo);
                }

                if(i+1 < numPartitions){
                    slave = availableSlaves.get(i+1);
                    try (Socket slaveSocket = new Socket(slave.address, slave.port)) {
                        DataOutputStream slaveOut = new DataOutputStream(slaveSocket.getOutputStream());
                        
                        slaveOut.writeUTF("STORE_PARTITION");
                        slaveOut.flush();
                        slaveOut.writeUTF(fileName);
                        slaveOut.flush();
                        slaveOut.writeInt(i+1);
                        slaveOut.flush();
                        slaveOut.writeLong(partitionSize);
                        slaveOut.flush();
                        
                        // Copier les données de la partition
                        copyPartitionData(in, slaveOut, partitionSize);
                        fileInfo.partitionToSlave.put(i + 1, slave.address);

                        filePartitions.put(fileName + ".part" + (i + 1), fileInfo);                        
                    }
                } else if (i+1 > numPartitions && numPartitions > 1){
                    slave = availableSlaves.get(0);
                    try (Socket slaveSocket = new Socket(slave.address, slave.port)) {
                        DataOutputStream slaveOut = new DataOutputStream(slaveSocket.getOutputStream());
                        
                        slaveOut.writeUTF("STORE_PARTITION");
                        slaveOut.flush();
                        slaveOut.writeUTF(fileName);
                        slaveOut.flush();
                        slaveOut.writeInt(i+1);
                        slaveOut.flush();
                        slaveOut.writeLong(partitionSize);
                        slaveOut.flush();
                        
                        // Copier les données de la partition
                        copyPartitionData(in, slaveOut, partitionSize);
                        fileInfo.partitionToSlave.put(i+1, slave.address);

                        filePartitions.put(fileName + ".part" + (i + 1), fileInfo);
                    }
                }

            }
        }

        /*
        private SlaveInfo selectBestSlaveForPartition(long partitionSize) {
            return connectedSlaves.values().stream()
                .filter(slave -> slave.availableSpace >= partitionSize)
                .max(Comparator.comparingLong(slave -> slave.availableSpace))
                .orElse(null);
        }fileName
        */

        private void copyPartitionData(DataInputStream in, DataOutputStream out, long size) throws IOException {
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = size;
            
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) break;
                out.write(buffer, 0, read);
                out.flush();
                remaining -= read;
            }
        }


        private void handleFileDownload(DataInputStream in, DataOutputStream out) throws IOException {
            String fileName;
            try {
                fileName = in.readUTF();
            } catch (EOFException e) {
                FileTransferLoggerServer.logError("Connexion interrompue : fin de flux inattendue.", e);
                out.writeBoolean(false);
                out.flush();
                return;
            }
            
            List<FilePartitionInfo> fileInfoList = new ArrayList<>();
            Set<Integer> parts = new HashSet<>();

            for (String key : filePartitions.keySet()) {
                if (key.contains(fileName + ".part")) {
                    fileInfoList.add(filePartitions.get(key));
                    parts.add(Integer.parseInt(key.split("\\.part")[1]));
                }
            }
            
            if (fileInfoList.isEmpty()) {
                out.writeBoolean(false);
                out.flush();
                return;
            }
            
            out.writeBoolean(true);
            out.flush();
            
            // Vérifier que toutes les partitions sont présentes
            boolean hasAllPartitions = true;
            long totalSize = 0;
            Map<Integer, Long> partitionSizes = new TreeMap<>(); // Pour garder l'ordre des partitions
            
            // Calculer d'abord la taille totale en vérifiant chaque partition
            
            int iPart = 1;
            for (int i = 0; iPart <= parts.size(); i++) {
                System.out.println("iPart = " + iPart);
                System.out.println("i = " + i);

                if(i >= fileInfoList.size()) { i = 0;}

                String slaveAddress = fileInfoList.get(i).partitionToSlave.get(iPart);
                if (slaveAddress == null) {
                    /*
                    FileTransferLoggerServer.log("Partition " + i + " non référencée pour " + fileName);
                    hasAllPartitions = false;
                    break;
                    */
                    continue;
                }

                try (Socket slaveSocket = new Socket(slaveAddress, SLAVE_PORT)) {
                    DataOutputStream slaveOut = new DataOutputStream(slaveSocket.getOutputStream());
                    DataInputStream slaveIn = new DataInputStream(slaveSocket.getInputStream());
                    
                    slaveOut.writeUTF("GET_PARTITION_SIZE");
                    slaveOut.writeUTF(fileName);
                    slaveOut.writeInt(iPart);
                    slaveOut.flush();
                    
                    long partSize = slaveIn.readLong();
                    
                    if (partSize <= 0) {
                        hasAllPartitions = false;
                        break;
                    }
                    
                    partitionSizes.put(iPart, partSize);
                    totalSize += partSize;
                    iPart++;
                } catch (IOException e) {
                    FileTransferLoggerServer.logError("Erreur lors de la vérification de la partition " + i + " sur " + slaveAddress, e);
                    hasAllPartitions = false;
                    break;
                }
            }
            
            if (!hasAllPartitions || totalSize <= 0) {
                FileTransferLoggerServer.log("Erreur : Fichier incomplet ou taille totale invalide (" + totalSize + "). Il se peut que le(s) slave(s) contenant des parties du fichier ne soient pas actifs.");
                out.writeLong(-1);
                out.writeLong(totalSize);
                out.flush();
                return;
            }
            
            // Envoyer la taille totale
            out.writeLong(totalSize);
            out.flush();
            
            for(int i = 0; i < fileInfoList.size(); i++){
                // Récupérer et envoyer chaque partition dans l'ordre
                for (Map.Entry<Integer, Long> entry : partitionSizes.entrySet()) {
                    int partNum = entry.getKey();
                    String slaveAddress = fileInfoList.get(i).partitionToSlave.get(partNum);
                    retrievePartitionFromSlave(slaveAddress, fileName, partNum, out);
                }
            }
        }

        private void retrievePartitionFromSlave(String slaveAddress, String fileName, int partNum, DataOutputStream clientOut) 
            throws IOException {
            try (Socket slaveSocket = new Socket(slaveAddress, SLAVE_PORT)) {
                DataOutputStream slaveOut = new DataOutputStream(slaveSocket.getOutputStream());
                DataInputStream slaveIn = new DataInputStream(slaveSocket.getInputStream());

                slaveOut.writeUTF("RETRIEVE_PARTITION");
                slaveOut.flush();
                slaveOut.writeUTF(fileName);
                slaveOut.flush();
                slaveOut.writeInt(partNum);
                slaveOut.flush();

                if (!slaveIn.readBoolean()) {
                    throw new IOException("Partition non trouvée sur le slave");
                }

                // Copier les données vers le client
                long partitionSize = slaveIn.readLong();
                copyPartitionData(slaveIn, clientOut, partitionSize);
            }
        }

        private void sendFileList(DataOutputStream out) throws IOException {
            Set<String> files = new HashSet<String>();
            filePartitions.keySet().forEach(filePart -> {
                String fileName = filePart;
                if(filePart.contains(".part")) { fileName = filePart.split("\\.part")[0]; }
                files.add(fileName);
            });

            for (String fileName : files) {
                out.writeUTF(fileName);
                out.flush();
            }
            
            out.writeUTF("END_OF_LIST");
            out.flush();
        }

        private boolean deleteFileAndPartitions(String fileName) {
            FilePartitionInfo fileInfo = null;

            for (Map.Entry<String, FilePartitionInfo> entry : filePartitions.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(fileName)) {
                    fileInfo = entry.getValue();
                    break;
                }
            }

            if (fileInfo == null) return false; // Le fichier n'existe pas
        
            // Supprimer toutes les partitions associées au fichier
            for (Map.Entry<Integer, String> entry : fileInfo.partitionToSlave.entrySet()) {
                String slaveAddress = entry.getValue();
                int partitionNumber = entry.getKey();
        
                try (Socket slaveSocket = new Socket(slaveAddress, NetworkConfig.DEFAULT_SLAVE_PORT);
                     DataOutputStream slaveOut = new DataOutputStream(slaveSocket.getOutputStream())) {
                    
                    slaveOut.writeUTF("DELETE_PARTITION");
                    slaveOut.flush();
                    slaveOut.writeUTF(fileName);
                    slaveOut.flush();
                    slaveOut.writeInt(partitionNumber);
                    slaveOut.flush();
                } catch (IOException e) {
                    FileTransferLoggerServer.logError("Erreur lors de la suppression de la partition " 
                        + partitionNumber + " sur " + slaveAddress, e);
                }
            }
        
            // Retirer le fichier du serveur
            filePartitions.keySet().forEach(filePart ->{
                if(filePart.contains(".part")){
                    if(filePart.split("\\.part")[0].equals(fileName)){
                        filePartitions.remove(filePart);
                    }
                } else if (filePart.equals(fileName)){
                    filePartitions.remove(filePart);
                }
            });
            
            return true;
        }
        
    }

    public void notifyFileListUpdate() {
        List<String> files = new ArrayList<>(filePartitions.keySet());
        serverInterface.updateFileList(files);
    }
    
    public void notifySlaveListUpdate() {
        List<String> slaves = new ArrayList<>(connectedSlaves.keySet());
        serverInterface.updateSlaveList(slaves);
    }

    public class ServerInterface extends JFrame {
        private JList<String> fileList;
        private DefaultListModel<String> fileListModel;
        private JList<String> slaveList;
        private DefaultListModel<String> slaveListModel;
        private JButton startButton;
        private JButton stopButton;
        private final Server server;
    
        public ServerInterface(Server server) {
            this.server = server;
            initComponents();
        }
    
        private void initComponents() {
            setTitle("Serveur Principal");
            setSize(600, 400);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    
            // Control Panel
            JPanel controlPanel = new JPanel(new FlowLayout());
            startButton = new JButton("Démarrer le Serveur");
            stopButton = new JButton("Arrêter le Serveur");
            stopButton.setEnabled(false);
    
            startButton.addActionListener(e -> {
                server.startServer();
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
            });
    
            stopButton.addActionListener(e -> {
                server.stopServer();
                slaveListModel.clear();
                stopButton.setEnabled(false);
                startButton.setEnabled(true);
            });
    
            controlPanel.add(startButton);
            controlPanel.add(stopButton);
    
            // File List Panel
            JPanel filePanel = new JPanel(new BorderLayout());
            fileListModel = new DefaultListModel<>();
            fileList = new JList<>(fileListModel);
            filePanel.add(new JScrollPane(fileList), BorderLayout.CENTER);
            filePanel.setBorder(BorderFactory.createTitledBorder("Fichiers Disponibles"));
    
            // Slave List Panel
            JPanel slavePanel = new JPanel(new BorderLayout());
            slaveListModel = new DefaultListModel<>();
            slaveList = new JList<>(slaveListModel);
            slavePanel.add(new JScrollPane(slaveList), BorderLayout.CENTER);
            slavePanel.setBorder(BorderFactory.createTitledBorder("Slaves Connectés"));
            
            // Ajouter les deux panneaux au frame principal
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, filePanel, slavePanel);
            splitPane.setDividerLocation(300);
            getContentPane().add(splitPane, BorderLayout.CENTER);

            // Ajouter le panneau de contrôle en bas
            getContentPane().add(controlPanel, BorderLayout.SOUTH);

            // Layout
            setLayout(new BorderLayout());
            add(controlPanel, BorderLayout.NORTH);
            add(filePanel, BorderLayout.CENTER);
            add(slavePanel, BorderLayout.SOUTH);
        }
    
        public void updateFileList(List<String> files) {
            SwingUtilities.invokeLater(() -> {
                fileListModel.clear();
                files.forEach(fileListModel::addElement);
            });
        }
    
        public void updateSlaveList(List<String> slaves) {
            SwingUtilities.invokeLater(() -> {
                slaveListModel.clear();
                slaves.forEach(slaveListModel::addElement);
            });
        }
    }   

    public void stopServer() {
        if (!isRunning) return; // Empêche d'arrêter un serveur déjà arrêté
    
        isRunning = false;
    
        try {
            // Arrête le serverSocket pour ne plus accepter de connexions
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
    
            // Arrête le monitoring des slaves
            slaveMonitor.shutdownNow();
    
            // Supprime la liste des slaves connectés et de leurs partitions de fichiers
            connectedSlaves.clear();
            filePartitions.clear();
            notifyFileListUpdate();

    
            // Log de l'arrêt
            FileTransferLoggerServer.log("Serveur arrêté.");
        } catch (IOException e) {
            FileTransferLoggerServer.logError("Erreur lors de l'arrêt du serveur", e);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Server server = new Server();
            ServerInterface serverInterface = server.new ServerInterface(server);
            server.serverInterface = serverInterface; // Associez l'interface utilisateur au serveur
            serverInterface.setVisible(true);
        });
    }
    
    
}