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
            "Você não tem permissão para editar documentos. Peça a um administrador "
            + "o papel de Editor.",
            "edit_not_in_sector",
            "Você tem permissão para editar, mas não pertence ao setor deste documento. "
            + "Peça a um administrador para incluí-lo no setor, em Configurações → Setores. "
            + "(O autor do documento e os administradores não precisam disso.)",
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

    /**
     * Erro vindo da base de dados, com a causa em texto legível.
     *
     * <p>Sem isto, uma restrição violada caía no handler genérico e produzia um
     * 500 com "erro interno" — e no log um rasto de pilha de 180 linhas onde a
     * frase que interessa ("duplicate key", "null value in column X", o nome do
     * índice) fica soterrada no topo, normalmente fora do que alguém copia.
     * O resultado prático é que o defeito só é diagnosticável por quem consegue
     * reproduzi-lo, e não por quem o encontrou.
     *
     * <p>O utilizador continua a ver uma frase genérica: nomes de tabela e de
     * restrição descrevem o desenho interno e não lhe servem de nada. Quem opera
     * o servidor passa a ter, numa única linha, o estado SQL e a mensagem.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(org.springframework.dao.DataAccessException ex) {
        Throwable root = ex.getMostSpecificCause();
        String sqlState = (root instanceof java.sql.SQLException e) ? e.getSQLState() : "?";
        String detalhe = root.getMessage() == null ? String.valueOf(ex.getMessage())
                                                   : root.getMessage().replace('\n', ' ');
        logger.error("Database error [SQLState {}]: {}", sqlState, detalhe, ex);

        // A causa vai TAMBÉM para a resposta, e isso é deliberado.
        //
        // Antes ficava só no log, dentro de um rasto de pilha de 180 linhas cuja
        // primeira linha — a única que diz o que aconteceu — é justamente a que
        // ninguém copia. Na prática o defeito só era diagnosticável por quem
        // conseguia reproduzi-lo. Quem o encontrou ficava com "erro interno".
        //
        // O que sai é a família do erro em linguagem comum, mais o nome da
        // restrição como referência. Um nome de restrição descreve o desenho
        // interno, sim — mas isto é uma ferramenta de laboratório atrás de
        // autenticação, e o custo de o esconder tem sido rondas inteiras a
        // adivinhar. Se um dia for exposta na internet, esta é a primeira linha
        // a rever.
        String restricao = nomeDaRestricao(detalhe);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("DATABASE_ERROR",
                        explicaSqlState(sqlState) + (restricao == null ? "" : " [" + restricao + "]")));
    }

    /** A família do erro, dita de forma que aponte para a ação seguinte. */
    private static String explicaSqlState(String sqlState) {
        return switch (sqlState == null ? "" : sqlState) {
            case "23505" -> "Já existe um registro com esses dados (violação de unicidade).";
            case "23503" -> "Isto depende de um registro que não existe mais.";
            case "23502" -> "Um campo obrigatório ficou vazio.";
            case "23514" -> "Um valor está fora do permitido para este campo.";
            case "22001" -> "Um texto é maior do que o campo comporta.";
            case "40001", "40P01" -> "Duas operações colidiram ao mesmo tempo. Tente de novo.";
            default -> "Não foi possível concluir a operação no banco de dados.";
        };
    }

    /** O nome da restrição violada, que é o que identifica a linha exata. */
    private static String nomeDaRestricao(String mensagem) {
        if (mensagem == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:constraint|índice|index)\\s+\"([^\"]+)\"|coluna \"([^\"]+)\"|column \"([^\"]+)\"")
                .matcher(mensagem);
        if (!m.find()) return null;
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) return m.group(g);
        }
        return null;
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
