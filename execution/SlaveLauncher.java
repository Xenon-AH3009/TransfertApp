package execution;

import slave.Slave;

import javax.swing.*;


public class SlaveLauncher {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Lancer Slave
            Slave slave = new Slave();
            slave.getSlaveInterface().setVisible(true);
            
        });
    }
}