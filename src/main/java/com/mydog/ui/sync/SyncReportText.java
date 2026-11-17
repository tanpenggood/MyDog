package com.mydog.ui.sync;

import com.mydog.i18n.I18n;
import com.mydog.sync.DataCounts;
import com.mydog.sync.SyncAction;
import com.mydog.sync.SyncPlan;
import com.mydog.sync.SyncReport;

import java.util.List;
import java.util.Locale;

/**
 * Plain-text summary of a run, used by the last wizard step and by "export report".
 *
 * <p>Deliberately a formatter over the model rather than a widget: the same text has to land in a
 * file a user can attach to a bug report, where no UI exists to render it.
 */
final class SyncReportText {

    private SyncReportText() {
    }

    static String of(SyncWizardModel model) {
        SyncReport report = model.report();
        SyncPlan plan = model.plan();
        StringBuilder sb = new StringBuilder();
        sb.append("MyDog sync report\n");
        sb.append("source: ").append(plan == null ? "?" : plan.sourceLabel())
                .append(" -> target: ").append(plan == null ? "?" : plan.targetLabel()).append('\n');
        sb.append("mode: ").append(model.mode())
                .append(", chunk: ").append(model.chunkSize())
                .append(", drop extraneous: ").append(model.dropExtraneous()).append('\n');

        if (report == null) {
            sb.append("\n(not executed)\n");
            appendPlanDetail(sb, plan);
            return sb.toString();
        }

        DataCounts totals = report.totals();
        sb.append(String.format(Locale.ROOT, "%nactions: %d done, %d failed, %d skipped%n",
                report.count(SyncAction.Status.DONE), report.count(SyncAction.Status.FAILED),
                report.count(SyncAction.Status.SKIPPED)));
        sb.append(String.format(Locale.ROOT, "rows: %d inserted, %d updated, %d deleted (%d scanned)%n",
                totals.inserts(), totals.updates(), totals.deletes(), totals.rowsScanned()));
        sb.append(String.format(Locale.ROOT, "elapsed: %.1f s%s%n", report.millis() / 1000.0,
                report.cancelled() ? "  (CANCELLED — partial)" : ""));

        if (!report.errors().isEmpty()) {
            sb.append("\n").append(I18n.tr("sync.report.errors")).append(":\n");
            for (String error : report.errors()) sb.append("  ").append(error).append('\n');
        }

        sb.append("\n").append(I18n.tr("sync.report.detail")).append(":\n");
        for (SyncAction action : report.actions()) {
            sb.append(String.format(Locale.ROOT, "  %-8s %-34s %-8s %s%n",
                    action.kind(), action.object().qualified(), action.status(),
                    describe(action)));
        }
        return sb.toString();
    }

    private static void appendPlanDetail(StringBuilder sb, SyncPlan plan) {
        if (plan == null) return;
        sb.append("\nplanned:\n");
        for (SyncAction action : plan.actions()) {
            sb.append(String.format(Locale.ROOT, "  %-8s %-34s %s%n",
                    action.kind(), action.object().qualified(),
                    action.detail() == null ? "" : action.detail()));
        }
        List<String> warnings = plan.warnings();
        if (!warnings.isEmpty()) {
            sb.append('\n').append(I18n.tr("sync.warnings")).append(":\n");
            warnings.forEach(w -> sb.append("  ").append(w).append('\n'));
        }
    }

    private static String describe(SyncAction action) {
        StringBuilder sb = new StringBuilder();
        if (!action.statements().isEmpty()) {
            sb.append(action.statements().size()).append(" ddl");
        }
        DataCounts counts = action.dataCounts();
        if (counts != null && counts.changes() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('+').append(counts.inserts())
                    .append(" ~").append(counts.updates())
                    .append(" -").append(counts.deletes());
        }
        if (action.detail() != null && !action.detail().isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append('[').append(action.detail()).append(']');
        }
        if (action.message() != null && !action.message().isEmpty()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(action.message());
        }
        return sb.toString();
    }
}
