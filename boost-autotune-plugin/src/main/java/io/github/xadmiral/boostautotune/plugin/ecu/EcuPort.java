package io.github.xadmiral.boostautotune.plugin.ecu;

import java.util.List;

/**
 * Everything the plugin needs from an ECU connection. Implemented by {@link TsEcuPort} for
 * TunerStudio and by {@link SimEcuPort} for the built-in demo.
 */
public interface EcuPort {

    /** Receives live channel values. */
    interface ChannelListener {
        void channelValue(String channel, double value);
    }

    String signature();

    List<String> configurationNames();

    List<String> parameterNames(String config);

    List<String> channelNames(String config);

    /** Metadata of a parameter (units, range, decimals, shape); null when unknown. */
    ParamInfo parameterInfo(String config, String name) throws EcuException;

    double readScalar(String config, String name) throws EcuException;

    String readOption(String config, String name) throws EcuException;

    double[] readArray1D(String config, String name) throws EcuException;

    /** Raw 2-D values exactly as the ECU layer hands them out. */
    double[][] readArray2D(String config, String name) throws EcuException;

    void writeScalar(String config, String name, double value) throws EcuException;

    void writeOption(String config, String name, String option) throws EcuException;

    void writeArray2D(String config, String name, double[][] raw) throws EcuException;

    void burn(String config) throws EcuException;

    void subscribe(String config, List<String> channels, ChannelListener listener) throws EcuException;

    void unsubscribe(ChannelListener listener);

    /** Table definitions from the INI (z, x, y parameter names), empty when unavailable. */
    List<UiTableInfo> uiTables(String config);

    /** Simple descriptor of an INI table editor. */
    final class UiTableInfo {
        public final String name;
        public final String xParam;
        public final String yParam;
        public final String zParam;
        public final String xChannel;
        public final String yChannel;

        public UiTableInfo(String name, String xParam, String yParam, String zParam, String xChannel, String yChannel) {
            this.name = name;
            this.xParam = xParam;
            this.yParam = yParam;
            this.zParam = zParam;
            this.xChannel = xChannel;
            this.yChannel = yChannel;
        }

        @Override
        public String toString() {
            return name + " [z=" + zParam + " x=" + xParam + "/" + xChannel + " y=" + yParam + "/" + yChannel + "]";
        }
    }

    /** Parameter metadata. */
    final class ParamInfo {
        public final String paramClass;
        public final String units;
        public final double min;
        public final double max;
        public final int decimals;
        public final int shapeWidth;
        public final int shapeHeight;
        public final List<String> options;

        public ParamInfo(String paramClass, String units, double min, double max, int decimals, int shapeWidth,
                         int shapeHeight, List<String> options) {
            this.paramClass = paramClass;
            this.units = units;
            this.min = min;
            this.max = max;
            this.decimals = decimals;
            this.shapeWidth = shapeWidth;
            this.shapeHeight = shapeHeight;
            this.options = options;
        }
    }
}
