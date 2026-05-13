Feature: Discord long-message splitting
  Discord caps individual messages at 2000 characters. Crew responses
  above that threshold are split into multiple sequential POSTs, each
  under the cap, preserving ordering. Splits prefer newline boundaries
  when available; a single line longer than the cap is hard-split.

  The cap is configured via comms.discord.message-cap (default 2000),
  which scenarios override to small values so test fixtures can stay
  short and readable.

  Background:
    Given default Grover setup in "/test/discord-splitting"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.token             | test-token |
      | comms.discord.allow-from.users  | 123        |
      | comms.discord.allow-from.guilds | G789       |
      | sessions.naming-strategy        | sequential |
    And the Discord client is ready as bot "bot-default"

  Scenario: a response longer than the message cap is split at newline boundaries
    Given config:
      | comms.discord.message-cap | 13 |
    And the following model responses are queued:
      | model | type | content                          |
      | echo  | text | alpha bravo\ncharlie delta\necho |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | #index       | 0           |
      | body.content | alpha bravo |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | #index       | 1             |
      | body.content | charlie delta |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | #index       | 2    |
      | body.content | echo |

  Scenario: a single line longer than the cap is hard-split at the cap boundary
    Given config:
      | comms.discord.message-cap | 5 |
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | abcdefghij |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999 |
      | guild_id   | G789 |
      | author.id  | 123  |
      | content    | hi   |
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | #index       | 0     |
      | body.content | abcde |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | #index       | 1     |
      | body.content | fghij |
