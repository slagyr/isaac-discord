Feature: Discord typing indicator
  While the crew is generating a response to a Discord-originated
  message, the adapter POSTs to /channels/{channel_id}/typing so the
  "..." typing bubble appears in Discord. Discord auto-clears the
  bubble after ~10 seconds; for turns longer than that, the adapter
  refreshes it every ~8 seconds until the turn ends — every outcome,
  success or error, stops the heartbeat (isaac-qomx).

  Background:
    Given default Grover setup in "/test/discord-typing"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.users  | 123        |
      | comms.discord.discord/allow-from.guilds | G789       |
      | sessions.naming-strategy        | sequential |
    And the Discord client is ready as bot "bot-default"

  Scenario: turn start posts a typing indicator to the Discord channel
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | hi back |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C999/typing" matches:
      | method                | POST           |
      | headers.Authorization | Bot test-token |

  @wip
  Scenario: typing refreshes while the turn is still running
    Given the following model responses are queued:
      | model | type | content | wait |
      | echo  | text | hi back | true |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    And the test clock advances 17000 milliseconds
    Then 3 Discord outbound HTTP requests to "https://discord.com/api/v10/channels/C999/typing" were made

  @wip
  Scenario: the heartbeat stops when the turn ends
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | hi back |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    And the test clock advances 30000 milliseconds
    Then 1 Discord outbound HTTP requests to "https://discord.com/api/v10/channels/C999/typing" were made

  @wip
  Scenario: an error turn also stops the heartbeat
    Given the following model responses are queued:
      | model | type  | content       |
      | echo  | error | provider boom |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    And the test clock advances 30000 milliseconds
    Then 1 Discord outbound HTTP requests to "https://discord.com/api/v10/channels/C999/typing" were made
