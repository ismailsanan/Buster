package core;

import java.util.ArrayList;
import java.util.List;

/**
 * parses the headers/cookie box into name/value pairs for HttpEngine
 *
 * two input styles both work, mixed freely:
 *
 *   full header lines, one per line
 *     Cookie: session=abc123; role=admin
 *     Authorization: Bearer eyJ...
 *     X-Api-Key: 9f8c...
 *
 *   a bare cookie string is also accepted on a line and wrapped
 *     session=abc123; role=admin      ->   Cookie: session=abc123; role=admin
 */
public class Headers {

    public static List<HttpEngine.HeaderKV> parse(String raw) {
        List<HttpEngine.HeaderKV> out = new ArrayList<>();
        if (raw == null) return out;

        for (String line : raw.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;

            int colon = t.indexOf(':');

            // "Name: value" style
            if (colon > 0) {
                String name = t.substring(0, colon).trim();
                String value = t.substring(colon + 1).trim();
                if (!name.isEmpty() && !value.isEmpty())
                    out.add(new HttpEngine.HeaderKV(name, value));
                continue;
            }

            // a bare "key=value; key2=value2" line is treated as a Cookie
            if (t.contains("=")) {
                out.add(new HttpEngine.HeaderKV("Cookie", t));
            }
        }
        return out;
    }
}