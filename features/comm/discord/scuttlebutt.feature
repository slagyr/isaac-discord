@wip
Feature: Discord under the scuttlebutt Comm protocol (phase 1, mechanical)
  Discord is a delivery surface, not a theater. Under the new Comm protocol
  (isaac-5nxf) it takes comm/defaults for everything except: on-turn-start
  (typing heartbeat, isaac-qomx), on-reply (the verdict posts as a new
  message to the origin channel), on-turn-end (errors only), and send!.
  Per-destination no-double-render: the origin channel sees the reply
  exactly once, from on-reply; a successful on-turn-end posts nothing.
  Chatter, reckoning, asides, tool events, and bulletins render nowhere
  in phase 1 (defaults). Phase 2 (live working message) is isaac-pq0b.

  Background:
    Given default Grover setup in "/test/discord-scuttlebutt"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.users  | 123        |
      | comms.discord.discord/allow-from.guilds | G789       |
      | sessions.naming-strategy        | sequential |
    And the Discord client is ready as bot "bot-default"

  Scenario: the reply posts once, from on-reply, and a successful turn end posts nothing more
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | hi back |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method                | POST           |
      | headers.Authorization | Bot test-token |
      | body.content          | hi back        |
    And 1 Discord outbound HTTP requests to "https://discord.com/api/v10/channels/C999/messages" were made

  Scenario: a failed turn posts its error from on-turn-end, and nothing from on-reply
    Given the following model responses are queued:
      | model | type  | content       |
      | echo  | error | provider boom |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method       | POST          |
      | body.content | provider boom |
    And 1 Discord outbound HTTP requests to "https://discord.com/api/v10/channels/C999/messages" were made

  Scenario: mid-turn chatter and tool events render nowhere on Discord
    Given the built-in tools are registered
    And the crew "main" allows tools: "fs/grep"
    And the following model responses are queued:
      | model | type        | content                      |
      | echo  | text-stream | ["thinking…","still going…"] |
      | echo  | text        | done                         |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method       | POST |
      | body.content | done |
    And 1 Discord outbound HTTP requests to "https://discord.com/api/v10/channels/C999/messages" were made
