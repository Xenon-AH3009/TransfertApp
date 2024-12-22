package client;

import java.awt.*;
import java.awt.event.*;
import java.util.Set;
import java.util.HashSet;
import java.io.*;
import java.net.Socket;
import javax.swing.*;
import utils.*;

public class Client extends JFrame {
    private JTextField serverAddressField;
    private JTextField serverPortField;
    private JButton connectButton;
    private JButton disconnectButton;
    private JList<String> fileList;
    private DefaultListModel<String> fileListModel;
    private JButton uploadButton;
    private JButton downloadButton;
    private JButton deleteButton;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private boolean isConnected = false;
    private static final int BUFFER_SIZE = NetworkConfig.BUFFER_SIZE;

    public Client() {
        initializeUI();
    }

    private void initializeUI() {
        setTitle("Client de Transfert de Fichiers");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Panel de connexion
        JPanel connectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        serverAddressField = new JTextField(15);
        serverAddressField.setText("localhost");
        serverPortField = new JTextField(5);
        serverPortField.setText(String.valueOf(NetworkConfig.DEFAULT_SERVER_PORT));
        connectButton = new JButton("Connexion");
        disconnectButton = new JButton("Déconnexion");
        disconnectButton.setEnabled(false);

        connectionPanel.add(new JLabel("Adresse:"));
        connectionPanel.add(serverAddressField);
        connectionPanel.add(new JLabel("Port:"));
        connectionPanel.add(serverPortField);
        connectionPanel.add(connectButton);
        connectionPanel.add(disconnectButton);

        // Panel principal avec la liste des fichiers
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        JScrollPane scrollPane = new JScrollPane(fileList);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Panel des boutons
        JPanel buttonPanel = new JPanel(new FlowLayout());
        uploadButton = new JButton("Upload");
        downloadButton = new JButton("Download");
        uploadButton.setEnabled(false);
        downloadButton.setEnabled(false);
        buttonPanel.add(uploadButton);
        buttonPanel.add(downloadButton);

        // Dans initializeUI(), ajoutez :
        deleteButton = new JButton("Supprimer");
        deleteButton.setEnabled(false);
        buttonPanel.add(deleteButton);

        // Ajouter un listener pour activer le bouton seulement si un fichier est sélectionné
        fileList.addListSelectionListener(e -> deleteButton.setEnabled(!fileList.isSelectionEmpty() && isConnected));

        // Gestion de l'événement du bouton Supprimer
        deleteButton.addActionListener(e -> handleDelete());


        // Ajout des composants à la fenêtre
        add(connectionPanel, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        // Gestion des événements
        connectButton.addActionListener(e -> connectToServer());
        disconnectButton.addActionListener(e -> disconnectFromServer());
        uploadButton.addActionListener(e -> handleUpload());
        downloadButton.addActionListener(e -> handleDownload());
        fileList.addListSelectionListener(e -> downloadButton.setEnabled(!fileList.isSelectionEmpty() && isConnected));

        // Ajout d'un WindowListener pour gérer la fermeture propre
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnectFromServer();
            }
        });
    }

    private void connectToServer() {
        try {
            String address = serverAddressField.getText();
            int port = Integer.parseInt(serverPortField.getText());
            
            socket = new Socket(address, port);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            
            isConnected = true;
            updateConnectionStatus(true);
            refreshFileList();

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Erreur de connexion: " + e.getMessage(),
                "Erreur",
                JOptionPane.ERROR_MESSAGE);
            FileTransferLoggerClient.logError("Erreur de connexion au serveur", e);
            disconnectFromServer();
        }
    }

    private synchronized void disconnectFromServer() {
        isConnected = false;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            FileTransferLoggerClient.logError("Erreur lors de la déconnexion", e);
        } finally {
            in = null;
            out = null;
            socket = null;
            updateConnectionStatus(false);
            fileListModel.clear();
        }
    }

    private void updateConnectionStatus(boolean connected) {
        SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(!connected);
            disconnectButton.setEnabled(connected);
            uploadButton.setEnabled(connected);
            serverAddressField.setEnabled(!connected);
            serverPortField.setEnabled(!connected);
            if (!connected) {
                downloadButton.setEnabled(false);
            }
        });
    }

    
    private void refreshFileList() {
        if (!ensureConnection()) return;
        
        try {
            socket = new Socket(serverAddressField.getText(), Integer.parseInt(serverPortField.getText()));
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            out.writeUTF("LIST_FILES");
            out.flush();

            fileListModel.clear();
            String fileName;
            Set<String> files = new HashSet<String>();

            while (!(fileName = in.readUTF()).equals("END_OF_LIST")) {
                files.add(fileName);
            }

            fileListModel.addAll(files);



        } catch (IOException e) {
            FileTransferLoggerClient.logError("Erreur lors de la récupération de la liste des fichiers", e);
            disconnectFromServer();
        }
    }

    private void handleUpload() {
        if (!ensureConnection()) return;

        JFileChooser fileChooser = new JFileChooser();
        int result = fileChooser.showOpenDialog(this);

        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            uploadFile(selectedFile);
        }
    }

    private void handleDownload() {
        if (!ensureConnection()) return;

        String selectedFile = fileList.getSelectedValue();
        if (selectedFile == null) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File(selectedFile));
        int result = fileChooser.showSaveDialog(this);

        if (result == JFileChooser.APPROVE_OPTION) {
            File saveFile = fileChooser.getSelectedFile();
            downloadFile(selectedFile, saveFile);
        }
    }

    private void uploadFile(File file) {
        if (!ensureConnection()) return;
    
        JProgressBar progressBar = new JProgressBar(0, 100);
        JDialog progressDialog = createProgressDialog(progressBar, "Upload en cours..."); 
    
        try {
            socket = new Socket(serverAddressField.getText(), Integer.parseInt(serverPortField.getText()));
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            // Configurer un timeout sur le socket
            socket.setSoTimeout(30000); // 30 secondes
            
            // 1. Envoi de la commande
            out.writeUTF("UPLOAD");
            out.flush();
            
            // 2. Envoi du nom du fichier
            out.writeUTF(file.getName());
            out.flush();
            
            // 3. Envoi de la taille
            long fileSize = file.length();
            out.writeLong(fileSize);
            out.flush();
            
            // 4. Envoi du contenu avec gestion des timeouts
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long totalBytesRead = 0;
                int bytesRead;
                long lastProgressUpdate = System.currentTimeMillis();
    
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    // Mise à jour de la progression toutes les 100ms max
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdate > 100) {
                        final int progress = (int) ((totalBytesRead * 100) / fileSize);
                        SwingUtilities.invokeLater(() -> progressBar.setValue(progress));
                        lastProgressUpdate = now;
                        
                        // Flush périodique pour éviter l'accumulation de données
                        out.flush();
                    }
                }
                out.flush();
            }
    
            // 5. Attendre la confirmation finale
            //response = in.readUTF();
            progressDialog.dispose();
            String response = null;
            if ((response = in.readUTF()).equals("SUCCESS")) {
                JOptionPane.showMessageDialog(this, "Upload terminé avec succès");
                refreshFileList();
            } else {
                throw new IOException("Échec de l'upload: " + response);
            }
    
        } catch (IOException e) {
            progressDialog.dispose();
            FileTransferLoggerClient.logError("Erreur lors de l'upload", e);
            JOptionPane.showMessageDialog(this,
                "Erreur lors de l'upload: " + e.getMessage(),
                "Erreur",
                JOptionPane.ERROR_MESSAGE);
            disconnectFromServer();
        }
    }

    private void downloadFile(String fileName, File saveFile) {
        if (!ensureConnection()) return;
    
        JProgressBar progressBar = new JProgressBar(0, 100);
        JDialog progressDialog = createProgressDialog(progressBar, "Download en cours...");
    
        try {
            socket = new Socket(serverAddressField.getText(), Integer.parseInt(serverPortField.getText()));
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
    
            // Configuration du timeout
            socket.setSoTimeout(30000); // 30 secondes
    
            // 1. Envoi de la commande et du nom de fichier
            out.writeUTF("DOWNLOAD");
            out.flush();
            out.writeUTF(fileName);
            out.flush();
    
            // 2. Vérification de l'existence du fichier
            boolean fileExists = in.readBoolean();
            if (!fileExists) {
                progressDialog.dispose();
                JOptionPane.showMessageDialog(this,
                    "Le fichier n'existe pas sur le serveur",
                    "Erreur",
                    JOptionPane.ERROR_MESSAGE);
                return;
            }
    
            // 3. Réception de la taille totale
            long fileSize = in.readLong();
            if (fileSize <= 0) {
                throw new IOException("Taille de fichier invalide reçue: " + in.readLong() + " octets.");
            }
    
            // 4. Préparation du fichier temporaire
            File tempFile = new File(saveFile.getParentFile(), saveFile.getName() + ".temp");
            
            // 5. Réception du contenu avec vérification
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long totalBytesRead = 0;
                int bytesRead;
                long lastProgressUpdate = System.currentTimeMillis();
    
                while (totalBytesRead < fileSize) {
                    bytesRead = in.read(buffer, 0, (int)Math.min(buffer.length, fileSize - totalBytesRead));
                    
                    if (bytesRead == -1) {
                        throw new IOException("Connexion interrompue avant la fin du téléchargement");
                    }
    
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
    
                    // Mise à jour de la progression toutes les 100ms
                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdate > 100) {
                        final int progress = (int)((totalBytesRead * 100) / fileSize);
                        SwingUtilities.invokeLater(() -> progressBar.setValue(progress));
                        lastProgressUpdate = now;
                    }
                }
                
                fos.flush();
            }
    
            // 6. Vérification de la taille finale
            if (tempFile.length() != fileSize) {
                throw new IOException("Taille du fichier téléchargé incorrecte. Attendu: " + fileSize + ", Reçu: " + tempFile.length());
            }
    
            // 7. Remplacement du fichier final
            if (saveFile.exists() && !saveFile.delete()) {
                throw new IOException("Impossible de supprimer le fichier existant");
            }
            
            if (!tempFile.renameTo(saveFile)) {
                throw new IOException("Impossible de renommer le fichier temporaire");
            }
    
            progressDialog.dispose();
            JOptionPane.showMessageDialog(this, "Download terminé avec succès");
    
        } catch (IOException e) {
            progressDialog.dispose();
            
            // Nettoyage en cas d'erreur
            File tempFile = new File(saveFile.getParentFile(), saveFile.getName() + ".temp");
            if (tempFile.exists()) {
                tempFile.delete();
            }
    
            FileTransferLoggerClient.logError("Erreur lors du download", e);
            JOptionPane.showMessageDialog(this,
                "Erreur lors du download: " + e.getMessage(),
                "Erreur",
                JOptionPane.ERROR_MESSAGE);
            disconnectFromServer();
        }
    }

    private void handleDelete() {
        if (!ensureConnection()) return;
    
        String selectedFile = fileList.getSelectedValue();
        if (selectedFile == null) return;
    
        int confirm = JOptionPane.showConfirmDialog(this, 
            "Êtes-vous sûr de vouloir supprimer le fichier " + selectedFile + " ?", 
            "Confirmation de suppression", 
            JOptionPane.YES_NO_OPTION);
    
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                socket = new Socket(serverAddressField.getText(), Integer.parseInt(serverPortField.getText()));
                out = new DataOutputStream(socket.getOutputStream());
                in = new DataInputStream(socket.getInputStream());
                
                out.writeUTF("DELETE_FILE");
                out.writeUTF(selectedFile);
                out.flush();
    
                String response = in.readUTF();
                if ("SUCCESS".equals(response)) {
                    JOptionPane.showMessageDialog(this, "Fichier supprimé avec succès !");
                    refreshFileList();
                } else {
                    JOptionPane.showMessageDialog(this, "Erreur lors de la suppression du fichier : " + response, 
                        "Erreur", JOptionPane.ERROR_MESSAGE);
                }
            } catch (IOException e) {
                FileTransferLoggerClient.logError("Erreur lors de la suppression du fichier", e);
                JOptionPane.showMessageDialog(this, "Erreur lors de la suppression : " + e.getMessage(), 
                    "Erreur", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    

    private synchronized boolean ensureConnection() {
        if (socket == null || socket.isClosed() || !isConnected) {
            JOptionPane.showMessageDialog(this,
                "La connexion au serveur a été perdue. Reconnexion nécessaire.",
                "Erreur de connexion",
                JOptionPane.ERROR_MESSAGE);
            disconnectFromServer();
            return false;
        }
        return true;
    }

    private JDialog createProgressDialog(JProgressBar progressBar, String title) {
        JDialog dialog = new JDialog(this, title, false);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.add(progressBar, BorderLayout.CENTER);
        dialog.setSize(300, 100);
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setVisible(true);
        return dialog;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Client client = new Client();
            client.setVisible(true);
        });
    }
}