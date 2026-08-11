package me.neveux.ccx.util;

/**
 * Translates profile {@code ignore:} patterns into Claude Code {@code permissions.deny}
 * rules. Both the apply path (which writes the rules) and the drift path (which checks
 * they are still present) translate through this one pure function, so a pattern
 * recorded in {@code .ccx/context.json} always maps back to the rule that was written.
 */
public final class IgnorePatterns {

    private IgnorePatterns() {
    }

    /**
     * Maps one profile {@code ignore:} entry to the equivalent {@code settings.json} deny rule.
     *
     * <p>Entries already written as a tool pattern ({@code Read(...)}, {@code Write(...)},
     * {@code Bash(...)}) pass through verbatim. Otherwise a directory pattern ({@code target/})
     * becomes {@code Read(target/**)}, a bare glob ({@code *.class}) becomes
     * {@code Read(**}{@code /*.class)}, and anything else is wrapped as-is ({@code .env}
     * becomes {@code Read(.env)}). Surrounding whitespace is stripped first:
     * {@code "target/ "} would otherwise yield {@code Read(target/ **)}, a rule matching nothing.
     *
     * <p>The emitted patterns are deliberately left unanchored, so they resolve against the
     * directory Claude Code was started in rather than the project root. As deny rules, a
     * single-segment directory pattern and a bare filename both match at any depth under that
     * directory, which is broader coverage than the settings-source-anchored {@code /target/**}
     * form would give. See ADR-014 for the full reasoning.
     */
    public static String toReadDenyRule(String rawPattern) {
        var pattern = rawPattern.strip();
        if (pattern.startsWith("Read(") || pattern.startsWith("Write(") || pattern.startsWith("Bash(")) {
            return pattern;
        }
        if (pattern.endsWith("/")) {
            return "Read(" + pattern + "**)";
        }
        if (pattern.contains("*") && !pattern.contains("/")) {
            return "Read(**/" + pattern + ")";
        }
        return "Read(" + pattern + ")";
    }
}
