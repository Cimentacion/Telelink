package app.telelink;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Saca un nombre corto ("Naruto 1", "Breaking Bad 2x05") del título de una página de vídeo. */
final class Names {

    private Names() {
    }

    private static final Pattern SEP = Pattern.compile("\\s+[-–—|·»:]+\\s+|\\s*\\|\\s*");
    private static final Pattern LEAD = Pattern.compile("(?i)^(?:ver|watch|mira|mirar)\\s+");
    private static final Pattern SXE = Pattern.compile(
            "(?i)\\bS(\\d{1,2})\\s*[-. ]?\\s*E(\\d{1,4})\\b|\\b(\\d{1,2})x(\\d{1,4})\\b");
    private static final Pattern SEASON = Pattern.compile(
            "(?i)\\b(?:temporada|season|temp\\.?)\\s*(\\d{1,2})\\b");
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)\\b(?:cap[ií]tulo|episodio|episode|cap\\.?|ep\\.?)\\s*#?\\s*(\\d{1,4})\\b");
    private static final Pattern NOISE = Pattern.compile("(?i)\\b(?:online|gratis|full\\s*hd|hd|4k|1080p|720p|480p|360p"
            + "|sub(?:titulad[oa]s?|bed|s)?(?:\\s+(?:en\\s+)?(?:espa[ñn]ol|english|latino|castellano))?"
            + "|dub(?:bed)?|doblad[oa]|espa[ñn]ol|latino|castellano|descargar|download)\\b");

    /**
     * @param title título de la página (puede venir vacío o ser la propia URL)
     * @param site  nombre de la web sin el .com ("animeflv"), para quitarlo del título
     * @param slug  último trozo de la URL, por si no hay título
     */
    static String nice(String title, String site, String slug) {
        String raw = title == null ? "" : title.trim();
        String t = raw;
        boolean fromSlug = false;
        if (t.isEmpty() || t.startsWith("http://") || t.startsWith("https://") || !t.contains(" ") && t.contains(".")) {
            if (slug == null || slug.isEmpty()) return raw;
            t = slug.replaceAll("\\.[A-Za-z0-9]{2,5}$", "").replaceAll("[-_+.]+", " ").trim();
            if (t.isEmpty()) return raw;
            fromSlug = true;
        }

        // Quita el trozo del título que es solo el nombre de la web ("... - AnimeFLV").
        String key = site == null ? "" : site.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String[] parts = SEP.split(t);
        if (parts.length > 1 && key.length() >= 3) {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                String k = p.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (k.isEmpty() || k.equals(key) || k.length() <= key.length() + 4 && k.contains(key)) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(p.trim());
            }
            if (sb.length() > 0) t = sb.toString();
        } else if (parts.length > 1) {
            t = String.join(" ", parts);
        }
        t = LEAD.matcher(t).replaceFirst("");

        String season = null, episode = null;
        int cut = t.length(), end = 0;
        Matcher m = SXE.matcher(t);
        if (m.find()) {
            season = m.group(1) != null ? m.group(1) : m.group(3);
            episode = m.group(2) != null ? m.group(2) : m.group(4);
            cut = m.start();
            end = m.end();
        } else {
            Matcher e = EPISODE.matcher(t);
            if (e.find()) {
                episode = e.group(1);
                cut = e.start();
                end = e.end();
                Matcher s = SEASON.matcher(t);
                if (s.find()) {
                    season = s.group(1);
                    cut = Math.min(cut, s.start());
                    end = Math.max(end, s.end());
                }
            }
        }

        String out;
        if (episode != null) {
            String name = clean(t.substring(0, cut));
            if (name.isEmpty()) name = clean(t.substring(end));
            if (name.isEmpty()) return raw.isEmpty() ? t : raw;
            int ep = Integer.parseInt(episode);
            out = name + " " + (season == null ? String.valueOf(ep)
                    : Integer.parseInt(season) + "x" + (ep < 10 ? "0" : "") + ep);
        } else {
            out = clean(t);
            if (out.length() < 3) return raw.isEmpty() ? t : raw;
        }
        if (fromSlug) out = capitalize(out);
        return out.length() > 60 ? out.substring(0, 60).trim() + "…" : out;
    }

    private static String clean(String s) {
        s = NOISE.matcher(s).replaceAll(" ");
        s = s.replaceAll("[(\\[]\\s*[)\\]]", " ").replaceAll("\\s+", " ");
        return s.replaceAll("^[\\s\\-–—|:·,.»]+|[\\s\\-–—|:·,»]+$", "");
    }

    private static String capitalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
