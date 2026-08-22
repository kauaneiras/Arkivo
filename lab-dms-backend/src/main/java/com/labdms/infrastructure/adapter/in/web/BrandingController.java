package com.labdms.infrastructure.adapter.in.web;

import com.labdms.domain.model.audit.AuditEntry;
import com.labdms.domain.port.out.AuditLogPort;
import com.labdms.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The installation's visual identity: organisation name, logo and accent colour.
 *
 * <p>Readable without signing in, because the login screen has to paint itself
 * before anyone is authenticated. Writing requires ADMIN.
 */
@RestController
@RequestMapping("/api/v1/branding")
@Tag(name = "Branding", description = "Organisation name, logo and accent colour")
public class BrandingController {

    /** Roughly 512 KB of base64 — comfortable for a logo, far from a payload. */
    private static final int MAX_LOGO_CHARS = 700_000;

    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /**
     * Only raster formats a browser renders as an image. SVG is excluded on
     * purpose: it is XML that can carry script, and this value is injected into
     * an {@code <img src>} on a page that is served before login.
     */
    private static final Pattern ALLOWED_LOGO =
            Pattern.compile("^data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\\s]+$");

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogPort auditLog;

    public BrandingController(JdbcTemplate jdbcTemplate, AuditLogPort auditLog) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLog = auditLog;
    }

    public record BrandingDto(String organisationName, String logoDataUrl, String primaryColor) {}

    @GetMapping
    @Operation(summary = "The organisation's name, logo and accent colour")
    public ResponseEntity<BrandingDto> get() {
        List<BrandingDto> rows = jdbcTemplate.query("""
                SELECT organisation_name, logo_data_url, primary_color FROM branding WHERE id = 1
                """, (rs, n) -> new BrandingDto(
                        rs.getString(1), rs.getString(2), rs.getString(3)));
        // Falls back to the defaults rather than erroring: a missing row must
        // not be able to keep people off the login screen.
        return ResponseEntity.ok(rows.isEmpty()
                ? new BrandingDto("Lab DMS", null, "#2563EB")
                : rows.get(0));
    }

    public record UpdateBrandingRequest(String organisationName, String logoDataUrl,
                                         String primaryColor, Boolean removeLogo) {}

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change the organisation's identity (Admin only)")
    public ResponseEntity<BrandingDto> update(
            @RequestBody UpdateBrandingRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        if (request.organisationName() != null && request.organisationName().isBlank()) {
            throw new IllegalArgumentException("The organisation name cannot be empty");
        }
        if (request.primaryColor() != null && !HEX_COLOUR.matcher(request.primaryColor()).matches()) {
            throw new IllegalArgumentException("The colour must be a hex value such as #2563EB");
        }
        if (request.logoDataUrl() != null && !request.logoDataUrl().isBlank()) {
            if (request.logoDataUrl().length() > MAX_LOGO_CHARS) {
                throw new IllegalArgumentException("The logo is too large. Use an image under about 400 KB.");
            }
            if (!ALLOWED_LOGO.matcher(request.logoDataUrl()).matches()) {
                throw new IllegalArgumentException(
                        "The logo must be a PNG, JPEG, GIF or WebP image. SVG is not accepted because "
                        + "it can carry executable content.");
            }
        }

        boolean clearLogo = Boolean.TRUE.equals(request.removeLogo());

        // A plain UPDATE with COALESCE on the CURRENT value, so an omitted field
        // keeps what is stored. The previous version put COALESCE(?, 'Lab DMS')
        // in an INSERT ... ON CONFLICT, which meant EXCLUDED was never null:
        // saving only the colour silently reset the organisation name back to
        // "Lab DMS", and saving only the name reset the colour to blue. The row
        // is created by the migration, so there is nothing to insert here.
        int updated = jdbcTemplate.update("""
                UPDATE branding SET
                    organisation_name = COALESCE(?, organisation_name),
                    logo_data_url = CASE WHEN ? THEN NULL ELSE COALESCE(?, logo_data_url) END,
                    primary_color = COALESCE(?, primary_color),
                    updated_at = NOW(),
                    updated_by = ?
                 WHERE id = 1
                """,
                request.organisationName() == null ? null : request.organisationName().trim(),
                clearLogo, clearLogo ? null : request.logoDataUrl(),
                request.primaryColor(), principal.getId());

        // Belt and braces: if the row is somehow absent (a database restored
        // from before V12), create it rather than silently doing nothing.
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO branding (id, organisation_name, logo_data_url, primary_color, updated_by)
                    VALUES (1, COALESCE(?, 'Lab DMS'), ?, COALESCE(?, '#2563EB'), ?)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    request.organisationName() == null ? null : request.organisationName().trim(),
                    clearLogo ? null : request.logoDataUrl(),
                    request.primaryColor(), principal.getId());
        }

        auditLog.log(AuditEntry.create(principal.getId(), "BRANDING_UPDATED", "Branding", null,
                Map.of("organisationName", String.valueOf(request.organisationName()),
                        "primaryColor", String.valueOf(request.primaryColor()),
                        "logoChanged", String.valueOf(clearLogo || request.logoDataUrl() != null)), null));

        return get();
    }
}
