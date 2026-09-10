Feature: Discord channel is the session id for episode crews
  A Discord channel is the session id (not a process thread),
  the same role as an ACP session id. For a crew with :session-policy
  :episodes the agent opens a container on first message under that
  session id; a warm second message appends; the session id never
  changes. Typing and replies use origin channel-id. Chronicle crews
  still map the channel to session discord-<channel-id>. Existing
  routing.feature stays that contract.

  Background:
    Given default Grover setup in "/test/discord-episodes"
    And the Discord Gateway is faked in-memory

  Scenario: first message on an episodes crew opens an episode and replies to the channel
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | episodes         |
    And config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.guilds | G789       |
      | comms.discord.crew                      | cordelia   |
    And the Discord client is ready as bot "bot-default"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999           |
      | guild_id   | G789           |
      | author.id  | 123            |
      | content    | Light the lamp |
    Then an episode exists for crew "cordelia" matching:
      | key        | value                          |
      | id         | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" |
      | status     | open                           |
      | session-id | discord-C999                   |
    And the following sessions match:
      | id           | crew     |
      | discord-c999 | cordelia |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method                | POST               |
      | headers.Authorization | Bot test-token     |
      | body.content          | Charted, keep west |

  Scenario: a warm second message on the same channel appends and still replies to the channel
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | episodes         |
    And config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.guilds | G789       |
      | comms.discord.crew                      | cordelia   |
    And the Discord client is ready as bot "bot-default"
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | Wick trimmed       | echo  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999           |
      | guild_id   | G789           |
      | author.id  | 123            |
      | content    | Light the lamp |
    Given the current time is "2026-03-01T10:10:00"
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999          |
      | guild_id   | G789          |
      | author.id  | 123           |
      | content    | Trim the wick |
    Then crew "cordelia" has 1 episode
    And an episode exists for crew "cordelia" matching:
      | key        | value        |
      | status     | open         |
      | session-id | discord-C999 |
    And the following sessions match:
      | id           | crew     |
      | discord-c999 | cordelia |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | #index       | 0                |
      | method       | POST               |
      | body.content | Charted, keep west |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | #index       | 1             |
      | method       | POST          |
      | body.content | Wick trimmed  |

  Scenario: a chronicle crew still maps the channel to session discord-<channel-id>
    Given config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.guilds | G789       |
      | comms.discord.crew                      | main       |
    And the Discord client is ready as bot "bot-default"
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999    |
      | guild_id   | G789    |
      | author.id  | 123     |
      | content    | Status? |
    Then the following sessions match:
      | id           |
      | discord-c999 |
    And crew "main" has 0 episodes
    And an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method       | POST |
      | body.content | Aye  |
