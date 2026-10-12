# Dashboard research: cron and heartbeat monitoring UIs

Research notes for the Cronbell web dashboard redesign (October 2026). This page records **UI conventions**,
not branding or code. Cronbell stays inside `website/DESIGN-SYSTEM.md`: black and white, colour only for
state, system fonts.

Sources: public docs and marketing pages, the open-source templates of Healthchecks.io
(`templates/front/*.html`) and Uptime Kuma (`src/components`, `src/pages`), and the shadcn-admin source.
Points marked *(recalled)* come from familiarity with the product and were not re-checked during this pass.

---

## Healthchecks.io

- **Checks list** (`checks_table.html`): a plain table. The columns are a status icon, Name (with tags
  underneath), Ping URL (a uuid/slug switch in the header), Integrations (one small toggle icon per channel),
  Period with Grace as a muted second line, and Last Ping with Last Duration as a second line. A trailing
  column holds the row menu.
- **Status** has five states: `new`, `up`, `late`, `down`, `paused`. Each is shown as an icon with a tooltip.
  While a run is in progress (a `/start` ping arrived), a small **animated spinner** sits under the icon.
  Only Name and Last Ping can be sorted. Clicking a header cycles through asc, desc and default.
- **Filtering**: tag pills above the table, and each pill takes the colour of its worst check
  (`btn {{status}}`). Next to them are a search box and a dropdown that filters by status.
- **Tab title and favicon reflect state**: the title shows the number down (`num_down_title`) and the favicon
  switches to a red variant when anything is down, on both the list and the detail page.
- **Check detail** is laid out in two columns. The left column has blocks: Description, *How To Ping*
  (click-to-copy URL and email address, a "Usage Examples" modal with snippets per language), *Current
  Status* (icon, status sentence and a monthly downtime table such as "2 downtimes, 14 min total, 99.97%
  uptime" or "All good!", plus Pause and **Ping Now!** buttons), Schedule (period or cron expression, time
  zone, grace time, each with a one-line explanation), Notifications and Danger zone. The right column
  holds the **event log**.
- **Event log rows**: sequence number #n, date and time, a coloured label (`OK`, `Started`, `Failure`,
  `Status 3` for the exit code, `Log`, `Ignored`), duration with a timer icon, the method and source IP, and
  a preview of the request body in `<code>`. Status flips appear as their own rows ("Status: up -> down")
  and missed runs as alert rows. "Show More..." links to the full log page.
- **Empty states** explain what will appear there, for example "You will see a live-updating log of received
  pings here. This check has not received any pings yet." A new project shows an "Add Check" call to action
  next to a sample ping URL and usage snippets *(recalled)*.
- **Integrations page**: a table of configured channels. Each row shows the type, its target, which checks
  it is attached to, and **Test!** and Remove buttons. Below it is a grid of available integrations, each
  with an "Add Integration" button. The checks list mirrors this with a per-check on/off icon for every
  channel.

## Cronitor

- **Monitor list**: one row per job, showing the name, schedule, a status badge, last run, and a **timeline
  strip of recent runs** (one tick per run, coloured by outcome). The marketing UI shows a header counter
  such as "6 monitored · 43 executions" with a time window ("02:00 -> 08:00 · now 07:04").
- **Summaries in plain words**: "1 failed · 1 over threshold · 1 missed", and for a run that is still going,
  "still running · usually done in 21m · now at 24m". Each anomaly is stated against its baseline.
- **Per-job stats**: run count, median duration and success rate ("44 RUNS", "97.7% success").
- **Monitor detail**: charts for success rate % and **duration p50/p95** with a visible threshold line
  ("THRESHOLD 60.0s"), the run history, and an alert history ("all sent alerts and timing").
- **Logs and output**: a STDOUT + STDERR panel per run, labelled with the run number and timestamp
  ("run #612 · 14 SEP 2025 22:00 UTC · last 4 of 12 lines"), so it shows a tail and can expand.
- **Lifecycle**: run/complete/fail events, so a run is a pair of events. Duration and "running" state come
  from that pairing.
- **Onboarding is discovery-first**: install the CLI, then `cronitor sync` scans the crontab ("Found 15 jobs
  in user 'ec2-user' crontab") and imports the jobs in bulk. curl telemetry snippets are the fallback.
- **Alerts**: notification lists such as "Slack#ops-alerts" attached per monitor, with grace period and
  failure tolerance set on the monitor itself.

## Better Stack (Uptime: heartbeats)

- Heartbeats sit in their own navigation section next to monitors, with a list and a primary
  "Create heartbeat" button.
- **The create form is short**: Name, "Expect a heartbeat every" (period), Grace period ("extra time allowed
  before an incident is raised"), and on-call escalation. Each field has its explanatory sentence inline.
- **After creation, the detail page leads with the secret URL** and a copy button. The status reads
  **Pending** until the first request arrives.
- **Endpoints**: base URL for success, `/fail` for an explicit failure, and `/<exit_code>` for exit-code
  reporting. A request body is kept as output for diagnostics.
- Missed heartbeats raise **incidents** (acknowledge, resolve, timeline) instead of only changing state.
  The incident is the main object for alerting.
- Overall look *(recalled)*: dark-first, dense lists with a status dot plus text such as "Up for 3d 4h",
  and coloured uptime bars on monitor pages.

## Uptime Kuma

- **Shell**: a left sidebar holds the monitor list, so it is always visible, and the right pane shows the
  selected monitor. The sidebar has search, a filter dropdown (status, active, tags), a select mode for bulk
  pause and resume, and group folders.
- **List item**: an uptime % pill (coloured by state), name and tags on the left, and a **heartbeat bar** on
  the right.
- **Heartbeat bar** (`HeartbeatBar.vue`): fixed-width rounded beats, about 10px (5px on narrow screens). The
  number of beats depends on the container width (`maxBeat = clientWidth / beatWidth`). Empty placeholder
  beats pad the left, and the caption underneath shows "time since first beat ... now". Classes: up, down,
  pending, maintenance, empty. Hover shows the time and message.
- **Dashboard home** ("Quick Stats"): count tiles for Up, Down, Maintenance, Unknown and Paused. A **number
  is coloured only when non-zero** (Down turns red only if > 0, otherwise muted). Below sits a table of
  important events (Name, Status, DateTime, Message) with "Clear All Events".
- **Detail**: status pill, the large heartbeat bar, a stats row (current response, 24h average, uptime for
  24h and 30d, certificate expiry), a response chart with a range selector, and an important-events table.
  Pause, Edit, Clone and Delete sit in the header.
- **Notifications**: one setup dialog per channel type (90+ providers), each with a **Test** button, a
  "default enabled / apply to all existing monitors" option, and per-monitor checkboxes in the monitor
  form.
- **Empty state** *(recalled)*: "No Monitors, please add one." with the "Add New Monitor" button kept in the
  header.

## Sentry Crons

- **Overview = timeline**: one row per monitor, plus one sub-row per environment, with **check-in ticks**
  plotted on a shared time axis. The window can be the last hour, day, week or month (changelog 2023-06-21).
  It answers "did anything fail or get missed?" at a glance.
- **Check-in statuses**: ok, error (failed), missed, timeout (in_progress that never completed within max
  runtime), in progress. Ticks are colour-coded per status and tooltips aggregate counts per bucket
  *(colours recalled: green / red / yellow / orange / grey)*.
- **Detail page**: a daily bar chart of successful, failed and missed check-ins, a **line chart of the
  average runtime**, an Issues section that links errors raised during runs, and a table of recent
  check-ins (status, started, duration, environment, expected time).
- **Environments** are a first-class part of a monitor: one schedule, separate status per environment.
- **Onboarding**: setup is split by method (SDK per platform, Sentry CLI wrapper, HTTP curl), each with a
  ready snippet. **Upsert**: monitors can be created implicitly by the first check-in with its config
  attached, so no form is needed. *(Tabs layout recalled.)*
- Alerts are tied to the monitor's incident (missed, timeout or error) with failure and recovery
  thresholds.

## Dead Man's Snitch

- **Snitch list**: a status icon per snitch (the green check for Healthy), the name, interval, tags and last
  check-in. *(recalled)* The status words are Pending, Healthy, Failed (missed), Errored (reported error)
  and Paused.
- **Onboarding is guided**: name it, choose an interval (start with a test hourly snitch) and an alert type
  (Basic or Smart). The result page shows the **unique URL plus tabbed snippets** (cURL, Field Agent,
  email, Ruby, Python). Then "come back to the list and watch it turn green".
- **The interval model is explained in plain words**: "on the hour, every hour". A check-in at 2:10 covers
  the 2-3 period, and the alert fires at 4:01 if nothing arrives in 3-4.
- Each snitch gets its own email check-in address as an alternative to HTTP.
- **Integrations**: Slack, PagerDuty, Opsgenie, webhooks and email, set per team and toggled per snitch.
- **Detail** *(recalled)*: check-in history as a strip of period cells (one per expected interval), with
  the latest check-in message or exit code.

---

## shadcn-admin (satnaing/shadcn-admin)

Stack: shadcn/ui (Radix and Tailwind), Vite, TanStack Router and Table, Lucide icons. The parts worth taking:

- **Shell** (`components/layout/authenticated-layout.tsx`): `SearchProvider > LayoutProvider >
  SidebarProvider` wraps `SkipToMain`, `AppSidebar` and `SidebarInset`. The open/closed sidebar state is
  kept in a cookie (`sidebar_state`). The content is a container-query root (`@container/content`), so
  tables adapt to the content width rather than the viewport.
- **Sidebar** (`app-sidebar.tsx`): `SidebarHeader` holds a team switcher (or a plain `AppTitle`),
  `SidebarContent` maps `navGroups` from one data file (`layout/data/sidebar-data.ts`) through `NavGroup`,
  `SidebarFooter` holds `NavUser`, and `SidebarRail` handles drag/click collapse. Collapsible
  (icon / offcanvas) and variant (inset / floating / sidebar) are runtime settings.
- **Header** (`header.tsx`): 64px high with a SidebarTrigger, a vertical separator, then page slots
  (Search as a fake input that opens the command menu, ThemeSwitch, ProfileDropdown). With `fixed`, it is
  sticky and gains a shadow plus backdrop blur once scrolled past 10px.
- **Page header** (`features/tasks/index.tsx`): an h2 title, a muted one-line description, and the primary
  buttons on the right (`flex-wrap items-end justify-between`).
- **Command menu** (`command-menu.tsx`): a `CommandDialog` opened with ⌘K. It is **generated from the
  same `sidebarData`**: each nav group becomes a command group, with nested items shown as "Group > Item".
  A Theme group (Light/Dark/System) follows, then "No results found.", inside a ScrollArea 72 units high.
- **Data table toolbar** (`data-table/toolbar.tsx`): a search input on the left (by column or global, h-8),
  then one **faceted filter** per configured column, then a ghost "Reset x" button that only appears when
  filters are active. On the right sits **View options**.
- **Faceted filter** (`faceted-filter.tsx`): an outline button with a dashed border and a "+" icon plus
  the title. Once values are picked, it shows a separator and badges (the chosen labels, or "N selected"
  above 2, or just the count on small screens). The popover is a Command list with check marks, optional
  icons and **facet counts** per option (`getFacetedUniqueValues`), plus "Clear filters".
- **View options** (`view-options.tsx`): a "View" dropdown with a "Toggle columns" checkbox list over
  hideable columns.
- **Pagination** (`pagination.tsx`): a rows-per-page select (10 to 50), "Page X of Y", and first/prev,
  numbered pages with ellipsis, then next/last. The layout reflows with container queries (first and last
  are hidden on narrow screens).
- **Table state lives in the URL** (`useTableUrlState`): global filter, column filters (arrays) and
  pagination are search params, so filtered views can be shared and survive a reload. Sorting, visibility
  and selection stay local.
- **Bulk actions** (`bulk-actions.tsx`): selecting rows brings up a floating action bar.

---

## Takeaways for Cronbell

1. **Status = coloured dot + word**, e.g. `● Down`, `● Late`, `● Running`, `● Up`, `● Paused`, `● New`. The
   word carries the meaning and the dot is the only colour (fits "colour only for state"). Paused and New stay
   grey. Never use colour alone (Healthchecks relies on icons with tooltips; we spell the word out).
2. **Problems first by default**: sort by severity (Down > Late > Running-overdue > Up > Paused > New), then
   by last run. Put the count in the page title and switch the favicon when anything is down (Healthchecks).
3. **A run-history strip on every row**: the last ~30 runs as fixed-width rounded ticks (Uptime Kuma
   style), padded with empty ticks for new jobs, with the newest on the right. Hover shows time, duration
   and exit code. Use colour on failed/missed ticks only; OK ticks stay neutral black/grey so a failure
   stands out against a monochrome strip.
4. **Columns for the jobs table**: Status · Name (with source app or tags on a muted second line) · Schedule
   (cron with a human second line such as "every day 02:00 UTC") · Last run (relative time, duration as a
   second line) · Next expected · History strip · row menu. Use Healthchecks' two-line cells instead of
   adding columns.
5. **State anomalies against a baseline in plain words**, as Cronitor does: "Running 24m, usually 21m",
   "Missed 02:00 run", "Failed 3 times in a row". Use these sentences in the detail header and the overview
   list.
6. **Overview = counters + needs-attention list**: Down / Late / Running / Up / Paused tiles where the
   number is coloured **only when non-zero** (Uptime Kuma), then a short list of failing jobs and a recent
   status-changes feed ("nightly-report: Up -> Down, 02:14").
7. **The job detail page has a fixed structure**: a header with the status sentence and the actions (Pause,
   Run now/test ping, Edit). Below it come a run-history strip, a duration chart (p50 line plus max-duration
   threshold, as Cronitor does), the schedule block (cron, time zone, grace, each with a one-line
   explanation, as Healthchecks does), and then a **runs table** (#, started, duration, result label, exit
   code, host). Each run can expand to show the tail of its output in monospace with "last N of M lines"
   (Cronitor).
8. **Treat status flips and missed runs as rows in the run log** (Healthchecks), so the log tells the whole
   story without a separate incident page.
9. **The empty state is the onboarding**: "No jobs yet". Primary path: add `cronbell-spring-boot-starter`
   (Maven/Gradle snippet, auto-discovers `@Scheduled`, which is our answer to Cronitor's `cronitor sync`).
   Secondary path: tabs for curl / shell wrapper with the ping URL already filled in and a copy button. End
   with "Waiting for first run..." that flips live to the first tick (Better Stack's Pending, DMS's "watch it
   turn green").
10. **The integrations page is a table of channels**: type, target, attached jobs (all/N), and a **Test**
    button on every row (all products have it). Add-channel is a short dialog. Per-job overrides belong in
    the job's settings, not in a matrix.
11. **Use the shadcn-admin shell as-is**: a collapsible sidebar driven by one nav data file (Overview, Jobs,
    Alerts/Channels, Settings), a sticky 64px header with a ⌘K search, a command menu built from the same
    nav data **plus a jobs group** (jump to any job by name), a page title, a muted description and primary
    actions on the right.
12. **Jobs table toolbar**: search by name, faceted filters with counts for Status, App/Source and Tag,
    Reset, View options, and filters and pagination kept in the URL. Default to 50 rows (job lists are
    longer and scanned, not paged through). Bulk select for Pause/Resume/Delete.

## What the redesign took, and what it left

Taken: dot + word status (1), problems first (2), run ticks per row, padded for new checks (3), two-line
cells (4), plain-word reasons such as "Missed the 02:00 run" (5), counters plus a needs-attention list (6),
the detail page order with a duration chart against the max runtime (7), the onboarding empty state with
ready snippets (9), a Test button on every channel (10), the shadcn-admin shell and command menu (11), and
search, a status filter with counts and URL state in the table toolbar (12).

Left for later, with the reason:

- OK ticks are green, as the brief asked; Takeaway 3's neutral OK ticks would make a failure stand out more
  and is a one-line change in `run-bar.tsx`.
- A status-change feed and status flips in the run log (6, 8) need state transitions to be stored; the
  server only keeps them in the alert outbox today.
- Missed runs appear only as the run a check is currently DOWN for: a missed run leaves no ping.
- Tags, apps, bulk actions, "Run now" and "usually 21m" baselines have no data or API yet.
- The page title count and favicon switch (2) were not built.
