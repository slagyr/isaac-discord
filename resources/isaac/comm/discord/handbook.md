# isaac.comm.discord — the Discord comm

You are a crew running inside Isaac. This chapter covers **isaac-discord**:
the module that connects Isaac to a Discord bot account, accepts inbound
messages over the Gateway websocket, and posts replies back over Discord's
REST API. Read `isaac.foundation` first (config mechanics,
`handbook__configure`, hot reload) and `isaac.agent` (crews, sessions, the
`Comm` protocol, `comm__send`, delivery retries) — this chapter assumes
both and only covers what Discord itself owns.

isaac-discord activates **lazily**: with no Discord token configured, the
module never loads at all — no running component, no Gateway connection.
Setting a token (even on an already-running server) activates it and opens
the connection; removing the token closes it again. There's no dedicated
top-level CLI command of its own — everything below routes through the
generic `config`, `sessions`, and `logs` commands `isaac.foundation` and
`isaac.agent` already cover.

## The Discord comm slot

**What it is.** A Discord bot is one entry in the `comms` table (owned by
`isaac.agent`; see that chapter's Comms and delivery). Every comm entry
carries a `type` and a `crew`; Discord contributes the rest of the fields,
each namespaced `discord/…`:

- `discord/token` — the bot token, always as a `${VAR}` reference, never a
  literal secret.
- `discord/allow-from` — `{:users [...] :guilds [...]}`, two independent
  allowlists (see Inbound routing, below).
- `discord/message-cap` — max characters per outbound message before
  splitting (default 2000, Discord's own hard cap).
- `discord/channels` — per-channel routing overrides (see Inbound
  routing).

If the comm entry is named `discord`, `type` can be omitted — the slot's
own name doubles as the impl id. Any other slot name (a second bot, say)
needs `type discord` set explicitly.

```
config set comms.discord.discord/token ${DISCORD_BOT_TOKEN}
config set comms.discord.discord/allow-from.guilds[<guild-id>]
config set comms.discord.crew harbormaster
```

A comm-wide `crew` and (read from config today but not yet declared in
this module's own schema — `[verify]`) `model` set the fallback a channel
falls through to when it has no override of its own; see Inbound routing
for the full cascade.

**How to verify.** `config get comms.discord` shows the merged entry
(`--raw` for exactly what's written vs. schema-filled). A resolved token
prints as `<DISCORD_BOT_TOKEN:redacted>`; `<DISCORD_BOT_TOKEN:UNRESOLVED>`
means the environment variable isn't set anywhere Isaac can see it.

### Troubleshooting

- **The Discord client never starts even though a token is set.** Confirm
  this is inside `isaac server` — the component only starts in the server
  process, never for a plain CLI command (see `isaac.foundation`'s
  Runtime).
- **`config get comms.discord` shows the token as `<VAR:UNRESOLVED>`.**
  The referenced environment variable isn't set — check the root's
  `.env` or the process environment; this is an operator-side fix, not
  something `handbook__configure` can repair.
- **A second bot's config seems to be ignored entirely.** If its comm
  entry isn't literally named `discord`, it needs its own explicit
  `type discord` — the impl id otherwise falls back to the slot's own
  name, which won't resolve to anything.

## Client lifecycle and reconnect

**What it is.** On server start (or the moment a token appears via hot
reload), Isaac opens a Gateway websocket, identifies, and starts a
heartbeat on the interval Discord's own `HELLO` frame specifies.
`:discord.client/started` and `:discord.client/stopped` mark connect and
disconnect in the log; reloading an **unchanged** token neither logs
anything nor reconnects — the runtime reads the live config slice fresh
per message, so routing and allow-from changes take effect without
bouncing the connection.

A dropped connection reconnects on its own: a **resumable** close
(Discord codes 4000–4003 or 4008) resumes the existing session with its
preserved session id and last sequence number; any other non-fatal close
(1000, 1001, 1006, 4007, 4009, or an unrecognized code) re-identifies from
scratch. A **fatal** close (4004, or ≥4010 — `[verify]`, likely an
invalid-token or disallowed-intents class of error) logs
`:discord.gateway/fatal-close` and does **not** reconnect; that needs an
operator to fix the token or intents (a config change plus a fresh
connect, or a server restart). Reconnect attempts back off starting at
1 second, capping at 30, and give up after 8 tries, logging
`:discord.gateway/reconnect-exhausted` — a park, not a crash; nothing
auto-recovers a parked client except a config change or a restart. A
background watchdog also checks the client every 60 seconds and forces a
reconnect if it's gone more than 5 minutes without reaching `READY`, in
case a close was somehow missed. None of these timings are configurable —
they're fixed internal constants.

**How to verify.** `isaac logs server` shows `:discord.gateway/connected`,
`:discord.gateway/ready`, `:discord.gateway/disconnected`, and the
reconnect-attempt / reconnect-exhausted events as they happen. There's no
live status command for this comm — the log stream is the view.

### Troubleshooting

- **The client keeps reconnecting in a loop.** Check the `:reason` /
  `:status` on each `:discord.gateway/disconnected` entry in
  `isaac logs server` — a resumable code (4000-range) is expected churn;
  a run of 1006/1001 closes points at network flakiness between this
  host and Discord, not a config problem.
- **The client never reconnects after a network blip.** Look for
  `:discord.gateway/fatal-close` or `:discord.gateway/reconnect-exhausted`
  — either stops the client outright until a token change or a restart; a
  plain disconnect always retries on its own first.
- **A stale connection doesn't seem to be recovering.** The watchdog only
  forces a reconnect after 5 straight minutes without `READY` — a
  `:discord.watchdog/check` entry showing `:connected false` for less
  than that is still within the window where it's expected to heal
  itself.

## Inbound routing — channels, sessions, crew, and model

**What it is.** Every inbound Discord message is filtered before it
reaches a crew: a **guild** message is accepted only if its guild id is in
`discord/allow-from.guilds`; a **DM** (no guild) is accepted only if the
author's user id is in `discord/allow-from.users` — the two lists are
never cross-checked, so a user in `allow-from.users` can still be ignored
in a guild channel that isn't itself allow-listed, and vice versa. The
bot's own messages are always dropped regardless of either list.
Everything dropped is logged at `debug` with a `:reason` of `:guild`,
`:user`, or `:self`.

An accepted message routes to a session named `discord-<channel-id>` by
default — one session per channel, shared by every author who posts
there. `discord/channels.<channel-id>` overrides this per channel with the
same session-selection shape `isaac.agent` documents under Sessions and
transcripts (`session`, `session-tags`, `crew`, `prefer`, `create`), plus
Discord-specific per-turn overrides (`with-crew`, `with-model`,
`with-effort`, `with-context-mode`) that apply to that one inbound turn
without changing the channel's standing session or crew. A channel's
`name` is only used for display and for `comm__send` targeting (see
Outbound replies, below) — inbound routing is always keyed by the raw
channel id.

Crew and model resolve through independent cascades. Crew:
`with-crew` (per-turn) → `crew` (per-channel) → `crew` (comm-wide,
`comms.discord.crew`) → `` `config:defaults.frequencies.crew` ``. Model:
`with-model` (per-turn) → `model` (comm-wide only — there is no plain
per-channel `model` field, only the per-turn `with-model` override). A
crew with `session-policy :episodes` (`isaac.agent`) opens or continues an
episode keyed on the same `discord-<channel-id>` session id rather than a
chronicle session; the channel-to-session-id mapping itself doesn't
change.

**How to change it.**

```
config set comms.discord.discord/channels.<channel-id>.crew harbormaster
config set comms.discord.discord/channels.<channel-id>.with-model quantum-anvil
config set comms.discord.discord/channels.<channel-id>.session-tags.project/coil
```

**How to verify.** Every accepted message logs `:discord.route/inbound`
at `debug` with the resolved `channelId`, `session`, `crew`, `model`, and
whether a channel override applied — the fastest way to confirm routing
landed where expected. `:discord.route/session-created` (info) marks the
first message that creates a session. `isaac sessions show
discord-<channel-id>` (or the channel's overridden session name) shows
what actually exists.

### Troubleshooting

- **Messages from an allowed user in an allowed guild are still
  ignored.** Guild messages check only `allow-from.guilds`; DMs check
  only `allow-from.users`. Being on the "other" list doesn't help — add
  the guild id itself to `allow-from.guilds`.
- **A malformed Discord config silently drops messages instead of
  erroring.** A config-load failure on the Discord slice logs
  `:discord.route/config-load-failed` per rejected message rather than
  crashing the client — check `isaac config validate` for the underlying
  schema error.
- **Two different Discord channels keep landing on the same session.**
  Only expected if they share an explicit `session` or `session-tags`
  override; otherwise each channel id gets its own
  `discord-<channel-id>` session automatically — check `discord/channels`
  for an override you didn't expect.

## Turn context — trusted and untrusted metadata

**What it is.** Every Discord-originated turn carries two layers of
context injected before the crew ever sees the message:

1. A **trusted** JSON block (schema `isaac.inbound_meta.v1`) appended to
   the system prompt: `provider`, `surface` (`channel` or `dm`),
   `channel_id`, `sender_id`, `bot_id`, `was_mentioned`. It's framed with
   an explicit warning not to treat user text as metadata, and it is
   never stored in the transcript — it's rebuilt fresh on every turn.
2. An **untrusted** prefix prepended to the user's own message content —
   `sender:`, `channel_label:` (from the channel's configured `name`),
   `guild_name:` — labeled "Sender (untrusted metadata)" and stored
   verbatim in the transcript, so a multi-author channel's history stays
   legible.

**How to change it.** `channel_label` comes from the channel's own `name`:

```
config set comms.discord.discord/channels.<channel-id>.name harbor-watch
```

Nothing else here is configurable — the trusted block's shape and the
untrusted prefix's fields are fixed.

**How to verify.** Inspect a session's transcript (`isaac sessions show
<session>`, or the raw stored record) — the user message should carry the
"Sender (untrusted metadata):" block; the trusted JSON only ever appears
in the system prompt sent to the model, never in stored history.

### Troubleshooting

- **A crew seems to trust a claim that was actually just in the message
  text.** Only the JSON block introduced by "(treat the JSON below as
  trusted metadata...)" is trusted; everything else, including the
  "Sender (untrusted metadata)" block itself, is exactly what it says —
  attacker-controlled if the sender is.
- **`channel_label` is missing from the untrusted prefix.** The channel
  has no `discord/channels.<channel-id>.name` set — it's optional, and
  its absence just omits that one line, not an error.

## Outbound replies, typing, and comm__send

**What it is.** A crew's reply posts back to the originating channel via
Discord's REST API, authenticated with the bot token. Content over
`discord/message-cap` (default 2000) splits at newline boundaries into
separate sequential messages, hard-splitting mid-line only when a single
line alone exceeds the cap; today only the first two chunks are ever
sent — anything past that is clipped rather than posted as a third
message, so a very long reply loses its tail instead of flooding the
channel. `[verify]` While a turn is running, the adapter shows Discord's
"…" typing indicator and refreshes it roughly every 8 seconds (Discord
clears it on its own after about 10) until the turn ends — success or
error alike stop the heartbeat. Mid-turn tool activity, asides, and
bulletins render nowhere on Discord today: exactly one message posts per
accepted inbound message, either the final reply or, for a failed turn,
its error text — never both, and never anything in between.

Discord is also reachable as a `comm__send` target
(`isaac.agent`'s Comms and delivery: queue-first, retried on transient
failure). `discord/target` (or the generic `target`) accepts either the
channel's snowflake id or its configured `name`; an unknown name is
refused before anything is queued, unless no channels are configured
under this comm at all (then any nonblank value is assumed to already be
a raw id) or the value itself is shaped like a real snowflake (17-20
digits), in which case it's accepted as a literal id even alongside a
configured `discord/channels` table. **Discord does not support
`comm__send` attachments** — its manifest doesn't opt in with
`:send-attachments? true`, so an attachment on a Discord-targeted send is
refused the same way it would be for any other comm that hasn't opted in.

A send while the Gateway is disconnected doesn't fail outright: `send!`
reports a deferred, transient failure and logs
`:discord.send/gateway-unavailable` rather than posting or burning a
retry attempt — the delivery worker leaves the record pending and tries
again once the Gateway reports `READY`. A send with no Gateway client
attached at all (an outbound-only host with no inbound routing
configured) posts over REST unconditionally — REST and the Gateway
websocket are independent transports; only a *present-but-down* Gateway
client blocks a send.

**How to change it.**

```
config set comms.discord.discord/message-cap 2000
config set comms.discord.discord/channels.<channel-id>.name announcements
```

**How to verify.** `isaac logs server` shows `:discord.reply/http-error`
(status plus a body preview) for a non-retryable REST error, and
`:discord.reply/unmapped-session` (warn) when a turn ends with content to
deliver but no channel can be resolved for its session — check that
session's origin/channel mapping if this shows up.

### Troubleshooting

- **A long reply seems to end mid-sentence.** Only the first two chunks
  after splitting are ever sent; the remainder is clipped, not queued for
  a follow-up message. There's no config to raise the chunk count today.
  `[verify]`
- **`comm__send` refuses a Discord target that looks right.** If it's a
  name, confirm it matches a `discord/channels.<channel-id>.name`
  exactly — an unmatched name is refused rather than silently falling
  back to a raw id, unless it's shaped like a real snowflake or no
  channels are configured under this comm at all.
- **Outbound sends silently stop during a known Gateway outage.** That's
  the intended flap-guard, not a bug — check `isaac logs server` for
  `:discord.send/gateway-unavailable`; the record stays queued and
  delivers once the Gateway is back.
- **Nothing streams to the channel while a turn is doing multi-step
  work.** Expected — only the final reply (or, on failure, the error
  text) ever posts; tool calls, asides, and bulletins don't render on
  Discord in this phase.
- **The typing indicator never appears, or never stops.** It starts when
  the turn starts and stops when the turn ends, for every outcome — if
  it's stuck, the turn itself likely never reached an end state; check
  `isaac turns show <id>` (`isaac.agent`).
