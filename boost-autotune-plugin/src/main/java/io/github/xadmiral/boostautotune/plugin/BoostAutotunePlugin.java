package io.github.xadmiral.boostautotune.plugin;

import com.efiAnalytics.plugin.ApplicationPlugin;
import com.efiAnalytics.plugin.ecu.ControllerAccess;
import io.github.xadmiral.boostautotune.plugin.ecu.TsEcuPort;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;
import io.github.xadmiral.boostautotune.plugin.ui.MainPanel;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * TunerStudio entry point. Registered through the {@code ApplicationPlugin} manifest attribute.
 * The UI is built lazily because TunerStudio instantiates plugins just to read their metadata.
 */
public final class BoostAutotunePlugin implements ApplicationPlugin {
    public static final String VERSION = "0.1.0";
    private ControllerAccess access;
    private String signature = "";
    private MainPanel panel;
    private final JPanel root = new JPanel(new BorderLayout());

    @Override
    public String getIdName() {
        return "BoostAutotune";
    }

    @Override
    public int getPluginType() {
        return PERSISTENT_DIALOG_PANEL;
    }

    @Override
    public String getDisplayName() {
        return "Boost Autotune";
    }

    @Override
    public String getDescription() {
        return "Closed-loop boost autotune: pick target boost stages, do a few pulls, "
                + "the plugin characterizes the wastegate, fills the bias/duty table and settles the PID.";
    }

    @Override
    public void initialize(ControllerAccess controllerAccess) {
        this.access = controllerAccess;
    }

    @Override
    public boolean displayPlugin(String signature) {
        this.signature = signature == null ? "" : signature;
        return true;
    }

    @Override
    public boolean isMenuEnabled() {
        return true;
    }

    @Override
    public String getAuthor() {
        return "xADMIRAL";
    }

    @Override
    public JComponent getPluginPanel() {
        synchronized (this) {
            if (panel == null) {
                if (access == null) {
                    root.add(new JLabel("Boost Autotune: no controller access yet, open a project first."), BorderLayout.CENTER);
                } else {
                    panel = new MainPanel(new TsEcuPort(access, signature), new SettingsStore());
                    root.removeAll();
                    root.add(panel, BorderLayout.CENTER);
                }
            }
        }
        return root;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (panel != null) {
                panel.dispose();
            }
        }
    }

    @Override
    public String getHelpUrl() {
        return "https://github.com/xADMIRAL/tunerplugautoboost";
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public double getRequiredPluginSpec() {
        return PLUGIN_API_VERSION;
    }
}
