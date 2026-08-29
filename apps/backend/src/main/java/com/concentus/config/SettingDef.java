package com.concentus.config;

import com.concentus.license.Feature;

import java.util.List;

/**
 * One thing somebody can change about this installation.
 *
 * <p>The catalogue exists so the settings screen is a form rather than a wall of text boxes. Every
 * entry carries what a form needs and a properties file cannot hold: which group it belongs to,
 * what to call it in a sentence somebody reads once, what happens if it is wrong, and whether the
 * change lands now or at the next start.
 *
 * <p><b>The key is the property key it overrides, verbatim.</b> One vocabulary for the thing
 * whichever way it is set — a value here reads as an override of exactly that configuration, and a
 * deployment that already sets it in the environment keeps working with no translation table in
 * between.
 *
 * @param enterpriseOnly the {@link Feature} a Team license withholds this setting behind, or null
 *                       for the ordinary ones. The screen shows such a row disabled with the
 *                       refusal, so a Team admin flipping "Send traces" learns why nothing
 *                       leaves the machine from the field itself rather than from a log line.
 */
public record SettingDef(String key, String group, String label, String help, Type type,
                         boolean restartRequired, List<String> options, Feature enterpriseOnly) {

    /** The same setting, held behind {@code feature} on a Team license. */
    public SettingDef requiring(Feature feature) {
        return new SettingDef(key, group, label, help, type, restartRequired, options, feature);
    }

    public enum Type {
        TEXT,
        /** A whole number. The UI shows a number field; the value is still stored as text. */
        NUMBER,
        BOOLEAN,
        /** Comma-separated. Shown as one field, because that is how these are written. */
        LIST,
        /** Never returned to the browser once set — only whether it has one. */
        SECRET,
        /** One of {@link #options}. */
        CHOICE,
    }

    public static SettingDef text(String key, String group, String label, String help,
                                  boolean restartRequired) {
        return new SettingDef(key, group, label, help, Type.TEXT, restartRequired, List.of(), null);
    }

    public static SettingDef number(String key, String group, String label, String help,
                                    boolean restartRequired) {
        return new SettingDef(key, group, label, help, Type.NUMBER, restartRequired, List.of(), null);
    }

    public static SettingDef flag(String key, String group, String label, String help,
                                  boolean restartRequired) {
        return new SettingDef(key, group, label, help, Type.BOOLEAN, restartRequired, List.of(), null);
    }

    public static SettingDef list(String key, String group, String label, String help,
                                  boolean restartRequired) {
        return new SettingDef(key, group, label, help, Type.LIST, restartRequired, List.of(), null);
    }

    public static SettingDef secret(String key, String group, String label, String help,
                                    boolean restartRequired) {
        return new SettingDef(key, group, label, help, Type.SECRET, restartRequired, List.of(), null);
    }

    public static SettingDef choice(String key, String group, String label, String help,
                                    boolean restartRequired, String... options) {
        return new SettingDef(key, group, label, help, Type.CHOICE, restartRequired,
                List.of(options), null);
    }
}
