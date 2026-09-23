import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Picker } from "./picker";

function options(count: number) {
  return Array.from({ length: count }, (_, i) => ({ value: `v${i}`, label: `Option ${i}` }));
}

describe("Picker", () => {
  it("renders a plain select at or under the search threshold", () => {
    const onChange = vi.fn();
    render(<Picker id="p1" label="Zone" value="" onChange={onChange} options={options(3)} placeholder="Choose…" />);
    expect(screen.getByRole("combobox", { name: "Zone" })).toBeInTheDocument(); // a <select> also has role combobox
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("sends the chosen value from the plain select", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Picker id="p1" label="Zone" value="" onChange={onChange} options={options(3)} placeholder="Choose…" />);
    await user.selectOptions(screen.getByRole("combobox", { name: "Zone" }), "v1");
    expect(onChange).toHaveBeenCalledWith("v1");
  });

  it("becomes a searchable combobox past the threshold and filters as the caller types", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<Picker id="p2" label="Service" value="" onChange={onChange} options={options(15)} placeholder="Choose…" noMatchesLabel="No matches" />);
    const input = screen.getByRole("combobox", { name: "Service" });
    expect(input.tagName).toBe("INPUT");

    await user.click(input);
    expect(screen.getAllByRole("option")).toHaveLength(15);

    await user.type(input, "Option 1");
    const optionsShown = screen.getAllByRole("option");
    expect(optionsShown.map((o) => o.textContent)).toEqual(["Option 1", "Option 10", "Option 11", "Option 12", "Option 13", "Option 14"]);
  });

  it("chooses an option from the searchable list and shows its label", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<Picker id="p3" label="Agent" value="" onChange={onChange} options={options(12)} placeholder="Choose…" />);
    const input = screen.getByRole("combobox", { name: "Agent" });
    await user.click(input);
    await user.click(screen.getByRole("option", { name: "Option 3" }));

    expect(onChange).toHaveBeenCalledWith("v3");
    rerender(<Picker id="p3" label="Agent" value="v3" onChange={onChange} options={options(12)} placeholder="Choose…" />);
    expect(screen.getByRole("combobox", { name: "Agent" })).toHaveValue("Option 3");
  });

  it("says so when the search matches nothing", async () => {
    const user = userEvent.setup();
    render(<Picker id="p4" label="Service" value="" onChange={vi.fn()} options={options(12)} placeholder="Choose…" noMatchesLabel="No matches" />);
    await user.type(screen.getByRole("combobox", { name: "Service" }), "zzz");
    expect(screen.getByText("No matches")).toBeInTheDocument();
  });

  it("closes the list and clears the query on Escape", async () => {
    const user = userEvent.setup();
    render(<Picker id="p5" label="Service" value="" onChange={vi.fn()} options={options(12)} placeholder="Choose…" />);
    const input = screen.getByRole("combobox", { name: "Service" });
    await user.type(input, "Option 1");
    expect(screen.getByRole("listbox")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("disables the control while loading", () => {
    render(<Picker id="p6" label="Service" value="" onChange={vi.fn()} options={[]} loading placeholder="Choose…" />);
    expect(screen.getByRole("combobox", { name: "Service" })).toBeDisabled();
  });

  it("fires onOpen on focus, for a plain select and a searchable combobox alike (ticket 64: load on demand)", async () => {
    const onOpen = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<Picker id="p7" label="Zone" value="" onChange={vi.fn()} options={options(3)} placeholder="Choose…" onOpen={onOpen} />);
    await user.click(screen.getByRole("combobox", { name: "Zone" }));
    expect(onOpen).toHaveBeenCalledTimes(1);

    rerender(<Picker id="p8" label="Service" value="" onChange={vi.fn()} options={options(12)} placeholder="Choose…" onOpen={onOpen} />);
    await user.click(screen.getByRole("combobox", { name: "Service" }));
    expect(onOpen).toHaveBeenCalledTimes(2);
  });
});
