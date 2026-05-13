# isaac-discord

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

Add to your Isaac `deps.edn` as a git dependency:

```clojure
io.github.slagyr/isaac-discord {:git/url "git@github.com:slagyr/isaac-discord.git"
                                 :git/sha "<sha>"}
```

Isaac will discover the module automatically via `isaac-manifest.edn`.

## Configuration

In your Isaac config (`config/isaac.edn`):

```clojure
{:comms {:discord {:token      "your-bot-token"
                   :crew       "main"
                   :message-cap 2000
                   :allow-from {:users    ["user-id-1"]
                                :channels ["channel-id-1"]}}}}
```

| Key           | Type   | Description |
|---------------|--------|-------------|
| `:token`      | string | Discord bot token |
| `:crew`       | string | Isaac crew to route messages to |
| `:message-cap`| int    | Max characters per Discord message (default 2000) |
| `:allow-from` | map    | Optional allow-list of `:users` and/or `:channels` |

## Development

```bash
bb spec       # Run Clojure specs
bb features   # Run Gherkin feature scenarios
bb ci         # Run both
```

Depends on [Isaac core](https://github.com/slagyr/isaac) via git dep.
Update `:git/sha` in `deps.edn` and `bb.edn` when pulling in Isaac changes.

## License

Copyright © 2026 Micah Martin. See [LICENSE](LICENSE).
