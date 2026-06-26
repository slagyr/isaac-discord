Feature: Discord reply via REST API
  After a crew turn produces text for a Discord-originated message,
  the adapter posts the response back to the originating channel via
  POST /channels/{channel_id}/messages on Discord's REST API. Auth
  via the bot token in the Authorization header. Errors surface as
  logged :discord.reply/http-error events.

  Background:
    Given default Grover setup in "/test/discord-reply"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.users  | 123        |
      | comms.discord.discord/allow-from.guilds | G789       |
      | sessions.naming-strategy        | sequential |
    And the Discord client is ready as bot "bot-default"

  Scenario: crew text response is posted back to the originating Discord channel
    And the following model responses are queued:
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

  Scenario: a non-retryable Discord REST error is logged
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | hi back |
    And the URL "https://discord.com/api/v10/channels/C999/messages" responds with:
      | status | 403 |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    Then the log has entries matching:
      | event                     | channelId | status |
      | :discord.reply/http-error | C999      | 403    |

  Scenario: a reply for an unmapped session logs a warning instead of silently dropping
    And config:
      | comms.discord.discord/token             | test-token   |
      | comms.discord.discord/allow-from.users  | cordelia     |
      | comms.discord.discord/allow-from.guilds | harbor-guild |
    And the following sessions exist:
      | name        | crew |
      | signal-loft | main |
    And the current session is "signal-loft"
    And the Discord client is ready as bot "harbormaster-bot"
    When the current session receives a completed turn with text "Lantern trimmed and ready."
    Then the log has entries matching:
      | level | event                           | session     |
      | :warn | :discord.reply/unmapped-session | signal-loft |
