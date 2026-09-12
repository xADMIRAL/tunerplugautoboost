package io.github.xadmiral.boostautotune.core.log;

import io.github.xadmiral.boostautotune.core.model.Sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads TunerStudio datalogs: {@code .msl} (tab separated, a few header lines, a name row and a
 * units row) or plain {@code .csv} exports (comma separated, name row first). Column names are
 * matched case-insensitively and ignoring surrounding whitespace.
 */
public final class MslLogReader {
    private MslLogReader() {
    }

    public static final class Result {
        public final List<Sample> samples = new ArrayList<Sample>();
        public final List<String> columns = new ArrayList<String>();
        public final List<String> warnings = new ArrayList<String>();
        public int skippedLines;
    }

    public static Result read(Reader reader, LogColumnMapping map) throws IOException {
        BufferedReader br = reader instanceof BufferedReader ? (BufferedReader) reader : new BufferedReader(reader);
        Result r = new Result();
        String line;
        String[] header = null;
        char sep = '\t';
        boolean unitsRowSkipped = false;
        int idxTime = -1, idxRpm = -1, idxTps = -1, idxMap = -1, idxTarget = -1, idxDuty = -1, idxClt = -1, idxGear = -1, idxCut = -1;
        double lastTime = 0;
        int rowIndex = 0;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (header == null) {
                if (line.startsWith("\"") || line.startsWith("MS") || line.startsWith("Capture") || line.startsWith("#")) {
                    continue; // msl preamble
                }
                sep = line.indexOf('\t') >= 0 ? '\t' : ',';
                header = split(line, sep);
                for (String h : header) {
                    r.columns.add(h.trim());
                }
                idxTime = find(header, map.time);
                idxRpm = find(header, map.rpm);
                idxTps = find(header, map.tps);
                idxMap = find(header, map.map);
                idxTarget = find(header, map.target);
                idxDuty = find(header, map.duty);
                idxClt = find(header, map.clt);
                idxGear = find(header, map.gear);
                idxCut = find(header, map.boostCut);
                if (idxRpm < 0 || idxMap < 0 || idxTps < 0) {
                    throw new IOException("Log is missing one of the required columns RPM / MAP / TPS (mapped as '"
                            + map.rpm + "', '" + map.map + "', '" + map.tps + "'). Columns: " + r.columns);
                }
                if (idxDuty < 0) {
                    r.warnings.add("No boost duty column '" + map.duty + "': bias learning is impossible from this log");
                }
                if (idxTarget < 0) {
                    r.warnings.add("No boost target column '" + map.target + "': closed-loop metrics are unavailable");
                }
                continue;
            }
            String[] f = split(line, sep);
            if (!unitsRowSkipped) {
                unitsRowSkipped = true;
                if (!isNumeric(f[idxRpm])) {
                    continue; // units row
                }
            }
            if (f.length < header.length) {
                r.skippedLines++;
                continue;
            }
            try {
                double t = idxTime >= 0 ? parse(f[idxTime]) : lastTime + 0.05;
                lastTime = t;
                double clt = idxClt >= 0 ? parse(f[idxClt]) : 90;
                if (map.cltFahrenheit) {
                    clt = (clt - 32) / 1.8;
                }
                Sample s = Sample.builder()
                        .time(t)
                        .rpm(parse(f[idxRpm]))
                        .tps(parse(f[idxTps]))
                        .map(parse(f[idxMap]))
                        .target(idxTarget >= 0 ? parse(f[idxTarget]) : Double.NaN)
                        .duty(idxDuty >= 0 ? parse(f[idxDuty]) : Double.NaN)
                        .clt(clt)
                        .gear(idxGear >= 0 ? (int) Math.round(parse(f[idxGear])) : 0)
                        .boostCut(idxCut >= 0 && parse(f[idxCut]) != 0)
                        .build();
                r.samples.add(s);
            } catch (NumberFormatException e) {
                r.skippedLines++;
            }
            rowIndex++;
        }
        if (header == null) {
            throw new IOException("No header row found");
        }
        return r;
    }

    private static String[] split(String line, char sep) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == sep && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static int find(String[] header, String name) {
        if (name == null) {
            return -1;
        }
        String want = name.trim().toLowerCase(Locale.US);
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().toLowerCase(Locale.US).equals(want)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isNumeric(String s) {
        try {
            parse(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double parse(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        return Double.parseDouble(t.replace(',', '.'));
    }

    /** Names of all columns in a header line, for UI pickers. */
    public static List<String> columnsOf(String headerLine) {
        char sep = headerLine.indexOf('\t') >= 0 ? '\t' : ',';
        List<String> out = new ArrayList<String>();
        for (String h : split(headerLine, sep)) {
            out.add(h.trim());
        }
        return out;
    }

    static List<String> asList(String[] a) {
        return Arrays.asList(a);
    }
}
