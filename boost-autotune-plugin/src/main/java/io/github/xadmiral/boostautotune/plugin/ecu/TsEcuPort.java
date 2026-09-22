package io.github.xadmiral.boostautotune.plugin.ecu;

import com.efiAnalytics.plugin.ecu.ControllerAccess;
import com.efiAnalytics.plugin.ecu.ControllerException;
import com.efiAnalytics.plugin.ecu.MathException;
import com.efiAnalytics.plugin.ecu.ControllerParameter;
import com.efiAnalytics.plugin.ecu.OutputChannelClient;
import com.efiAnalytics.plugin.ecu.UiTable;
import com.efiAnalytics.plugin.ecu.servers.ControllerParameterServer;
import com.efiAnalytics.plugin.ecu.servers.OutputChannelServer;
import com.efiAnalytics.plugin.ecu.servers.UiSettingServer;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** The only class that touches the TunerStudio plugin API. */
public final class TsEcuPort implements EcuPort {
    private final ControllerAccess access;
    private final String signature;
    private final Map<ChannelListener, OutputChannelClient> clients = new IdentityHashMap<ChannelListener, OutputChannelClient>();

    public TsEcuPort(ControllerAccess access, String signature) {
        this.access = access;
        this.signature = signature == null ? "" : signature;
    }

    @Override
    public String signature() {
        return signature;
    }

    @Override
    public List<String> configurationNames() {
        try {
            String[] names = access.getEcuConfigurationNames();
            return names == null ? Collections.<String>emptyList() : Arrays.asList(names);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> parameterNames(String config) {
        try {
            String[] names = params().getParameterNames(config);
            return names == null ? Collections.<String>emptyList() : Arrays.asList(names);
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> channelNames(String config) {
        try {
            String[] names = channels().getOutputChannels(config);
            return names == null ? Collections.<String>emptyList() : Arrays.asList(names);
        } catch (ControllerException e) {
            return Collections.emptyList();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    @Override
    public ParamInfo parameterInfo(String config, String name) throws EcuException {
        ControllerParameter p = param(config, name);
        Dimension d = p.getShape();
        List<String> options = new ArrayList<String>();
        if (p.getOptionDescriptions() != null) {
            for (Object o : p.getOptionDescriptions()) {
                options.add(String.valueOf(o));
            }
        }
        return new ParamInfo(p.getParamClass(), p.getUnits(), p.getMin(), p.getMax(), p.getDecimalPlaces(),
                d == null ? 0 : d.width, d == null ? 0 : d.height, options);
    }

    @Override
    public double readScalar(String config, String name) throws EcuException {
        return param(config, name).getScalarValue();
    }

    @Override
    public String readOption(String config, String name) throws EcuException {
        return param(config, name).getStringValue();
    }

    @Override
    public double[] readArray1D(String config, String name) throws EcuException {
        double[][] raw = param(config, name).getArrayValues();
        if (raw == null || raw.length == 0) {
            throw new EcuException("Parameter " + name + " has no array values");
        }
        if (raw.length == 1) {
            return raw[0].clone();
        }
        if (raw[0].length == 1) {
            double[] out = new double[raw.length];
            for (int i = 0; i < raw.length; i++) {
                out[i] = raw[i][0];
            }
            return out;
        }
        throw new EcuException("Parameter " + name + " is a 2-D table (" + raw.length + "x" + raw[0].length
                + "), a 1-D axis was expected");
    }

    @Override
    public double[][] readArray2D(String config, String name) throws EcuException {
        double[][] raw = param(config, name).getArrayValues();
        if (raw == null || raw.length == 0 || raw[0].length == 0) {
            throw new EcuException("Parameter " + name + " has no array values");
        }
        double[][] copy = new double[raw.length][];
        for (int i = 0; i < raw.length; i++) {
            copy[i] = raw[i].clone();
        }
        return copy;
    }

    @Override
    public void writeScalar(String config, String name, double value) throws EcuException {
        try {
            params().updateParameter(config, name, value);
        } catch (ControllerException e) {
            throw new EcuException("Writing " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void writeOption(String config, String name, String option) throws EcuException {
        try {
            params().updateParameter(config, name, option);
        } catch (ControllerException e) {
            throw new EcuException("Writing " + name + " = " + option + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void writeArray2D(String config, String name, double[][] raw) throws EcuException {
        try {
            params().updateParameter(config, name, raw);
        } catch (ControllerException e) {
            throw new EcuException("Writing table " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void writeArray1D(String config, String name, double[] values) throws EcuException {
        double[][] raw = param(config, name).getArrayValues();
        if (raw == null || raw.length == 0) {
            throw new EcuException("Parameter " + name + " has no array values");
        }
        double[][] out;
        if (raw.length == 1) {
            if (raw[0].length != values.length) {
                throw new EcuException("Axis " + name + " has " + raw[0].length + " bins, got " + values.length);
            }
            out = new double[1][];
            out[0] = values.clone();
        } else {
            if (raw.length != values.length || raw[0].length != 1) {
                throw new EcuException("Axis " + name + " has " + raw.length + " bins, got " + values.length);
            }
            out = new double[values.length][1];
            for (int i = 0; i < values.length; i++) {
                out[i][0] = values[i];
            }
        }
        writeArray2D(config, name, out);
    }

    @Override
    public void burn(String config) throws EcuException {
        try {
            params().burnData(config);
        } catch (ControllerException e) {
            throw new EcuException("Burn failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void subscribe(String config, List<String> channelNames, final ChannelListener listener) throws EcuException {
        OutputChannelClient client;
        synchronized (clients) {
            client = clients.get(listener);
            if (client == null) {
                client = new OutputChannelClient() {
                    @Override
                    public void setCurrentOutputChannelValue(String channel, double value) {
                        listener.channelValue(channel, value);
                    }
                };
                clients.put(listener, client);
            }
        }
        for (String ch : channelNames) {
            if (ch == null || ch.trim().isEmpty()) {
                continue;
            }
            try {
                channels().subscribe(config, ch, client);
            } catch (ControllerException e) {
                throw new EcuException("Cannot subscribe to output channel '" + ch + "': " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new EcuException("Cannot subscribe to output channel '" + ch + "': " + e, e);
            }
        }
    }

    @Override
    public void unsubscribe(ChannelListener listener) {
        OutputChannelClient client;
        synchronized (clients) {
            client = clients.remove(listener);
        }
        if (client != null) {
            try {
                channels().unsubscribe(client);
            } catch (RuntimeException e) {
                // TunerStudio is shutting down or the configuration is gone: nothing to do
            }
        }
    }

    /** Reads channels through TunerStudio's expression evaluator: works whenever the runtime data is being read. */
    @Override
    public double[] pollChannels(String config, List<String> channels) {
        double[] out = new double[channels.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = access.evaluateExpression(config, channels.get(i));
            } catch (MathException e) {
                out[i] = Double.NaN;
            } catch (RuntimeException e) {
                out[i] = Double.NaN;
            }
        }
        return out;
    }

    @Override
    public List<UiTableInfo> uiTables(String config) {
        List<UiTableInfo> out = new ArrayList<UiTableInfo>();
        try {
            UiSettingServer ui = access.getUiComponentServer(config);
            if (ui == null || ui.getUiTable() == null) {
                return out;
            }
            for (UiTable t : ui.getUiTable()) {
                out.add(new UiTableInfo(t.getName(), t.getXParameterName(), t.getYParameterName(), t.getZParameterName(),
                        t.getXOutputChannel(), t.getYOutputChannel()));
            }
        } catch (ControllerException e) {
            // older TunerStudio builds do not provide the UI server
        } catch (RuntimeException e) {
            // same
        }
        return out;
    }

    private ControllerParameter param(String config, String name) throws EcuException {
        try {
            ControllerParameter p = params().getControllerParameter(config, name);
            if (p == null) {
                throw new EcuException("Parameter '" + name + "' not found");
            }
            return p;
        } catch (ControllerException e) {
            throw new EcuException("Reading '" + name + "' failed: " + e.getMessage(), e);
        }
    }

    private ControllerParameterServer params() {
        return access.getControllerParameterServer();
    }

    private OutputChannelServer channels() {
        return access.getOutputChannelServer();
    }
}
