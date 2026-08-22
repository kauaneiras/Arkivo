package com.labdms.shared.util;

/**
 * Stamps a status watermark across every page of a compiled document.
 *
 * <p>ISO/IEC 17025:2017 clause 8.3.2 f) requires obsolete documents to be
 * "clearly identified ... to prevent unintended use". A status badge that only
 * exists on screen does not satisfy that: the moment somebody downloads or
 * prints the PDF, an obsolete revision becomes indistinguishable from the one in
 * force. Assessors test this by walking the lab and looking at what is lying on
 * the bench.
 *
 * <p>Implemented by injecting LaTeX into the preamble rather than post-processing
 * the PDF, which keeps the backend free of a PDF-manipulation dependency and
 * reuses the engine (and its cache) we already run. The {@code eso-pic} and
 * {@code xcolor} packages ship with texlive-latex-extra, already in the image.
 */
public final class LatexWatermark {

    /** What is being marked — each carries a different meaning to a reader. */
    public enum Kind {
        /** The document itself was withdrawn from use (8.3.2 f). */
        OBSOLETE("DOCUMENTO OBSOLETO", "1,0,0"),
        /** The document is valid, but this is not the revision in force. */
        NOT_IN_FORCE("VERSAO NAO VIGENTE", "0.85,0.45,0"),
        /** Not approved yet — must not be used to perform work. */
        DRAFT("RASCUNHO", "0.45,0.45,0.45");

        private final String label;
        private final String rgb;

        Kind(String label, String rgb) {
            this.label = label;
            this.rgb = rgb;
        }
    }

    private LatexWatermark() {
        // utility class
    }

    /**
     * Returns the source with a diagonal watermark on every page.
     *
     * <p>The injection point is right before {@code \begin{document}} so it lands
     * after the user's own packages and cannot be overridden by them. If the
     * source has no {@code \begin{document}} (it would not compile anyway), the
     * text is returned untouched rather than corrupted.
     */
    public static String apply(String latexSource, Kind kind) {
        if (latexSource == null || kind == null) return latexSource;

        int marker = latexSource.indexOf("\\begin{document}");
        if (marker < 0) return latexSource;

        // Background for a document typeset here, foreground for one that wraps
        // an uploaded PDF. A PDF exported from Word or produced by a scanner
        // paints an opaque white rectangle over the whole page, so a background
        // watermark ends up UNDERNEATH it and is invisible — the obsolete
        // revision then prints looking exactly like the approved one, which is
        // the failure 8.3.2 f) exists to prevent. Measured on a PDF with a
        // painted background: 0 watermark pixels in BG, 473 in FG.
        String layer = latexSource.contains("\\includepdf") ? "FG" : "BG";

        String injection = """
                %% --- Lab DMS: ISO/IEC 17025 8.3.2 f) status watermark ---
                \\usepackage{eso-pic}
                \\usepackage{xcolor}
                \\usepackage{graphicx}
                \\makeatletter
                \\AddToShipoutPicture__LAYER__{%
                  \\AtPageCenter{%
                    \\makebox(0,0){%
                      \\rotatebox{45}{%
                        \\color[rgb]{__RGB__}%
                        \\fontsize{54}{54}\\selectfont\\bfseries%
                        \\textcolor[rgb]{__RGB__}{__LABEL__}%
                      }%
                    }%
                  }%
                }
                \\makeatother
                %% --- end watermark ---
                """
                .replace("__LAYER__", layer)
                .replace("__RGB__", kind.rgb)
                .replace("__LABEL__", kind.label);

        return latexSource.substring(0, marker) + injection + latexSource.substring(marker);
    }

    /**
     * Decides which watermark a given revision deserves, or {@code null} when it
     * is the approved revision in force and needs none.
     *
     * @param documentStatus  DRAFT / IN_REVIEW / ACTIVE / OBSOLETE
     * @param isVersionInForce whether the revision being served is the official one
     */
    public static Kind kindFor(String documentStatus, boolean isVersionInForce) {
        if ("OBSOLETE".equals(documentStatus)) return Kind.OBSOLETE;
        if (!isVersionInForce) return Kind.NOT_IN_FORCE;
        if ("DRAFT".equals(documentStatus) || "IN_REVIEW".equals(documentStatus)) return Kind.DRAFT;
        return null;
    }
}
