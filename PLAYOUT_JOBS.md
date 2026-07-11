# Playout rebuilds as tracked jobs

Rebuilding a channel's playout timeline can take minutes. Previously
`POST /api/channels/:id/playout` ran the rebuild **synchronously**, blocking the
HTTP request until every event was generated — which is why the Channel Playout
page showed "No Upcoming Events" on reload while a rebuild was still in flight,
with no indication that anything was happening.

Rebuilds now run as **asynchronous jobs**. The endpoint returns immediately and
the work is tracked through a jobs API whose wire shape is **compatible with the
Tunarr Scheduler** (`fudoniten/tunarr-scheduler`) `/api/jobs` endpoints, so the
Marquee UI can render jobs from either backend with the same code.

This document is split into two parts:

1. **Backend API** — implemented in `fudoniten/pseudovision` (this repo).
2. **Frontend spec** — the changes needed in `fudoniten/marquee`, which is a
   separate repo and was **not** modified here.

---

## 1. Backend API (implemented)

### Job shape

A job serialises to JSON as:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "playout/rebuild",
  "status": "running",
  "metadata": { "channel-id": 7, "from": "now", "horizon-days": 14 },
  "progress": null,
  "created-at": "2026-06-21T18:03:11.120Z",
  "started-at": "2026-06-21T18:03:11.131Z",
  "completed-at": null,
  "duration-ms": 4210,
  "result": null,
  "error": null
}
```

| Field | Notes |
|-------|-------|
| `id` | UUID string. |
| `type` | Job type; currently only `"playout/rebuild"`. |
| `status` | One of `queued`, `running`, `succeeded`, `failed`. |
| `metadata` | Caller-supplied context. For rebuilds: `channel-id`, `from`, `horizon-days`. |
| `progress` | Present only while/after the task reports progress. Either a number or a map (`phase`, `total`, `completed`, `failed`, `skipped`, `current-item`). Rebuild does not currently report granular progress, so this stays absent. |
| `created-at` / `started-at` / `completed-at` | ISO-8601 strings. `started-at` appears once running; `completed-at` once finished. |
| `duration-ms` | Elapsed ms (started→completed, or started→now while running). |
| `result` | Present on `succeeded`. For rebuilds: `{ "message", "events-generated", "horizon-days", "from" }`. |
| `error` | Present on `failed`: `{ "message", "type" }`. |

This matches Tunarr Scheduler's `Job` schema, with a pseudovision-specific
`type` value.

### Endpoints

| Method | Path | Response |
|--------|------|----------|
| `PUT` | `/api/channels/:channel-id/playout` | **200** `{ <Playout> }`. **Attaches a schedule** (`{"schedule-id": N}` body) — a different operation from the `POST` below, on the same path: this one sets which schedule the channel plays, `POST` regenerates its timeline. Creates the playout row on first attach. Pass `?rebuild=true&horizon=<days>` to also submit a rebuild job in the same call. |
| `POST` | `/api/channels/:channel-id/playout` | **202** `{ "job": <Job> }` (or `404` if the channel has no playout). `?from=now\|horizon` and `?horizon=<days>` are unchanged. |
| `GET` | `/api/jobs` | **200** `{ "jobs": [<Job>, …] }`, newest-first. |
| `GET` | `/api/jobs/:job-id` | **200** `{ "job": <Job> }`, or **404** `{ "error": "Job not found" }`. |

**Breaking change:** `POST .../playout` now returns **202 + `{job}`** instead of
**200 + `{message, events-generated, horizon-days}`**. The old result payload is
now the job's `result` field, retrievable once the job succeeds.

### Implementation notes

- `src/pseudovision/jobs/runner.clj` — in-memory `IJobRunner` (atom of jobs,
  background `future` per job, retains the last 100 jobs). Ported from Tunarr
  Scheduler for wire compatibility.
- `src/pseudovision/http/api/jobs.clj` — the two read endpoints.
- `src/pseudovision/http/api/playouts.clj` — `rebuild-playout-handler` submits a
  `:playout/rebuild` job.
- Wired into the Integrant system as `:pseudovision/jobs` and injected into the
  HTTP handler context.

Jobs are **in-memory only**: they do not survive a server restart, and in a
multi-instance deployment each instance tracks its own. That is sufficient for
progress/status polling (matching Tunarr Scheduler's model). If durable history
is needed later, back the runner with a `jobs` table.

> **Not implemented (possible follow-up):** the backend does not yet de-duplicate
> concurrent rebuilds of the same channel — two `POST`s start two jobs. The
> frontend button-disable below is the first line of defence; if stronger
> protection is wanted, add a guard that returns the in-flight job for a channel
> instead of starting a second.

---

## 2. Frontend spec (Marquee — not yet implemented)

These changes belong in `fudoniten/marquee` and could not be made from this
session (only `fudoniten/pseudovision` was in scope).

### 2a. Channel Playout page — rebuild button

When the user clicks **Rebuild**:

1. `POST /api/channels/:id/playout`. The response is `202 { "job": { "id", … } }`.
   Keep `job.id`.
2. **Disable the button** and show an in-progress affordance (spinner +
   "Rebuilding…"). Persist this state keyed by channel id so a reload while a
   rebuild is running re-enters the in-progress state (see 2c).
3. **Poll** `GET /api/jobs/:job-id` every ~2s until `status` is `succeeded` or
   `failed`.
   - `succeeded` → re-enable the button, refresh the upcoming-events list, show a
     brief success note (`result.events-generated` events generated).
   - `failed` → re-enable the button, surface `error.message`.
4. Because the page already (or should) auto-refresh (2c), the upcoming-events
   list will fill in as soon as the job finishes — replacing the transient
   "No Upcoming Events".

If the pseudovision API client is generated/typed, regenerate it: the rebuild
call's success type changes from the old `{message, events-generated,
horizon-days}` to `{ job: Job }` with HTTP 202.

### 2b. Jobs tab

The pseudovision backend now exposes the same `/api/jobs` contract as Tunarr
Scheduler, so the existing Jobs tab can list pseudovision jobs with the same
component:

- `GET /api/jobs` → `{ jobs: [...] }`. Render `type`, `status`, `created-at`,
  `duration-ms`, and (when present) `progress` / `error.message`.
- A `playout/rebuild` job's `metadata.channel-id` lets you link the row back to
  the relevant Channel Playout page.
- If the Jobs tab currently points only at the Tunarr Scheduler base URL, add
  the pseudovision base URL as a second source and merge/sort by `created-at`.

### 2c. Periodic auto-refresh (general)

The user also wants pages to update without a manual reload — both the Channel
Playout page and the Jobs tab.

- **Simplest:** poll on an interval while a view is mounted (e.g. Jobs tab every
  3–5s; the Channel Playout page every 5–10s, and faster — ~2s — while a rebuild
  job for that channel is in flight). Stop polling on unmount.
- **On job completion:** when a tracked job transitions to a terminal status,
  trigger a refresh of the dependent view (e.g. the upcoming-events list) so the
  UI reflects the new state immediately.
- **Re-enter in-progress state on load:** on mounting the Channel Playout page,
  `GET /api/jobs` and look for a non-terminal `playout/rebuild` job whose
  `metadata.channel-id` matches; if found, show the in-progress UI and resume
  polling that `job.id`. This makes the greyed-out/spinner state survive reloads.
- If you later want push instead of polling, SSE/WebSocket would be the upgrade
  path, but interval polling against `/api/jobs` is enough to satisfy this
  request and keeps both backends symmetric.
