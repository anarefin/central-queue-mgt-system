import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Badge, Button, Card, EmptyState, PageHeader, SelectField, Skeleton, TextField } from "./components";

describe("Button", () => {
  it("renders its children and responds to a click", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save</Button>);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("marks itself busy and disabled while loading, and ignores clicks", async () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Save
      </Button>,
    );
    const button = screen.getByRole("button", { name: "Save" });
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it.each(["primary", "secondary", "ghost", "danger"] as const)("renders the %s variant without throwing", (variant) => {
    render(<Button variant={variant}>Go</Button>);
    expect(screen.getByRole("button", { name: "Go" })).toBeInTheDocument();
  });
});

describe("Card", () => {
  it("renders header, actions and children together", () => {
    render(
      <Card header="Sites" actions={<button type="button">Add</button>}>
        <p>Body</p>
      </Card>,
    );
    expect(screen.getByText("Sites")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add" })).toBeInTheDocument();
    expect(screen.getByText("Body")).toBeInTheDocument();
  });

  it("renders without a header or actions", () => {
    render(<Card>Just body</Card>);
    expect(screen.getByText("Just body")).toBeInTheDocument();
  });
});

describe("PageHeader", () => {
  it("renders the title, description and actions", () => {
    render(<PageHeader title="Sites" description="Manage sites" actions={<button type="button">New site</button>} />);
    expect(screen.getByRole("heading", { name: "Sites" })).toBeInTheDocument();
    expect(screen.getByText("Manage sites")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New site" })).toBeInTheDocument();
  });
});

describe("Badge", () => {
  it.each(["neutral", "ok", "warn", "danger", "info"] as const)("renders the %s variant", (variant) => {
    render(<Badge variant={variant}>Label</Badge>);
    expect(screen.getByText("Label")).toBeInTheDocument();
  });
});

describe("EmptyState", () => {
  it("renders title, body and an action", () => {
    render(<EmptyState title="No sites yet" body="Add your first site to get started." action={<button type="button">Add site</button>} />);
    expect(screen.getByText("No sites yet")).toBeInTheDocument();
    expect(screen.getByText("Add your first site to get started.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add site" })).toBeInTheDocument();
  });
});

describe("Skeleton", () => {
  it("renders as decorative, hidden from assistive tech", () => {
    const { container } = render(<Skeleton className="h-4 w-full" />);
    expect(container.firstChild).toHaveAttribute("aria-hidden", "true");
  });
});

describe("TextField", () => {
  it("links help text via aria-describedby", () => {
    render(<TextField label="Name" id="name" helpText="As it appears on the badge" />);
    const input = screen.getByLabelText("Name");
    expect(input).toHaveAccessibleDescription("As it appears on the badge");
  });

  it("shows an error as an alert linked by aria-describedby, replacing help text", () => {
    render(<TextField label="Name" id="name" helpText="As it appears on the badge" error="Name is required" />);
    const input = screen.getByLabelText("Name");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("Name is required");
    expect(input).toHaveAccessibleDescription("Name is required");
  });

  it("still forwards ordinary input props", async () => {
    const onChange = vi.fn();
    render(<TextField label="Name" id="name" placeholder="Jane Doe" onChange={onChange} />);
    const input = screen.getByPlaceholderText("Jane Doe");
    await userEvent.type(input, "x");
    expect(onChange).toHaveBeenCalled();
  });
});

describe("SelectField", () => {
  const options = [
    { value: "en", label: "English" },
    { value: "bn", label: "Bangla" },
  ];

  it("renders every option and keeps its label association", () => {
    render(<SelectField label="Language" id="language" options={options} />);
    const select = screen.getByLabelText("Language");
    expect(select).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Bangla" })).toBeInTheDocument();
  });

  it("shows an error as an alert linked by aria-describedby", () => {
    render(<SelectField label="Language" id="language" options={options} error="Choose a language" />);
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a language");
    expect(screen.getByLabelText("Language")).toHaveAttribute("aria-invalid", "true");
  });
});
