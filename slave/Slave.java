package slave;

import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.Timer;
import utils.FileTransferLoggerSlave;
import utils.NetworkConfig;

public class Slave {
    private static int DEFAULT_PORT = NetworkConfig.DEFAULT_SLAVE_PORT;
    private static String DATA_DIRECTORY = NetworkConfig.SLAVE_DATA_DIRECTORY;
    private ServerSocket serverSocket;
    private BroadcastSlaves broadcastSlave;
    private SlaveInterface slaveInterface = null;
    private volatile boolean isRunning = false;
    private List<String> availableFiles = new ArrayList<>();
    private String slaveId;
    private static int BUFFER_SIZE = NetworkConfig.BUFFER_SIZE;

    public Slave() {
        try {
            this.slaveId = UUID.randomUUID().toString();

            NetworkConfig.loadConfigurations();
            DEFAULT_PORT = NetworkConfig.DEFAULT_SLAVE_PORT;
            DATA_DIRECTORY = NetworkConfig.SLAVE_DATA_DIRECTORY;
            BUFFER_SIZE = NetworkConfig.BUFFER_SIZE;

            initializeStorage();
            slaveInterface = new SlaveInterface(this);
            scanAvailableFiles();
        } catch (Exception e) {
            FileTransferLoggerSlave.logError("Erreur de lancement du serveur de stockage", e);
        }
    }

    private void initializeStorage() {
        try {
            Path storagePath = Paths.get(DATA_DIRECTORY);
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
            }
        } catch (IOException e) {
            FileTransferLoggerSlave.logError("Erreur d'initialisation du stockage", e);
        }
    }

    public void startServer() {
        if (isRunning) return;
        
        try {
            serverSocket = new ServerSocket(DEFAULT_PORT);
            isRunning = true;
            scanAvailableFiles();
            FileTransferLoggerSlave.log("Serveur de stockage démarré sur le port " + DEFAULT_PORT);

            // Démarrage du broadcast pour être découvert par le serveur principal
            broadcastSlave = new BroadcastSlaves(DEFAULT_PORT);
            broadcastSlave.start();

            // Écoute des connexions dans un thread séparé
            new Thread(this::listenForConnections).start();
        } catch (IOException e) {
            FileTransferLoggerSlave.logError("Erreur de démarrage du serveur de stockage", e);
            JOptionPane.showMessageDialog(null,
                "Impossible de démarrer le serveur : " + e.getMessage(),
                "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void listenForConnections() {
        while (isRunning) {
            try {
                Socket socket = serverSocket.accept();
                new StorageHandler(socket).start();
            } catch (IOException e) {
                if (isRunning) {
                    FileTransferLoggerSlave.logError("Erreur d'acceptation de connexion", e);
                }
            }
        }
    }

    private void scanAvailableFiles() {
        availableFiles.clear();
        // Ensemble pour stocker les noms de fichiers uniques
        Set<String> uniqueFiles = new HashSet<>();
    
        Path partitionPath = Paths.get(DATA_DIRECTORY);
        try {
            Files.walk(partitionPath)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    String fileName = path.getFileName().toString();
                    // Extraire le nom de fichier original (avant **part)
                    String originalFileName = fileName;
                    uniqueFiles.add(originalFileName);
                });
        } catch (IOException e) {
            FileTransferLoggerSlave.logError("Erreur de scan des fichiers", e);
        }

        // Ajouter les noms de fichiers uniques dans availableFiles
        availableFiles.addAll(uniqueFiles);
        slaveInterface.updateFileList(availableFiles);
    }

    public void stopServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (broadcastSlave != null) {
                broadcastSlave.interrupt();
            }
            isRunning = false;
        } catch (IOException e) {
            FileTransferLoggerSlave.logError("Erreur d'arrêt du serveur", e);
        }
    }

    public SlaveInterface getSlaveInterface() {
        return slaveInterface;
    }

    private class StorageHandler extends Thread {
        private final Socket socket;

        public StorageHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (DataInputStream dis = new DataInputStream(socket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

                String command = dis.readUTF();

                switch (command) {
                    case "STORE_PARTITION":
                        handlePartitionStorage(dis);
                        scanAvailableFiles();
                        break;
                    case "RETRIEVE_PARTITION":
                        handlePartitionRetrieval(dis, dos);
                        break;
                    case "GET_SPACE":
                        sendAvailableSpace(dos);
                        break;
                    case "LIST_PARTITIONS":
                        sendPartitionsList(dos);
                        break;
                    case "GET_PARTITION_SIZE":
                        sendPartitionSize(dis , dos);
                        break;
                    case "DELETE_PARTITION":
                        handlePartitionDeletion(dis);
                        scanAvailableFiles(); // Mettre à jour la liste des fichiers après suppression
                        break;
                }

            } catch (IOException e) {
                FileTransferLoggerSlave.logError("Erreur de traitement de la requête", e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    FileTransferLoggerSlave.logError("Erreur de fermeture de socket", e);
                }
            }
        }

        private void handlePartitionStorage(DataInputStream dis) throws IOException {
            String fileName = dis.readUTF();
            int partitionNumber = dis.readInt();
            long partitionSize = dis.readLong();

            String partitionFileName = null;
            partitionFileName = fileName + ".part" + partitionNumber;

            Path partitionPath = Paths.get(DATA_DIRECTORY, partitionFileName);

            // Réception et stockage des données
            try (FileOutputStream fos = new FileOutputStream(partitionPath.toFile())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long remainingBytes = partitionSize;
                
                while (remainingBytes > 0) {
                    int bytesToRead = (int) Math.min(buffer.length, remainingBytes);
                    int bytesRead = dis.read(buffer, 0, bytesToRead);
                    if (bytesRead == -1) break;
                    
                    fos.write(buffer, 0, bytesRead);
                    fos.flush();
                    remainingBytes -= bytesRead;
                }
            }
            
            FileTransferLoggerSlave.log("Partition " + partitionNumber + " du fichier " + fileName + " stockée");
        }

        private void handlePartitionRetrieval(DataInputStream dis, DataOutputStream dos) throws IOException {
            String fileName = dis.readUTF();
            int partitionNumber = dis.readInt();
            
            String partitionFileName = fileName + ".part" + partitionNumber;
            Path partitionPath = Paths.get(DATA_DIRECTORY, partitionFileName);
        
            if (!Files.exists(partitionPath)) {
                dos.writeBoolean(false);
                return;
            }
            
            long size = Files.size(partitionPath);
            dos.writeBoolean(true);
            dos.writeLong(size);
            
            // Envoi des données avec vérification
            try (FileInputStream fis = new FileInputStream(partitionPath.toFile())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long remainingBytes = size;
                
                while (remainingBytes > 0) {
                    int bytesToRead = (int) Math.min(buffer.length, remainingBytes);
                    int bytesRead = fis.read(buffer, 0, bytesToRead);
                    
                    if (bytesRead == -1) {
                        throw new IOException("Fin de fichier inattendue");
                    }
                    
                    dos.write(buffer, 0, bytesRead);
                    dos.flush();
                    remainingBytes -= bytesRead;
                }
                dos.flush();
            }
        }

        private void sendAvailableSpace(DataOutputStream dos) throws IOException {
            File storageDir = new File(DATA_DIRECTORY);
            long usableSpace = storageDir.getUsableSpace();
            dos.writeLong(usableSpace);
            dos.flush();
        }

        private void sendPartitionsList(DataOutputStream dos) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(DATA_DIRECTORY))) {
                List<String> listFileName = new ArrayList<String>();
                for (Path path : stream) {
                    if (Files.isRegularFile(path)) {
                        String fileName = path.getFileName().toString();
                        if(!listFileName.contains(fileName)){
                            dos.writeUTF(fileName);
                            dos.flush();
                            listFileName.add(fileName);
                        }
                    }
                }
                dos.writeUTF("END_OF_LIST");
                dos.flush();
            } catch (IOException e) {
                FileTransferLoggerSlave.logError("Erreur dans sendPartitionsList", e);
            }
        }

        private void sendPartitionSize(DataInputStream dis, DataOutputStream dos) throws IOException {
            String fileName = dis.readUTF();
            int partitionNumber = dis.readInt();
            String partitionFileName = fileName + ".part" + partitionNumber;
            Path partitionPath = Paths.get(DATA_DIRECTORY, partitionFileName);
        
            // Log pour le chemin demandé
            FileTransferLoggerSlave.log("Chemin demandé : " + partitionPath.toString());
            System.out.println("Chemin complet : " + partitionPath.toAbsolutePath());
        
            // Vérifier si le fichier existe
            if (!Files.exists(partitionPath)) {
                FileTransferLoggerSlave.logError("Partition introuvable : " + partitionFileName, null);
                System.out.println("ERREUR : Partition introuvable " + partitionPath.toAbsolutePath());
                dos.writeLong(-1); // Taille négative pour indiquer l'erreur
                dos.flush();
                return;
            }
        
            try {
                long fileSize = Files.size(partitionPath);
                if (fileSize <= 0) {
                    FileTransferLoggerSlave.logError("Partition vide ou taille invalide : " + partitionFileName, null);
                    System.out.println("ERREUR : Taille invalide pour " + partitionFileName);
                    dos.writeLong(-1);
                } else {
                    dos.writeLong(fileSize);
                    FileTransferLoggerSlave.log("Taille de la partition " + partitionFileName + " : " + fileSize);
                    System.out.println("SUCCESS : Taille de " + partitionFileName + " est " + fileSize);
                }
            } catch (IOException e) {
                FileTransferLoggerSlave.logError("Erreur de lecture de taille pour : " + partitionFileName, e);
                System.out.println("ERREUR : Impossible de lire la taille de " + partitionFileName + " - " + e.getMessage());
                dos.writeLong(-1);
            }
        
            dos.flush();
        }
        
        
        

        private void handlePartitionDeletion(DataInputStream dis) throws IOException {
            String fileName = dis.readUTF();  // Nom du fichier
            int partitionNumber = dis.readInt();  // Numéro de la partition
        
            // Construire le chemin du fichier à supprimer
            String partitionFileName = fileName + ".part" + partitionNumber;
            Path partitionPath = Paths.get(DATA_DIRECTORY, partitionFileName);
        
            try {
                if (Files.exists(partitionPath)) {
                    Files.delete(partitionPath);
                    FileTransferLoggerSlave.log("Partition " + partitionNumber + " du fichier " + fileName + " supprimée.");
                } else {
                    FileTransferLoggerSlave.log("Partition " + partitionNumber + " du fichier " + fileName + " introuvable.");
                }
            } catch (IOException e) {
                FileTransferLoggerSlave.logError("Erreur lors de la suppression de la partition " + partitionNumber, e);
            }
        }
        
        
    }

    public class SlaveInterface extends JFrame {
        private final JTextArea logArea;
        private final JButton startStopButton;
        private final JLabel statusLabel;
        private final JLabel spaceLabel;
        private final DefaultListModel<String> listFiles;
        private final JList<String> fileList;

        public SlaveInterface(Slave slave) {
            setTitle("Slave (Serveur de Stockage)");
            setSize(600, 400);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            // Panel principal
            JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
            mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Panel supérieur pour les contrôles
            JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            startStopButton = new JButton("Démarrer");
            statusLabel = new JLabel("État: Arrêté");
            spaceLabel = new JLabel();
            updateSpaceLabel();

            startStopButton.addActionListener(e -> {
                if (!isRunning) {
                    slave.startServer();
                    startStopButton.setText("Arrêter");
                    statusLabel.setText("État: En cours d'exécution");
                } else {
                    slave.stopServer();
                    startStopButton.setText("Démarrer");
                    statusLabel.setText("État: Arrêté");
                }
            });

            controlPanel.add(startStopButton);
            controlPanel.add(statusLabel);
            controlPanel.add(spaceLabel);

            
            listFiles = new DefaultListModel<>();
            fileList = new JList<>(listFiles);

            // Zone de logs
            logArea = new JTextArea();
            logArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(fileList);

            mainPanel.add(controlPanel, BorderLayout.NORTH);
            mainPanel.add(scrollPane, BorderLayout.CENTER);

            add(mainPanel);

            // Timer pour mettre à jour l'espace disponible
            new Timer(10000, e -> updateSpaceLabel()).start();
        }

        private void updateSpaceLabel() {
            File storageDir = new File(DATA_DIRECTORY);
            long freeSpace = storageDir.getUsableSpace();
            spaceLabel.setText(String.format("Espace disponible: %.2f GB", freeSpace / (1024.0 * 1024.0 * 1024.0)));
        }

        public void addLog(String message) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }

        public void updateFileList(List<String> files) {
            SwingUtilities.invokeLater(() -> {
                listFiles.clear();
                files.forEach(listFiles::addElement);
            });
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Slave slave = new Slave();
            slave.getSlaveInterface().setVisible(true);
        });
    }
}