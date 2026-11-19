import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import axios from "axios";
import "./styles.css";

const API = (import.meta.env.VITE_API_URL || "/api").replace(/\/$/, "");
const api = axios.create({ baseURL: API });

function Badge({ children }) {
  return (
    <span className={`badge ${String(children).toLowerCase()}`}>
      {children}
    </span>
  );
}

function apiErrorMessage(error) {
  const detail = error.response?.data?.detail;
  if (typeof detail === "string") return detail;
  if (Array.isArray(detail)) {
    return detail
      .map((issue) => {
        const field = Array.isArray(issue.loc) ? issue.loc.at(-1) : "request";
        return `${field}: ${issue.msg || "invalid value"}`;
      })
      .join("; ");
  }

  return `Cannot reach API (${API}). Verify backend is running.`;
}

function App() {
  const [stats, setStats] = useState(null);
  const [requests, setRequests] = useState([]);
  const [resources, setResources] = useState([]);
  const [tab, setTab] = useState("overview");
  const [notice, setNotice] = useState("");

  const refresh = async () => {
    const [statsResult, requestsResult, resourcesResult] = await Promise.all([
      api.get("/stats"),
      api.get("/requests"),
      api.get("/resources"),
    ]);
    setStats(statsResult.data);
    setRequests(requestsResult.data);
    setResources(resourcesResult.data);
  };

  useEffect(() => {
    refresh().catch(() =>
      setNotice("API is offline. Start the backend service to connect."),
    );
  }, []);

  const submit = async (event, endpoint) => {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const payload = Object.fromEntries(form.entries());
    [
      "latitude",
      "longitude",
      "quantity",
      "urgency_level",
      "available_quantity",
    ].forEach((key) => {
      if (payload[key]) payload[key] = Number(payload[key]);
    });
    try {
      const result = await api.post(endpoint, payload);
      const createdNotice = `Created ${result.data.request_id || result.data.resource_id}`;
      formElement.reset();
      setNotice(createdNotice);
      try {
        await refresh();
      } catch {
        setNotice(
          `${createdNotice}. Dashboard refresh failed; use Refresh data to try again.`,
        );
      }
    } catch (error) {
      setNotice(apiErrorMessage(error));
    }
  };

  return (
    <div className="app-shell">
      <aside>
        <div className="brand">
          <div className="brand-mark">+</div>
          <div>
            <strong>Relief Grid</strong>
            <small>Emergency coordination</small>
          </div>
        </div>
        <nav>
          {[
            ["overview", "Overview"],
            ["request", "New request"],
            ["resource", "Register resource"],
            ["storage", "Storage monitor"],
          ].map(([key, label]) => (
            <button
              className={tab === key ? "active" : ""}
              onClick={() => setTab(key)}
              key={key}
            >
              {label}
            </button>
          ))}
        </nav>
        <div className="side-status">
          <span className="pulse" /> All systems nominal
        </div>
      </aside>
      <main>
        <header>
          <div>
            <p className="eyebrow">Live response network</p>
            <h1>
              {tab === "overview"
                ? "Situation overview"
                : tab === "request"
                  ? "Create emergency request"
                  : tab === "resource"
                    ? "Register a resource"
                    : "Distributed storage"}
            </h1>
          </div>
          <button className="refresh" onClick={() => refresh()}>
            Refresh data
          </button>
        </header>
        {notice && <div className="notice">{notice}</div>}
        {tab === "overview" && (
          <Overview stats={stats} requests={requests} resources={resources} />
        )}
        {tab === "request" && (
          <EntryForm endpoint="/requests" resource={false} onSubmit={submit} />
        )}
        {tab === "resource" && (
          <EntryForm endpoint="/resources" resource onSubmit={submit} />
        )}
        {tab === "storage" && <Storage />}
      </main>
    </div>
  );
}

function Overview({ stats, requests, resources }) {
  const statuses = stats?.requests_by_status || {};
  return (
    <>
      <section className="metric-grid">
        {[
          ["Requests", stats?.requests || 0, "total"],
          ["Pending", statuses.PENDING || 0, "pending"],
          ["Matched", statuses.MATCHED || 0, "matched"],
          [
            "Available resources",
            stats?.resources_by_status?.AVAILABLE || 0,
            "available",
          ],
        ].map(([label, value, tone]) => (
          <article className={`metric ${tone}`} key={label}>
            <span>{label}</span>
            <b>{value}</b>
            <small>live count</small>
          </article>
        ))}
      </section>
      <section className="content-grid">
        <article className="panel wide">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Queue activity</p>
              <h2>Recent emergency requests</h2>
            </div>
            <span className="live-dot">● Live</span>
          </div>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Request</th>
                  <th>Need</th>
                  <th>Location</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {requests.slice(0, 8).map((item) => (
                  <tr key={item.request_id}>
                    <td>
                      <strong>{item.request_id}</strong>
                      <small>
                        {new Date(item.created_at).toLocaleString()}
                      </small>
                    </td>
                    <td>
                      {item.request_type}
                      <small>Qty {item.quantity}</small>
                    </td>
                    <td>{item.location}</td>
                    <td>
                      <Badge>{item.status}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!requests.length && (
              <div className="empty">No requests have been submitted yet.</div>
            )}
          </div>
        </article>
        <article className="panel">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">Capacity</p>
              <h2>Resources</h2>
            </div>
          </div>
          <div className="resource-total">
            <b>{resources.length}</b>
            <span>registered sources</span>
          </div>
          {["AVAILABLE", "ASSIGNED", "UNAVAILABLE"].map((status) => (
            <div className="bar-row" key={status}>
              <span>{status}</span>
              <div>
                <i
                  style={{
                    width: `${resources.length ? (resources.filter((r) => r.availability_status === status).length / resources.length) * 100 : 0}%`,
                  }}
                />
              </div>
              <b>
                {
                  resources.filter((r) => r.availability_status === status)
                    .length
                }
              </b>
            </div>
          ))}
        </article>
      </section>
    </>
  );
}

function EntryForm({ endpoint, resource, onSubmit }) {
  return (
    <form
      className="entry-form"
      onSubmit={(event) => onSubmit(event, endpoint)}
    >
      <div className="form-intro">
        <p className="eyebrow">
          {resource ? "Expand the response network" : "Signal a need"}
        </p>
        <h2>
          {resource ? "Add available capacity" : "Request urgent assistance"}
        </h2>
        <p>
          Information is routed to the matching service as soon as it is
          submitted.
        </p>
      </div>
      <label>
        Type
        <select
          name={resource ? "resource_type" : "request_type"}
          defaultValue="water"
        >
          {["water", "food", "medicine", "transportation", "shelter"].map(
            (item) => (
              <option key={item}>{item}</option>
            ),
          )}
        </select>
      </label>
      <label>
        {resource ? "Provider name" : "Description"}
        <input
          name={resource ? "provider_name" : "description"}
          required
          placeholder={resource ? "Community NGO" : "What is needed?"}
        />
      </label>
      {resource && (
        <label>
          Description
          <input
            name="description"
            required
            placeholder="Describe the available resource"
          />
        </label>
      )}
      <label>
        Location
        <input name="location" required placeholder="Central Relief Camp" />
      </label>
      <div className="two-col">
        <label>
          Latitude
          <input
            name="latitude"
            type="number"
            step="any"
            min="-90"
            max="90"
            required
          />
        </label>
        <label>
          Longitude
          <input
            name="longitude"
            type="number"
            step="any"
            min="-180"
            max="180"
            required
          />
        </label>
      </div>
      <div className="two-col">
        <label>
          {resource ? "Available quantity" : "Quantity"}
          <input
            name={resource ? "available_quantity" : "quantity"}
            type="number"
            min="1"
            required
          />
        </label>
        {!resource && (
          <label>
            Urgency level
            <input
              name="urgency_level"
              type="number"
              min="1"
              max="5"
              defaultValue="3"
              required
            />
          </label>
        )}
        {resource && (
          <label>
            Contact information
            <input
              name="contact_information"
              required
              placeholder="phone or email"
            />
          </label>
        )}
      </div>
      <button className="primary" type="submit">
        Submit to response network
      </button>
    </form>
  );
}

function Storage() {
  const [data, setData] = useState(null);
  useEffect(() => {
    api
      .get("/storage/distribution")
      .then((result) => setData(result.data.shards));
  }, []);
  return (
    <section className="storage-grid">
      {(
        data || [0, 1, 2].map((shard_id) => ({ shard_id, record_count: "-" }))
      ).map((shard) => (
        <article className="shard" key={shard.shard_id}>
          <div className="shard-header">
            <span>SHARD {shard.shard_id}</span>
            <Badge>HEALTHY</Badge>
          </div>
          <b>{shard.record_count}</b>
          <small>records routed by SHA-256</small>
          <div className="replicas">
            <span>● Primary</span>
            <span>● Secondary</span>
          </div>
        </article>
      ))}
    </section>
  );
}

createRoot(document.getElementById("root")).render(<App />);
