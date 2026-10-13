package fr.tibkpl.openut2004;

import java.util.List;

/** Modification minimale des fichiers .ini d'Unreal (sections [Nom], lignes cle=valeur). */
final class Ini {

    private Ini() {}

    /** Fixe key=value dans [section] (cree la cle ou la section si besoin). */
    static void set(List<String> lines, String section, String key, String value) {
        String header = "[" + section + "]";
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(header)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            lines.add("");
            lines.add(header);
            lines.add(key + "=" + value);
            return;
        }
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("[")) {
                end = i;
                break;
            }
        }
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase(key)) {
                lines.set(i, key + "=" + value);
                return;
            }
        }
        lines.add(start + 1, key + "=" + value);
    }
}
