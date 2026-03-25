import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility to mirror a Choreo .traj file across the field's horizontal centerline (Y axis midpoint).
 *
 * <p>Mirroring rules:
 * <ul>
 *   <li>Y-like coordinates: reflected about {@code FIELD_CENTER_Y_METERS}
 *       (i.e. {@code y' = 2 * center - y})
 *   <li>Heading/rotation-like radian values: negated
 *       (e.g. heading π/4 → -π/4, ω negated, etc.)
 * </ul>
 *
 * <p>Supports both plain numeric fields ({@code "y": 1.5}) and Choreo's expression-wrapper
 * format ({@code "y": {"exp": "1.5 m", "val": 1.5}}). In the wrapper case BOTH {@code val}
 * (used at runtime) AND {@code exp} (displayed in the Choreo editor) are mirrored, so the
 * editor reflects the correct coordinates immediately after mirroring.
 *
 * <p>Root cause of previous bug: the old implementation called {@code mirrorNodeRecursive(root)}
 * and then {@code mirrorWaypoints(root)}, causing {@code params.waypoints} to be mirrored twice
 * (and sometimes three times due to extra recursive calls inside {@code mirrorWaypointArray}).
 * Two mirrors cancel out, so {@code params.waypoints} was left unchanged. Because Choreo treats
 * {@code params.waypoints} as the source of truth for regeneration, any subsequent generation
 * produced the original, unmirrored path.
 */
public final class PathMirrorUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Field vertical midpoint: 158.85 inches converted to meters. */
    private static final double FIELD_CENTER_Y_METERS = 158.85 * 0.0254; // ≈ 4.0348 m

    /**
     * Matches the leading signed decimal (including optional scientific notation) at the start
     * of a Choreo exp string, followed by an optional whitespace-separated unit suffix.
     * Examples matched:
     *   "1.5 m"       → group 1 = "1.5",    group 2 = "m"
     *   "-0.785 rad"  → group 1 = "-0.785", group 2 = "rad"
     *   "3.14"        → group 1 = "3.14",   group 2 = ""
     *   "2e-3 m"      → group 1 = "2e-3",   group 2 = "m"
     */
    private static final Pattern EXP_NUMBER_PATTERN =
            Pattern.compile("^(-?\\d+\\.?\\d*(?:[eE][+-]?\\d+)?)\\s*(.*)$");

    private PathMirrorUtil() {}

    /**
     * Reads a .traj file, mirrors it across the horizontal field centerline, and writes a new
     * .traj file next to it.
     *
     * @param trajFilePath absolute or relative path to the input .traj
     * @return path of the written mirrored file
     * @throws IOException on read / parse / write failure
     */
    public static String mirrorTrajAcrossXAxis(String trajFilePath) throws IOException {
        Path input = Paths.get(trajFilePath);
        Path outputDirectory = input.getParent();
        return mirrorTrajAcrossXAxis(trajFilePath, outputDirectory);
    }

    /**
     * Reads a .traj file, mirrors it across the horizontal field centerline, and writes a new
     * .traj file to the provided output directory.
     *
     * @param trajFilePath absolute or relative path to the input .traj
     * @param outputDirectory folder where mirrored files should be written
     * @return path of the written mirrored file
     * @throws IOException on read / parse / write failure
     */
    public static String mirrorTrajAcrossXAxis(String trajFilePath, Path outputDirectory) throws IOException {
        Path input = Paths.get(trajFilePath);
        JsonNode root = MAPPER.readTree(Files.readString(input));

        // Single pass — mirrors every y-like and heading-like field in the entire tree,
        // including params.waypoints, trajectory.samples, and snapshot data.
        mirrorNodeRecursive(root, null);

        String fileName = input.getFileName().toString();
        String mirroredName = fileName.startsWith("MIRRORED_")
            ? fileName
            : "MIRRORED_" + fileName;

        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(mirroredName);
        Files.writeString(
            output,
            MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        return output.toString();
    }

    /**
     * Recursively walks the JSON tree and mirrors numeric fields in-place.
     *
     * @param node      current node being visited
     * @param fieldName the key under which {@code node} is stored in its parent object,
     *                  or {@code null} for the root node and array elements
     */
    private static void mirrorNodeRecursive(JsonNode node, String fieldName) {
        if (node == null) return;

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;

            // --- Choreo expression-wrapper: {"exp": "1.5 m", "val": 1.5} ---
            // Both "val" (used at runtime) and "exp" (displayed in the Choreo editor) must be
            // mirrored so the editor shows the correct values after mirroring.
            if (fieldName != null && obj.has("val") && obj.get("val").isNumber()) {
                if (isYField(fieldName)) {
                    double mirrored = mirrorY(obj.get("val").asDouble());
                    obj.put("val", mirrored);
                    if (obj.has("exp") && obj.get("exp").isTextual()) {
                        obj.put("exp", rebuildExpString(obj.get("exp").asText(), mirrored));
                    }
                    return; // nothing else in this wrapper needs touching
                }
                if (isHeadingField(fieldName)) {
                    double mirrored = -obj.get("val").asDouble();
                    obj.put("val", mirrored);
                    if (obj.has("exp") && obj.get("exp").isTextual()) {
                        obj.put("exp", rebuildExpString(obj.get("exp").asText(), mirrored));
                    }
                    return;
                }
            }

            // Collect field names first to avoid any issues with concurrent modification.
            List<String> keys = new ArrayList<>();
            obj.fieldNames().forEachRemaining(keys::add);

            for (String key : keys) {
                JsonNode child = obj.get(key);

                if (child.isNumber()) {
                    // Leaf numeric value — mirror if the key matches.
                    if (isYField(key)) {
                        obj.put(key, mirrorY(child.asDouble()));
                    } else if (isHeadingField(key)) {
                        obj.put(key, -child.asDouble());
                    }
                    // Plain numbers that don't match are left unchanged; no recursion needed.
                } else {
                    // Object or array — recurse, passing the current key as context.
                    mirrorNodeRecursive(child, key);
                }
            }
            return;
        }

        if (node.isArray()) {
            // Pass the parent's field name down so expression-wrapper detection works inside
            // arrays (e.g. an array of waypoints each having {"y": {"val": ...}}).
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                mirrorNodeRecursive(arr.get(i), fieldName);
            }
        }
    }

    /**
     * Rebuilds a Choreo {@code exp} string by replacing its leading numeric token with
     * {@code newValue} — which is always in the canonical internal unit (radians for angles,
     * meters for positions) — converting it into whatever display unit the original suffix
     * specifies before formatting.
     *
     * <p>Supported unit conversions:
     * <ul>
     *   <li>{@code deg}  — radians  → degrees  (× 180 / π)
     *   <li>{@code rad}  — radians  → radians  (no conversion)
     *   <li>{@code m}    — meters   → meters   (no conversion)
     *   <li>{@code in}   — meters   → inches   (÷ 0.0254)
     *   <li>{@code ft}   — meters   → feet     (÷ 0.3048)
     *   <li>(none)       — used as-is
     * </ul>
     *
     * <p>Examples (heading field, newValue already negated in radians):
     * <pre>
     *   rebuildExpString("180 deg",   -Math.PI)  → "-180 deg"
     *   rebuildExpString("-0.785 rad", 0.785)    → "0.785 rad"
     *   rebuildExpString("3.14159",  -3.14159)   → "-3.14159"
     * </pre>
     *
     * <p>Examples (y field, newValue already mirrored in meters):
     * <pre>
     *   rebuildExpString("1.5 m",   3.0694) → "3.0694 m"
     *   rebuildExpString("59.04 in", 3.0694) → "120.84... in"
     * </pre>
     *
     * <p>If the string does not start with a recognisable number the original is returned
     * unchanged, which is safer than corrupting an expression Choreo cannot parse.
     *
     * @param original the original {@code exp} string from the JSON
     * @param newValue the already-computed mirrored/negated value in canonical internal units
     * @return the rebuilt expression string with the number converted to the display unit
     */
    private static String rebuildExpString(String original, double newValue) {
        Matcher m = EXP_NUMBER_PATTERN.matcher(original.trim());
        if (!m.matches()) {
            // Unrecognised format (e.g. a symbolic expression) — leave it alone rather than
            // corrupt something Choreo cannot re-parse.
            return original;
        }

        String suffix = m.group(2).trim(); // e.g. "m", "rad", "deg", "in", "ft", or ""

        // Convert newValue (always in canonical internal units) to the display unit.
        double display;
        switch (suffix.toLowerCase()) {
            case "deg":
                // newValue is in radians; exp must show degrees
                display = Math.toDegrees(newValue);
                break;
            case "in":
                // newValue is in meters; exp must show inches
                display = newValue / 0.0254;
                break;
            case "ft":
                // newValue is in meters; exp must show feet
                display = newValue / 0.3048;
                break;
            default:
                // "rad", "m", or no unit — no conversion needed
                display = newValue;
                break;
        }

        String formatted = formatDouble(display);
        return suffix.isEmpty() ? formatted : formatted + " " + suffix;
    }

    /**
     * Formats a double with up to 10 significant decimal digits, stripping unnecessary
     * trailing zeros, so output looks like {@code "3.0694"} not {@code "3.0694000000"}.
     */
    private static String formatDouble(double v) {
        String s = String.format("%.10f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /** Reflects {@code y} about the field's horizontal centerline. */
    private static double mirrorY(double y) {
        return (2.0 * FIELD_CENTER_Y_METERS) - y;
    }

    /**
     * Returns {@code true} if {@code field} is a y-coordinate or y-component field name.
     * Covers trajectory samples (y, vy, ay), waypoint position variants, and common aliases.
     */
    private static boolean isYField(String field) {
        switch (field.toLowerCase()) {
            case "y":
            case "ypos":
            case "yposition":
            case "ymeters":
            case "vy":           // y component of velocity in trajectory samples
            case "velocityy":
            case "ay":           // y component of acceleration
            case "accelerationy":
            case "vertical":
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns {@code true} if {@code field} is a heading or angular-velocity field name.
     * Covers robot heading, waypoint rotation, and angular velocity in trajectory samples.
     */
    private static boolean isHeadingField(String field) {
        switch (field.toLowerCase()) {
            case "heading":
            case "headingradians":
            case "theta":
            case "rotation":
            case "angleradians":
            case "omega":        // angular velocity in trajectory samples
                return true;
            default:
                return false;
        }
    }
}