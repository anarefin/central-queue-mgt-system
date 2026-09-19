package com.qms.audit;

import java.io.IOException;
import java.io.OutputStream;

/** A prepared CSV export. Authorisation and the audit entry for the export happen before this is handed out. */
@FunctionalInterface
public interface AuditExport {

    void writeTo(OutputStream out) throws IOException;
}
