package com.abcft.pdfextract.core.table;

public enum OutputFormat {
    CSV,
    CSV_ESCAPE, // Internal use only
    TSV,
    GSON,
    GSON_TEST,
    JSON,
    HTML;

    public static String[] formatNames() {
        OutputFormat[] values = OutputFormat.values();
        String[] rv = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            if (CSV_ESCAPE == values[i]) // Internal use only
                continue;
            rv[i] = values[i].name();
        }
        return rv;
    }
}
