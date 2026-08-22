package com.labdms.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = DocumentCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDocumentCode {
    String message() default "Invalid document code format. Examples: IT-SOLDAGEM-001, PQ-001, PO-001, MQ-001";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
