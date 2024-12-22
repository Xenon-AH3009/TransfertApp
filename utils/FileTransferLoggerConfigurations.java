package utils;

import java.util.logging.Logger;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.logging.Level;
import java.io.IOException;

public class FileTransferLoggerConfigurations {
    private static final Logger logger = Logger.getLogger(FileTransferLoggerConfigurations.class.getName());
    
    static {
        try {
            FileHandler fileHandler = new FileHandler("file_transfer_configurations.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void log(String message) {
        logger.info(message);
    }

    public static void logError(String message, Throwable exception) {
        logger.log(Level.SEVERE, message, exception);
    }
}