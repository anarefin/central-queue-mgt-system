package com.qms.reporting;

import java.util.function.Consumer;

/** The detailed token report's rows, handed one at a time to whichever writer is exporting them (ticket 49) — the
 * inline path wraps {@link DetailedTokenReportReads#stream} directly; a future report's own export would supply its
 * own source without any writer needing to change. */
@FunctionalInterface
interface ReportRowSource {

    void forEach(Consumer<DetailedTokenReportReads.Row> consumer);
}
