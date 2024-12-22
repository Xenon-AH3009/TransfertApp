package utils;

import java.io.BufferedReader;
import java.io.FileReader;

public class NetworkConfig {
    // Ports
    public static int DEFAULT_SERVER_PORT = 5000;
    public static int DEFAULT_SLAVE_PORT = 5001;
    public static int BROADCAST_PORT = 7777;
    
    // Timeouts et intervalles (en millisecondes)
    public static int CONNECTION_TIMEOUT = 5000;
    public static int BROADCAST_INTERVAL = 2500;
    public static int SLAVE_HEALTH_CHECK_INTERVAL = 30000;
    public static int SLAVE_TIMEOUT = 60000;
    
    // Messages du protocole
    public static String DISCOVER_SLAVES_MESSAGE = "DISCOVER_SLAVE";
    public static String READY_SLAVES_MESSAGE = "READY_SLAVE";
    
    // Configuration réseau
    public static final String BROADCAST_ADDRESS = "255.255.255.255";
    public static int BUFFER_SIZE = 8192;
    
    // Chemins des répertoires
    public static String SLAVE_DATA_DIRECTORY = "./slaveStorage";
    
    private NetworkConfig() {
        // Empêche l'instanciation
    }

    public static void loadConfigurations() {
        try (BufferedReader config = new BufferedReader(new FileReader("./configurations/config.txt"))) {
            String ligne;
            while ((ligne = config.readLine()) != null) {
                // Ignorer les lignes vides et les commentaires
                if (ligne.trim().isEmpty() || ligne.trim().startsWith("#")) {
                    continue;
                }

                // Nettoyer la ligne
                ligne = ligne.replace(";", " ").trim();
                String[] parties = ligne.split("=");
                
                // Vérifier que la ligne a le bon format
                if (parties.length < 2) {
                    FileTransferLoggerConfigurations.logError("Format de ligne incorrect: " + ligne, null);
                    continue;
                }

                // Récupérer la clé et la valeur
                String key = parties[0].trim().toUpperCase();
                String value = parties[1].trim();

                try {
                    switch (key) {
                        case "SLAVE_DATA_DIRECTORY":
                            SLAVE_DATA_DIRECTORY = value;
                            break;
                        case "SERVER_PORT":
                            DEFAULT_SERVER_PORT = Integer.parseInt(value);
                            break;
                        case "SLAVE_PORT":
                            DEFAULT_SLAVE_PORT = Integer.parseInt(value);
                            break;
                        case "BROADCAST_PORT":
                            BROADCAST_PORT = Integer.parseInt(value);
                            break;
                        case "BUFFER_SIZE":
                            int newBufferSize = Integer.parseInt(value);
                            if (newBufferSize > 0) {
                                BUFFER_SIZE = newBufferSize;
                            }
                            break;
                        case "CONNECTION_TIMEOUT":
                            int timeout = Integer.parseInt(value);
                            if (timeout > 0) {
                                CONNECTION_TIMEOUT = timeout;
                            }
                            break;
                        case "BROADCAST_INTERVAL":
                            BROADCAST_INTERVAL = Integer.parseInt(value);
                            break;
                        case "SLAVE_HEALTH_CHECK_INTERVAL":
                            SLAVE_HEALTH_CHECK_INTERVAL = Integer.parseInt(value);
                            break;
                        case "DISCOVER_SLAVES_MESSAGE":
                            DISCOVER_SLAVES_MESSAGE = value;
                            break;
                        default:
                            FileTransferLoggerConfigurations.log("Configuration inconnue ignorée: " + key);
                            break;
                    }
                } catch (NumberFormatException e) {
                    FileTransferLoggerConfigurations.logError("Erreur de format pour la configuration " + key + ": " + value, e);
                }
            }

            // Validation des chemins de répertoires
            validateDirectory(SLAVE_DATA_DIRECTORY, "SLAVE_DATA_DIRECTORY");

            // Validation des ports
            validatePort(DEFAULT_SERVER_PORT, "DEFAULT_SERVER_PORT");
            validatePort(DEFAULT_SLAVE_PORT, "DEFAULT_SLAVE_PORT");

            // Log des configurations chargées
            logCurrentConfiguration();

        } catch (Exception e) {
            FileTransferLoggerConfigurations.logError("Erreur lors du chargement de la configuration", e);
        }
    }

    private static void validateDirectory(String path, String name) {
        if (path == null || path.trim().isEmpty()) {
            FileTransferLoggerConfigurations.logError("Le chemin " + name + " n'est pas défini", null);
            return;
        }
        try {
            java.nio.file.Path directory = java.nio.file.Paths.get(path);
            if (!java.nio.file.Files.exists(directory)) {
                java.nio.file.Files.createDirectories(directory);
                FileTransferLoggerConfigurations.log("Répertoire créé: " + path);
            }
        } catch (Exception e) {
            FileTransferLoggerConfigurations.logError("Erreur lors de la validation/création du répertoire " + path, e);
        }
    }

    private static void validatePort(int port, String name) {
        if (port <= 0 || port > 65535) {
            FileTransferLoggerConfigurations.logError(
                "Port invalide pour " + name + ": " + port + ". Utilisation du port par défaut.", 
                null
            );
        }
    }

    private static void logCurrentConfiguration() {
        FileTransferLoggerConfigurations.log("Configuration actuelle:");
        FileTransferLoggerConfigurations.log("SLAVE_DATA_DIRECTORY: " + SLAVE_DATA_DIRECTORY);
        FileTransferLoggerConfigurations.log("DEFAULT_SERVER_PORT: " + DEFAULT_SERVER_PORT);
        FileTransferLoggerConfigurations.log("DEFAULT_SLAVE_PORT: " + DEFAULT_SLAVE_PORT);
    }
}