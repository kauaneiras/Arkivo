package com.labdms.shared.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class DocumentCodeValidator implements ConstraintValidator<ValidDocumentCode, String> {

    @Override
    public boolean isValid(String code, ConstraintValidatorContext context) {
        // Single source of truth: same rule as the DocumentCode value object
        // (TYPE[-SECTOR]-NUMBER, e.g. IT-001, IT-SOLDAGEM-001, PQ-001)
        return com.labdms.domain.model.document.DocumentCode.isValid(code);
    }
}
