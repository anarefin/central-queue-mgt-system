import type { ReactNode } from "react";
import { Skeleton } from "./components";

export interface DataTableColumn<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  sortable?: boolean;
  /** Render this column's cell as an accessible row header (`<th scope="row">`) instead of `<td>` — for the one
   * column, if any, that names/identifies the row (unrelated to `sortable`). */
  rowHeader?: boolean;
}

export interface DataTableSort {
  key: string;
  direction: "ascending" | "descending";
}

export interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  sort?: DataTableSort;
  onSortChange?: (key: string) => void;
  loading?: boolean;
  loadingRowCount?: number;
  emptyState?: ReactNode;
  /** An accessible `<caption>`, naming the table for assistive tech the same way a heading names a section. */
  caption?: ReactNode;
}

/** Column headers with an optional sort indicator, a loading-skeleton state, an empty slot, and horizontal scroll kept inside the card, not the page. */
export function DataTable<T>({ columns, rows, rowKey, sort, onSortChange, loading = false, loadingRowCount = 3, emptyState, caption }: DataTableProps<T>) {
  const showEmpty = !loading && rows.length === 0;

  return (
    <div className="max-w-full overflow-x-auto rounded-lg border border-border">
      <table className="w-full min-w-max text-start text-sm" aria-busy={loading || undefined}>
        {caption && <caption className="px-3 py-2 text-start text-fg-muted">{caption}</caption>}
        <thead className="sticky top-0 z-10 bg-surface-muted">
          <tr className="border-b border-border bg-surface-muted">
            {columns.map((column) => {
              const isSorted = sort?.key === column.key;
              return (
                <th key={column.key} scope="col" aria-sort={column.sortable ? (isSorted ? sort.direction : "none") : undefined} className="px-3 py-2 text-start font-medium text-fg-muted">
                  {column.sortable ? (
                    <button
                      type="button"
                      onClick={() => onSortChange?.(column.key)}
                      className="inline-flex items-center gap-1 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
                    >
                      {column.header}
                      {isSorted && <span aria-hidden="true">{sort.direction === "ascending" ? "▲" : "▼"}</span>}
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {loading &&
            Array.from({ length: loadingRowCount }, (_, rowIndex) => (
              <tr key={`skeleton-${rowIndex}`} className="border-b border-border last:border-0">
                {columns.map((column) => (
                  <td key={column.key} className="px-3 py-2">
                    <Skeleton className="h-4 w-full" />
                  </td>
                ))}
              </tr>
            ))}
          {!loading &&
            rows.map((row) => (
              <tr key={rowKey(row)} className="border-b border-border last:border-0">
                {columns.map((column) =>
                  column.rowHeader ? (
                    <th key={column.key} scope="row" className="px-3 py-2 text-start font-medium text-fg">
                      {column.render(row)}
                    </th>
                  ) : (
                    <td key={column.key} className="px-3 py-2 text-fg">
                      {column.render(row)}
                    </td>
                  ),
                )}
              </tr>
            ))}
        </tbody>
      </table>
      {showEmpty && <div className="p-6">{emptyState}</div>}
    </div>
  );
}
