package io.github.xadmiral.boostautotune.plugin.ui;

import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Color;

/**
 * Colours that stay readable whatever look and feel the host application uses. TunerStudio ships a
 * dark theme: text painted in plain black on its panels disappears, so "normal" text takes the
 * look and feel's own label colour and the status colours are picked for the background's
 * brightness. Highlighted cells always set both background and foreground.
 */
public final class Palette {
    private Palette() {
    }

    /** The look and feel's ordinary text colour. */
    public static Color text() {
        Color c = UIManager.getColor("Label.foreground");
        if (c == null) {
            c = new JLabel().getForeground();
        }
        return c == null ? Color.BLACK : c;
    }

    /** The look and feel's ordinary panel colour. */
    public static Color panel() {
        Color c = UIManager.getColor("Panel.background");
        return c == null ? Color.LIGHT_GRAY : c;
    }

    /** Relative luminance 0..1 of a colour. */
    public static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    public static boolean isDark(Color c) {
        return luminance(c) < 0.5;
    }

    /** A text colour that contrasts with the given background. */
    public static Color textOn(Color background) {
        return isDark(background) ? new Color(240, 240, 240) : new Color(20, 20, 20);
    }

    public static Color dimTextOn(Color background) {
        return isDark(background) ? new Color(190, 190, 190) : new Color(90, 90, 90);
    }

    public static Color goodOn(Color background) {
        return isDark(background) ? new Color(110, 230, 110) : new Color(0, 130, 0);
    }

    public static Color badOn(Color background) {
        return isDark(background) ? new Color(255, 100, 100) : new Color(200, 0, 0);
    }

    public static Color warnOn(Color background) {
        return isDark(background) ? new Color(255, 170, 60) : new Color(200, 90, 0);
    }

    /** Background for the live value cells: a light card in a light theme, a dark card in a dark one. */
    public static Color cardOn(Color background) {
        return isDark(background) ? new Color(45, 48, 52) : new Color(252, 252, 246);
    }
}
