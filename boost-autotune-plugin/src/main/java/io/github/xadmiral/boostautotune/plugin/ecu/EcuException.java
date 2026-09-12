package io.github.xadmiral.boostautotune.plugin.ecu;

/** Wraps TunerStudio's ControllerException so the UI does not depend on the API jar. */
public class EcuException extends Exception {
    public EcuException(String message) {
        super(message);
    }

    public EcuException(String message, Throwable cause) {
        super(message, cause);
    }
}
