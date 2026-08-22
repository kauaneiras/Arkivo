package com.labdms.domain.model.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "IT-001", "PQ-001", "PO-001", "MQ-001",
            "IT-SOLDAGEM-001", "MQ-2026-001",
            "IT-999999",           // max digit length
            "it-001",              // lowercase accepted (normalized upstream)
    })
    void acceptsValidCodes(String code) {
        assertThat(DocumentCode.isValid(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "IT", "IT-", "IT-ABCDEF", "IT-0000000",
            "IT_001", "IT 001", "IT-001-", "-IT-001", "IT--001",
    })
    void rejectsInvalidCodes(String code) {
        assertThat(DocumentCode.isValid(code)).isFalse();
    }

    /**
     * A code carries a type the laboratory defined, so the value object can only
     * judge the SHAPE. "XX-001" used to be rejected here because XX was not one
     * of four hardcoded types; now that a quality manager can create types, that
     * question moved to where the answer lives — DocumentTypeService checks the
     * type exists and is active before a document is created. Keeping the check
     * here as well would silently forbid every new type the lab defines.
     */
    @ParameterizedTest
    @ValueSource(strings = { "XX-001", "CAL-014", "FT-2026-007", "REG01-001" })
    void acceptsCodesOfTypesTheLaboratoryMayHaveDefined(String code) {
        assertThat(DocumentCode.isValid(code)).isTrue();
    }

    @Test
    void extractTypeHandlesPrefixesThatAreNotTwoCharacters() {
        // Taking the first two characters reported "CAL-014" as type "CA".
        assertThat(new DocumentCode("CAL-014").extractType().name()).isEqualTo("CAL");
        assertThat(new DocumentCode("REG01-001").extractType().name()).isEqualTo("REG01");
    }

    @Test
    void rejectsNullCode() {
        assertThat(DocumentCode.isValid(null)).isFalse();
    }

    @Test
    void constructorThrowsOnBlank() {
        assertThatThrownBy(() -> new DocumentCode("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void constructorThrowsOnInvalidFormat() {
        assertThatThrownBy(() -> new DocumentCode("INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid document code format");
    }

    @Test
    void extractTypeReturnsThePrefixAsDocumentType() {
        assertThat(new DocumentCode("IT-SOLDAGEM-001").extractType()).isEqualTo(DocumentType.IT);
        assertThat(new DocumentCode("PQ-001").extractType()).isEqualTo(DocumentType.PQ);
    }
}
