# isaac-discord

[![CI Tests](https://github.com/slagyr/isaac-discord/actions/workflows/ci.yml/badge.svg)](https://github.com/slagyr/isaac-discord/actions/workflows/ci.yml)

A [Discord](https://discord.com) comm module for [Isaac](https://github.com/slagyr/isaac),
the AI assistant framework. It bridges Discord gateway events to Isaac sessions,
allowing Isaac-powered agents to receive messages from and reply to Discord channels.

## Features

- Discord gateway WebSocket connection with automatic reconnection
- Routes incoming messages to Isaac sessions by channel/user
- Streams agent replies back to Discord with typing indicators
- Message splitting for long responses
- Allow-list filtering by user or channel
- Configurable per-crew routing

## Installation

Declare the module in your Isaac config's `:modules` map so Isaac's loader
discovers it, then configure a `:discord` comm slot:

```clojure
{:modules {:isaac.comm.discord {:git/url "https://github.com/slagyr/isaac-discord.git"
                                :git/sha "<sha>"}}

 :comms {:discord {:discord/token       "your-bot-token"
                   :crew                "main"
                   :discord/message-cap 2000
                   :discord/allow-from  {:users  ["user-id-1"]
                                         :guilds ["guild-id-1"]}
                   :discord/channels    {"1491164414794272848" {:crew    "support"
                                                                 :model   "grover"
                                                                 :session "discord-support"
                                                                 :name    "#support"}}}}}
```

Channel IDs are strings in config. Quote snowflake keys in EDN (`"1491164414794272848"`) — bare numeric keys work too but string keys are clearer:

```clojure
:discord/channels {"1491164414794272848" {:session "discord-support"}}
```

Schema fields owned by the discord module are namespaced under `:discord/`
to avoid collision with other comm modules in the same comm slot map. `:crew`
is the shared slot-level field and stays unqualified.

| Key                    | Type   | Description |
|------------------------|--------|-------------|
| `:discord/token`       | string | Discord bot token |
| `:crew`                | string | Isaac crew to route messages to |
| `:discord/message-cap` | int    | Max characters per reply before splitting (Discord's hard cap is 2000) |
| `:discord/allow-from`  | map    | Inbound allow-list: `:users` (snowflake strings) and/or `:guilds` (server IDs) |
| `:discord/channels`    | map    | Optional per-channel overrides keyed by channel ID — each value can set `:crew`, `:model`, `:session`, `:name` |

Outbound sends (`comm_send`, delivery worker, cron) accept `:discord/target` as either the
channel snowflake ID or the friendly `:name` declared on that channel entry:

```clojure
:discord/channels {"123456789012345678" {:name "announcements" :crew "main"}}
;; comm_send target "announcements" or "123456789012345678" both resolve to the same channel
```

## Recovery behavior

The Discord gateway client is designed to recover internally from transient
transport failures without requiring a full `isaac service restart`.

Observed production failure mode on zanebot:

- Discord sends opcode 7 (`Reconnect`)
- the socket churns through multiple quick disconnects
- the client reaches `HELLO` + `READY` again
- a later reader failure surfaces as `java.io.IOException: "Output closed"`
  and logs `:discord.gateway/reader-loop-failed`

Recovery expectations:

- queue/transport error maps are treated as disconnect triggers, not just log-only noise
- a reader failure or transport error schedules exactly one reconnect path at a time
- reconnect attempts resume or re-identify as appropriate, then return to `READY`
- heartbeat/liveness scheduling is recreated on the recovered connection
- `DiscordService` runs a watchdog (also started when a Discord comm registers
  on a running server, so hot-reload token adds are covered): every minute it
  logs `:discord.watchdog/check` while disconnected and, after 5 minutes stale,
  logs `:discord.watchdog/stale-connection` at WARN and forces a
  reconnect/re-registration
- if `on-close!` fails to schedule a reconnect, `:discord.gateway/stale-not-recovering`
  nudges recovery on the next heartbeat tick or watchdog pass

In short: `"Output closed"`, `reader-loop-failed`, opcode-7 reconnect storms,
and similar transient websocket failures should self-heal within the normal
reconnect backoff window.

## Development

```bash
bb spec       # Run Clojure specs
bb features   # Run Gherkin feature scenarios
bb ci         # Run both
```

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Use the `:dev-local` alias
for sibling checkouts (`../isaac-foundation`, `../isaac-agent`, `../isaac-server`
for features). Bump `:git/sha` in `deps.edn` when CI needs newer split-module
code.

## License

Copyright © 2026 Micah Martin. See [LICENSE](LICENSE).
