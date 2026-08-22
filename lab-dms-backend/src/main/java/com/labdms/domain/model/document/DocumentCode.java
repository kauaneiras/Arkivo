package com.labdms.domain.model.document;

import java.util.regex.Pattern;

/**
 * Value object representing a document code.
 * Codes follow the pattern: PREFIX-[AREA-]NNN
 * <ul>
 *   <li>IT-XXX-NNN (Work Instruction, with area code)</li>
 *   <li>PQ-NNN (Quality Procedure)</li>
 *   <li>PO-NNN (Standard Operating Procedure)</li>
 *   <li>MQ-NNN (Quality Manual)</li>
 * </ul>
 */
public record DocumentCode(String value) {

    /**
     * Accepted format: TYPE[-SEGMENT...]-NUMBER
     * TYPE    = 1 to 10 letters/digits starting with a letter, defined by the
     *           laboratory in the document_types table
     * SEGMENT = optional alphanumeric qualifiers (e.g. sector), up to 4
     * NUMBER  = 1 to 6 digits
     * Examples: IT-001, IT-SOLDAGEM-001, PQ-001, MQ-2026-001, CAL-014
     *
     * <p>The type used to be the fixed alternation (IT|PQ|PO|MQ). Since a
     * quality manager can now define types, hardcoding four of them here would
     * have made every document of a new type unsaveable — the code would be
     * rejected as malformed at the value object, far from the actual cause.
     * The first segment is unambiguous regardless: it ends at the first dash,
     * and a prefix may not contain one.
     */
    private static final Pattern CODE_PATTERN =
            Pattern.compile("^([A-Z][A-Z0-9]{0,9})(-[A-Z0-9]{1,15}){0,4}-\\d{1,6}$");

    public DocumentCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Document code must not be blank");
        }
        if (!isValid(value)) {
            throw new IllegalArgumentException(
                    "Invalid document code format: " + value +
                    ". Expected TYPE[-SECTOR]-NUMBER, e.g. IT-001, IT-SOLDAGEM-001, PQ-001");
        }
    }

    /**
     * Validates whether the given code matches the accepted document code pattern.
     */
    public static boolean isValid(String code) {
        if (code == null) return false;
        return CODE_PATTERN.matcher(code.toUpperCase()).matches();
    }

    /**
     * Extracts the document type prefix from the code.
     */
    public DocumentType extractType() {
        // Split at the first dash rather than taking two characters: prefixes are
        // no longer all two letters, and "CAL-014" would otherwise report its
        // type as "CA".
        int dash = value.indexOf('-');
        String prefix = dash > 0 ? value.substring(0, dash) : value;
        return DocumentType.valueOf(prefix);
    }

    @Override
    public String toString() {
        return value;
    }
}
