# isaac-discord

[![CI](https://github.com/slagyr/isaac-discord/actions/workflows/ci.yml/badge.svg)](https://github.com/slagyr/isaac-discord/actions/workflows/ci.yml)

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

 :comms {:discord {:token       "your-bot-token"
                   :crew        "main"
                   :message-cap 2000
                   :allow-from  {:users  ["user-id-1"]
                                 :guilds ["guild-id-1"]}
                   :channels    {"channel-id-1" {:crew    "support"
                                                 :model   "grover"
                                                 :session "discord-support"
                                                 :name    "#support"}}}}}
```

| Key           | Type   | Description |
|---------------|--------|-------------|
| `:token`      | string | Discord bot token |
| `:crew`       | string | Isaac crew to route messages to |
| `:message-cap`| int    | Max characters per reply before splitting (Discord's hard cap is 2000) |
| `:allow-from` | map    | Inbound allow-list: `:users` (snowflake strings) and/or `:guilds` (server IDs) |
| `:channels`   | map    | Optional per-channel overrides keyed by channel ID — each value can set `:crew`, `:model`, `:session`, `:name` |

## Development

```bash
bb spec       # Run Clojure specs
bb features   # Run Gherkin feature scenarios
bb ci         # Run both
```

Depends on [Isaac core](https://github.com/slagyr/isaac). `bb.edn` auto-detects
a sibling `../isaac` checkout when present, so local cross-repo edits don't
need a sha bump; set `ISAAC_GIT=1` to force the pinned git sha even with the
sibling present. Bump `:git/sha` in `deps.edn` and `bb.edn` when CI / fresh
clones need newer Isaac code.

## License

Copyright © 2026 Micah Martin. See [LICENSE](LICENSE).
