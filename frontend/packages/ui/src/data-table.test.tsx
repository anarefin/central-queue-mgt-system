import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DataTable, type DataTableColumn } from "./data-table";

interface Site {
  id: string;
  name: string;
  timezone: string;
}

const SITES: Site[] = [
  { id: "1", name: "Main campus", timezone: "Asia/Dhaka" },
  { id: "2", name: "North branch", timezone: "Asia/Dhaka" },
];

const COLUMNS: DataTableColumn<Site>[] = [
  { key: "name", header: "Name", render: (row) => row.name, sortable: true },
  { key: "timezone", header: "Timezone", render: (row) => row.timezone },
];

describe("DataTable", () => {
  it("renders column headers and every row", () => {
    render(<DataTable columns={COLUMNS} rows={SITES} rowKey={(row) => row.id} />);
    expect(screen.getByRole("columnheader", { name: "Name" })).toBeInTheDocument();
    expect(screen.getByText("Main campus")).toBeInTheDocument();
    expect(screen.getByText("North branch")).toBeInTheDocument();
  });

  it("shows the empty slot instead of rows when there are none", () => {
    render(<DataTable columns={COLUMNS} rows={[]} rowKey={(row) => row.id} emptyState={<p>No sites yet</p>} />);
    expect(screen.getByText("No sites yet")).toBeInTheDocument();
    expect(screen.queryByRole("row", { name: /Main campus/ })).not.toBeInTheDocument();
  });

  it("renders skeleton rows instead of data while loading", () => {
    const { container } = render(<DataTable columns={COLUMNS} rows={[]} rowKey={(row) => row.id} loading loadingRowCount={2} emptyState={<p>No sites yet</p>} />);
    expect(screen.queryByText("No sites yet")).not.toBeInTheDocument();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(2);
  });

  it("marks the sorted column with aria-sort and calls onSortChange when its header is clicked", async () => {
    const onSortChange = vi.fn();
    render(<DataTable columns={COLUMNS} rows={SITES} rowKey={(row) => row.id} sort={{ key: "name", direction: "ascending" }} onSortChange={onSortChange} />);
    expect(screen.getByRole("columnheader", { name: /Name/ })).toHaveAttribute("aria-sort", "ascending");
    expect(screen.getByRole("columnheader", { name: "Timezone" })).not.toHaveAttribute("aria-sort");

    await userEvent.click(screen.getByRole("button", { name: /Name/ }));
    expect(onSortChange).toHaveBeenCalledWith("name");
  });

  it("renders an accessible caption and a rowHeader column as th scope=row", () => {
    const columns: DataTableColumn<Site>[] = [
      { key: "name", header: "Name", rowHeader: true, render: (row) => row.name },
      { key: "timezone", header: "Timezone", render: (row) => row.timezone },
    ];
    render(<DataTable columns={columns} rows={SITES} rowKey={(row) => row.id} caption="Every site" />);
    expect(screen.getByText("Every site").closest("caption")).toBeInTheDocument();
    const rowHeader = screen.getByRole("rowheader", { name: "Main campus" });
    expect(rowHeader.tagName).toBe("TH");
    expect(screen.getAllByRole("cell", { name: "Asia/Dhaka" })).toHaveLength(2);
  });
});
