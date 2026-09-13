package io.github.xadmiral.boostautotune.core.log;

/** Column names (as printed in the log header) for each sample field. Null = not available. */
public final class LogColumnMapping {
    public String time = "Time";
    public String rpm = "RPM";
    public String tps = "TPS";
    public String map = "MAP";
    public String target = "Boost target 1";
    public String duty = "Boost duty";
    public String clt = "CLT";
    public String gear = "Gear";
    public String boostCut;
    public String fuelLoad = "Fuel Load";
    public String ignLoad = "Ign Load";
    public String vvtAngle = "VVT1 Angle";
    public String vvtTarget = "VVT1 Target";
    public String advance = "Advance";
    public String knock = "Knock";
    public String knockRetard = "Knock Retard";
    public String afr = "AFR";
    /** Coolant column is in Fahrenheit and must be converted. */
    public boolean cltFahrenheit;

    public static LogColumnMapping ms3() {
        return new LogColumnMapping();
    }

    public static LogColumnMapping speeduino() {
        LogColumnMapping m = new LogColumnMapping();
        m.time = "Time";
        m.rpm = "RPM";
        m.tps = "TPS";
        m.map = "MAP";
        m.target = "Boost Target";
        m.duty = "Boost Duty";
        m.clt = "CLT";
        m.gear = "Gear";
        m.boostCut = "Boost cut";
        m.advance = "Advance";
        m.vvtAngle = "VVT1 Angle";
        m.vvtTarget = "VVT1 Target";
        return m;
    }

    public static LogColumnMapping rusefi() {
        LogColumnMapping m = new LogColumnMapping();
        m.time = "Time";
        m.rpm = "RPM";
        m.tps = "TPS";
        m.map = "MAP";
        m.target = "Boost: Target";
        m.duty = "Boost: Output";
        m.clt = "CLT";
        m.gear = "Detected gear";
        return m;
    }
}
