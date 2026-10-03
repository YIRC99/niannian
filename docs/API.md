# API contract v1

Base URL entered in Android is origin e.g. http://192.168.1.10:56507. All paths below include /api/v1. JSON UTF-8. Bearer opaque token except health/auth. Errors {"error":"human readable Chinese"}. Timestamps RFC3339. IDs strings. Unknown fields rejected for writes. Server owns user identity, recipients and media.

GET /health -> {"status":"ok","version":"1.2.0"}
POST /api/v1/auth/code {"email":"..."} -> {"message":"验证码已发送"}
POST /api/v1/auth/verify {"email":"...","code":"123456"} -> {"token":"...","user":{"id":"...","email":"..."}}
Registration is open: any syntactically valid email can request a code, and the first successful verification creates its account. Repeat verification reuses the same account. Legacy allowed_emails configuration is ignored across login, sessions, and reminder delivery. OTP expiry/rate limits and user-data isolation still apply.
GET /api/v1/me -> {"id":"...","email":"..."}
POST /api/v1/auth/logout -> {"ok":true}

Rule fields: {"kind":"monthly_window","mode":"until_done","timezone":"Asia/Shanghai","times":["09:00"],"start_date":"2026-10-03","end_date":"","weekdays":[],"month_start":1,"month_end":15,"every_days":1,"max_reminders":0}
kind = once | daily | weekly | monthly_window | date_range | interval | ongoing.
mode = until_done (one completion ends current cycle) | every_day (completion only stops that day's remaining reminders) | notify_once (only first notification each cycle, no completion required).
times: unique HH:mm 1..8 values. weekdays 1=Mon..7=Sun. max_reminders 0 unlimited, otherwise per cycle cap 1..100. once requires start_date, date_range requires both dates, interval requires start_date and every_days (1..365), monthly_window needs month_start<=month_end, 1..31 (clamp end to last actual day; start 31 means last day in short months). Daily repeating kinds honor optional start/end bounds. All use calendar day arithmetic.
ongoing requires start_date, optional end_date, mode=until_done only. Repeats daily until one permanent completion, fixed cycle ongoing:<start_date>. Use this for daily reminders until a one-time obligation is fulfilled with no explicit deadline; daily kind resets completion each day.

GET /api/v1/tasks -> {"tasks":[TaskView]}
POST /api/v1/tasks {"title":"...","description":"...","rule":Rule} -> TaskView
GET /api/v1/tasks/{id} -> TaskView (owner can also read archived:true tasks for history navigation; all mutations still reject archived tasks)
PUT /api/v1/tasks/{id} {"title":"...","description":"...","rule":Rule,"paused":false} -> TaskView
PUT /api/v1/tasks/{id}/paused {"paused":true} -> TaskView (owner only, rejects archived tasks; changes only paused/updated_at, preserves the rule and snooze state)
DELETE /api/v1/tasks/{id} -> {"ok":true} (soft archive, preserve history)
POST /api/v1/tasks/{id}/complete {"text":"...","media_ids":["..."]} -> Completion
POST /api/v1/tasks/{id}/snooze {"minutes":60} -> {"ok":true} (1..1440, durable)
TaskView {"id":"...","title":"...","description":"...","rule":Rule,"paused":false,"created_at":"...","updated_at":"...","summary":"每月 1—15 日，每天 09:00，完成后停止本月提醒","status":"pending|done|upcoming|paused|expired","next_reminder":"RFC3339 or empty","cycle_key":"..."}
Completion {"id":"...","task_id":"...","task_title":"...","cycle_key":"...","text":"...","media_ids":[],"created_at":"..."}
GET /api/v1/history -> {"records":[Completion]}
GET /api/v1/notifications -> {"notifications":[{"id":"...","task_id":"...","task_title":"...","scheduled_at":"...","sent_at":"...","status":"sent|failed|pending","error":"..."}]}
GET /api/v1/calendar?month=2026-10&timezone=Asia%2FShanghai -> {"month":"2026-10","timezone":"Asia/Shanghai","generated_at":"RFC3339","entries":[CalendarEntry],"truncated":false}
CalendarEntry {"id":"stable identifier","type":"planned|notification|completion","task_id":"...","task_title":"...","at":"RFC3339","state":"upcoming|pending|completed|expired|notified","cycle_key":"...","cycle_end":"RFC3339 or empty"}. Notification entries retain scheduled_at/sent_at/status/error; completion entries retain created_at/text/media_ids. Planned entries are projections, never persisted notifications and never evidence that mail was sent.

The calendar endpoint accepts one valid YYYY-MM month and explicit IANA display timezone, returns only the authenticated user's entries, and bounds the response at 20,000 entries or a 6 MiB serialization budget, with an explicit truncated flag. Both actual records and projected times are grouped by their instant in that display timezone. Projection uses the same schedule rules as delivery, excludes times before task creation, honors completed cycles, notification caps and snoozing, and suppresses unsent plans for paused/archived tasks. Actual notifications override the projected entry at the same task+instant; actual completions and their text/photos remain available. Every-third-day rules use start_date as their calendar anchor.

Calendar states: upcoming = future reminder; pending = due but its completion cycle has not ended; expired = cycle ended without completion; completed = actual completion or an earlier reminder in a completed cycle; notified = a successful notify_once reminder (no completion required). Mail transport failures do not determine completion or expiry. Historical notifications use their saved rule snapshot; historical completions with no reliable cycle boundary leave cycle_end empty. Unsent past projections use the current rule only from max(created_at,updated_at), conservatively avoiding fabricated history after edits. Future forecasts may change when the user completes, pauses or edits a task.

Android loads the selected month, filters selected days locally, and rejects stale responses after month/account/page changes. Old servers without the endpoint show an explicit update notice instead of pretending an empty calendar is complete; stored records remain unchanged on the server. task_id provides owner-authenticated navigation to the reminder, including read-only archived reminders.
POST /api/v1/media multipart field file (jpeg/png/webp, max 10MB) -> {"id":"...","url":"/api/v1/media/{id}"}
GET /api/v1/media/{id} -> authenticated bytes
POST /api/v1/ai/parse {"text":"每月1到15号提醒申报，完成就不提醒"} -> {"title":"申报财务信息","description":"...","rule":Rule,"summary":"...","warnings":[]}. Draft only, validate before returning, no silent save.
GET /api/v1/export -> {"tasks":[...],"records":[...],"notifications":[...]} for user's data. Media exported separately via authorized media routes.

Scheduler package contract (Go): type Rule matching JSON above; Validate(Rule) error; Summary(Rule) string; CycleAt(Rule,time.Time) (Cycle,bool), Cycle {Key string; Start,End time.Time}; Due(Rule,from,to time.Time) []Occurrence, Occurrence {At time.Time; CycleKey string}; Next(Rule,after time.Time) (time.Time,bool). Complete cycle includes day for every_day, otherwise monthly YYYY-MM, weekly ISO year/week, date_range fixed window. Dates are inclusive; interval is anchored to start_date. Backend checks archived/paused/snoozed/completed cycle plus successful reminder count before enqueue/send. Notification unique(task,cycle_key,scheduled_at). Future schedule edits must not resurrect already-sent slots; completion history survives edits. Catchup limited to current local day and latest due slot to avoid spam after downtime.

Backend dependency contract: internal/config Config loads embedded defaults from cmd/server/secrets.local.json at build, optional config.local.json at runtime and NIANNIAN_* env overrides. Root owns config, internal/ai and internal/mail packages; backend agent owns internal/app and cmd/server/main.go. app.New(cfg config.Config) (*App,error); App.Handler() http.Handler; App.RunScheduler(context.Context); App.Close() error. internal/mail New(config.Config) *Client, Send(ctx,to,subject,body string) error. internal/ai New(config.Config) *Client, Parse(ctx,text string,now time.Time) (Draft,error), Draft {Title,Description string; Rule schedule.Rule; Summary string; Warnings []string}. Config fields Addr,DataDir,DeepSeekAPIKey,DeepSeekBaseURL,DeepSeekModel,SMTPHost,SMTPUser,SMTPPassword,SMTPPort int,AllowedEmails []string,PublicURL string. SMTPPort only field int; remaining except AllowedEmails strings. Defaults port56507, data ./data, tz bundled. No dev auth bypass in production.
