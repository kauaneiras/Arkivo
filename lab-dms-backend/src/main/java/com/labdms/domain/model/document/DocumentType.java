package com.labdms.domain.model.document;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The kind of laboratory document, identified by the prefix that opens its code.
 *
 * <p>This was an enum with four fixed values. It is a value object now because
 * the set of document types belongs to the laboratory, not to the build: one lab
 * needs "FT" for datasheets, another "CAL" for calibration records, and ISO 9000
 * prescribes no taxonomy. The authoritative list lives in the
 * {@code document_types} table, which a quality manager maintains; this type
 * carries the prefix through the domain and enforces the shape a prefix must
 * have to be parseable back out of a document code.
 *
 * <p>The four original values remain as constants so existing code and tests
 * keep reading naturally, and {@link #valueOf(String)} keeps its name so the
 * enum's call sites did not have to change — but it now accepts any well-formed
 * prefix rather than throwing on anything outside a fixed list.
 *
 * <p>Equality is by prefix, which is what makes a type identical to itself
 * across a round trip through the database.
 */
public final class DocumentType {

    /**
     * A prefix has to survive being concatenated into "PREFIX-001" and parsed
     * back, so it may not contain the separator or anything that would make the
     * split ambiguous.
     */
    private static final Pattern VALID_PREFIX = Pattern.compile("^[A-Z][A-Z0-9]{0,9}$");

    /** Instrução de Trabalho. */
    public static final DocumentType IT = new DocumentType("IT");
    /** Procedimento da Qualidade. */
    public static final DocumentType PQ = new DocumentType("PQ");
    /** Procedimento Operacional Padrão. */
    public static final DocumentType PO = new DocumentType("PO");
    /** Manual da Qualidade. */
    public static final DocumentType MQ = new DocumentType("MQ");

    private final String prefix;

    private DocumentType(String prefix) {
        this.prefix = prefix;
    }

    /**
     * @throws IllegalArgumentException when the prefix could not appear in a
     *         document code
     */
    public static DocumentType of(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Document type is required");
        }
        String normalised = prefix.trim().toUpperCase();
        if (!VALID_PREFIX.matcher(normalised).matches()) {
            throw new IllegalArgumentException(
                    "Invalid document type '" + prefix + "': use 1 to 10 letters or digits, starting with a letter");
        }
        return new DocumentType(normalised);
    }

    /** Kept from the enum API so existing call sites read unchanged. */
    public static DocumentType valueOf(String prefix) {
        return of(prefix);
    }

    /** Kept from the enum API: the prefix is the type's name. */
    public String name() {
        return prefix;
    }

    public String getPrefix() {
        return prefix;
    }

    /** The four types the system ships with, used to seed a fresh database. */
    public static DocumentType[] values() {
        return new DocumentType[] { MQ, PQ, PO, IT };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentType other)) return false;
        return prefix.equals(other.prefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix);
    }

    /**
     * Serialised as the bare prefix, so the JSON shape is exactly what the enum
     * produced and no client had to change.
     */
    @Override
    public String toString() {
        return prefix;
    }

    @com.fasterxml.jackson.annotation.JsonValue
    public String toJson() {
        return prefix;
    }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static DocumentType fromJson(String prefix) {
        return of(prefix);
    }
}
