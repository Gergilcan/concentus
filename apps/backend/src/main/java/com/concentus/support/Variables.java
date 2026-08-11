package com.concentus.support;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{NAME}}} substitution for prompts.
 *
 * <p>The syntax is the mustache-shaped one people already write in prompt templates, chosen over
 * {@code ${NAME}} because prompts quote shell fragments all the time and a variable system that
 * mangles {@code ${PATH}} in an example command would be worse than none.
 *
 * <p>An unknown variable is left exactly as written, never blanked: a prompt that says
 * {@code {{CUSTOMER}}} where nobody defined CUSTOMER should look wrong in the run log, not
 * silently lose a word. Callers collect the unresolved names and say so out loud instead.
 */
public final class Variables {

    /** Conservative on purpose: names, not expressions. Spaces inside the braces are tolerated. */
    private static final Pattern REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private Variables() {
    }

    /**
     * Replaces every {@code {{NAME}}} whose name {@code values} defines; the rest stay verbatim
     * and their names are added to {@code unresolved} (when the caller passes one).
     */
    public static String substitute(String text, Map<String, String> values, Set<String> unresolved) {
        if (text == null || text.isEmpty() || !text.contains("{{")) return text;
        Matcher m = REF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = values.get(name);
            if (value == null) {
                if (unresolved != null) unresolved.add(name);
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
        }
        m.appendTail(out);
        return out.toString();
    }
}
