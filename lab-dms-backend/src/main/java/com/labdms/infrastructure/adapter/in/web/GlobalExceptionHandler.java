package com.labdms.infrastructure.adapter.in.web;

import com.labdms.domain.exception.DomainException;
import com.labdms.domain.exception.UnauthorizedAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(com.labdms.domain.exception.LatexSecurityException.class)
    public ResponseEntity<ErrorResponse> handleLatexSecurity(com.labdms.domain.exception.LatexSecurityException ex) {
        // Tell the user exactly which construct was rejected and why,
        // instead of an opaque message key.
        logger.warn("LaTeX security violation: {}", ex.getDetail());
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("LATEX_SECURITY_VIOLATION",
                        "Forbidden LaTeX construct: " + ex.getDetail()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex) {
        logger.warn("Domain exception: {}", ex.getMessage());
        // For a full implementation, we'd map exception types to specific i18n keys
        // and fetch translated messages using messageSource.getMessage(...)
        ErrorResponse error = new ErrorResponse("BAD_REQUEST", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Explains WHY access was refused, not just that it was.
     *
     * <p>Every refusal used to read "Access denied", so a reviewer who could not
     * approve had no way to tell whether they lacked the permission, were the
     * document's own author, or were simply not the person assigned to it. Those
     * are three different problems with three different fixes, and an
     * administrator hitting the self-review rule reasonably concluded the system
     * was broken.
     *
     * <p>Saying which rule applied is safe here: the caller is authenticated and
     * already knows the document exists — unlike a login form, where naming the
     * reason leaks who has an account.
     */
    private static final java.util.Map<String, String> DENIAL_REASONS = java.util.Map.of(
            "approve_own_document",
            "Você é o autor deste documento e por isso não pode aprová-lo. "
            + "Peça a revisão a outra pessoa com permissão de revisor — a separação entre "
            + "quem escreve e quem aprova é exigida pela norma.",
            "approve_not_assigned_reviewer",
            "Este documento tem outro revisor designado. Só essa pessoa, ou um administrador, pode decidir.",
            "approve",
            "Você não tem permissão para aprovar revisões. Peça a um administrador "
            + "o papel de Revisor.",
            "approve_not_in_sector",
            "Você tem permissão de revisor, mas não pertence ao setor deste documento. "
            + "Peça a um administrador para incluí-lo no setor, em Configurações → Setores.",
            "edit",
            "Você não tem permissão para editar este documento.",
            "view",
            "Você não tem acesso a este documento.",
            "delete",
            "Você não tem permissão para excluir documentos."
    );

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedAccessException ex) {
        Object[] args = ex.getArgs();
        String reason = (args != null && args.length > 0) ? String.valueOf(args[0]) : "";
        logger.warn("Access refused ({})", reason.isEmpty() ? "unspecified" : reason);

        String message = DENIAL_REASONS.getOrDefault(reason,
                messageSource.getMessage("error.unauthorized", null, "Acesso negado.",
                        LocaleContextHolder.getLocale()));

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(org.springframework.dao.EmptyResultDataAccessException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(org.springframework.dao.EmptyResultDataAccessException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "Resource not found"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "File exceeds the maximum upload size"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        // Used for conflicts that are valid states but block the requested
        // operation (e.g. deleting a group still referenced by documents).
        logger.warn("Conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        // Validation errors from domain value objects (e.g. DocumentCode format,
        // duplicate document code) are client errors, not server errors.
        logger.warn("Invalid argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", ex.getMessage()));
    }

    /**
     * A failed sign-in is a normal outcome, not a server fault.
     *
     * <p>Without this, {@code BadCredentialsException} fell through to the
     * catch-all below and the user was told "Internal server error" with a 500 —
     * indistinguishable from the backend actually being broken. It also filled
     * the log with stack traces for every mistyped password, burying the real
     * errors among them.
     *
     * <p>The message is deliberately the same whether the account does not exist
     * or the password is wrong: telling an attacker which e-mails are registered
     * turns the login form into a directory of users.
     */
    @ExceptionHandler({
            org.springframework.security.authentication.BadCredentialsException.class,
            org.springframework.security.core.userdetails.UsernameNotFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleBadCredentials(Exception ex) {
        logger.warn("Failed sign-in attempt: {}", ex.getMessage());
        String translatedMsg = messageSource.getMessage("error.badCredentials", null,
                "E-mail ou senha inválidos.", LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("BAD_CREDENTIALS", translatedMsg));
    }

    /** The account exists but is not active (pending approval or disabled). */
    @ExceptionHandler(org.springframework.security.authentication.DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabled(Exception ex) {
        logger.warn("Sign-in attempt on an inactive account: {}", ex.getMessage());
        String translatedMsg = messageSource.getMessage("error.accountDisabled", null,
                "Esta conta ainda não está ativa. Fale com um administrador.",
                LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("ACCOUNT_DISABLED", translatedMsg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllUncaughtException(Exception ex) {
        logger.error("Unknown error occurred", ex);
        String translatedMsg = messageSource.getMessage("error.internal", null, "Internal server error", LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", translatedMsg));
    }

    public record ErrorResponse(String code, String message) {}
}
