package execution;

import server.Server;
import server.Server.ServerInterface;

import javax.swing.*;

public class ServerLauncher {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
        Server server = new Server();
        ServerInterface serverInterface = server.new ServerInterface(server);
        server.serverInterface = serverInterface; // Associez l'interface utilisateur au serveur
        serverInterface.setVisible(true);
    });
    }
}