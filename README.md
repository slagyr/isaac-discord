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
