package com.labdms.domain.port.out;

import com.labdms.domain.model.audit.AuditEntry;

/**
 * Output port for audit logging.
 */
public interface AuditLogPort {

    void log(AuditEntry entry);
}
