package io.github.xadmiral.boostautotune.plugin;

import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;
import io.github.xadmiral.boostautotune.plugin.ui.MainPanel;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.File;

/**
 * Runs the plugin UI outside TunerStudio against the built-in simulated MS3 so the workflow can be
 * tried on a desk: {@code java -cp BoostAutotune.jar io.github.xadmiral.boostautotune.plugin.DemoLauncher}
 */
public final class DemoLauncher {
    private DemoLauncher() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception e) {
                    // default look and feel is fine
                }
                File f = new File(System.getProperty("java.io.tmpdir"), "boost-autotune-demo.properties");
                MainPanel panel = new MainPanel(new SimEcuPort(), new SettingsStore(f));
                JFrame frame = new JFrame("Boost Autotune - demo (simulated MS3)");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.setContentPane(panel);
                frame.setSize(1150, 760);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            }
        });
    }
}
