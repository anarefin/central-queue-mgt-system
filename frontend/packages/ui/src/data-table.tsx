import type { ReactNode } from "react";
import { Skeleton } from "./components";

export interface DataTableColumn<T> {
  key: string;
  header: ReactNode;
  render: (row: T) => ReactNode;
  sortable?: boolean;
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
}

/** Column headers with an optional sort indicator, a loading-skeleton state, an empty slot, and horizontal scroll kept inside the card, not the page. */
export function DataTable<T>({ columns, rows, rowKey, sort, onSortChange, loading = false, loadingRowCount = 3, emptyState }: DataTableProps<T>) {
  const showEmpty = !loading && rows.length === 0;

  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-start text-sm">
        <thead>
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
                {columns.map((column) => (
                  <td key={column.key} className="px-3 py-2 text-fg">
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
        </tbody>
      </table>
      {showEmpty && <div className="p-6">{emptyState}</div>}
    </div>
  );
}
