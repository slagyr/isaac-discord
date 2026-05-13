Feature: Discord per-turn context
  Every Discord turn receives two context layers injected before the
  crew sees the message.

  1. Trusted system block — JSON with schema "isaac.inbound_meta.v1"
     appended to the system prompt. Carries verifiable identifiers:
     provider, surface (channel|dm), channel_id, sender_id, bot_id,
     was_mentioned. Wrapped in a framing note to guard against prompt
     injection. Not stored in the transcript.

  2. Untrusted user-message prefix — prepended to the user message
   content with an explicit "Sender (untrusted metadata):" label,
      carrying display-name fields from the payload/config (sender
      username, channel_label, guild_name). Stored verbatim in the
      transcript for coherent multi-author history.

  Background:
    Given default Grover setup in "/test/discord-context"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.token             | test-token |
      | comms.discord.allow-from.users  | 123        |
      | comms.discord.allow-from.guilds | G789       |
      | sessions.naming-strategy        | sequential |
    And the Discord client is ready as bot "bot-default"

  Scenario: trusted system block is appended to the system prompt
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | ok      |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then the system prompt contains "isaac.inbound_meta.v1"
    And the system prompt contains "channel_id"
    And the system prompt contains "sender_id"
    And the system prompt contains "trusted metadata"

  Scenario: untrusted user-message prefix is prepended to the user message
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | ok      |
    When Discord sends MESSAGE_CREATE:
      | channel_id      | C999  |
      | guild_id        | G789  |
      | author.id       | 123   |
      | author.username | alice |
      | content         | hello |
    Then session "discord-C999" has transcript matching:
      | type    | message.role | message.content                     |
      | message | user         | #"(?s).*Sender.*alice.*\nhello.*"   |
      | message | assistant    | ok                                  |

  Scenario: configured channel label and guild name are included in the untrusted prefix
    Given config:
      | comms.discord.channels.C999.name | cooking |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | ok      |
    When Discord sends MESSAGE_CREATE:
      | channel_id      | C999           |
      | guild_id        | G789           |
      | guild_name      | Planet Express |
      | author.id       | 123            |
      | author.username | alice          |
      | content         | hello          |
    Then session "discord-C999" has transcript matching:
      | type    | message.role | message.content                                                      |
      | message | user         | #"(?s).*sender: alice.*channel_label: cooking.*guild_name: Planet Express.*\nhello.*" |
      | message | assistant    | ok                                                                   |

  Scenario: channel label is omitted when the channel has no configured name
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | ok      |
    When Discord sends MESSAGE_CREATE:
      | channel_id      | C999           |
      | guild_id        | G789           |
      | guild_name      | Planet Express |
      | author.id       | 123            |
      | author.username | alice          |
      | content         | hello          |
    Then session "discord-C999" has transcript matching:
      | type    | message.role | message.content                                                                  |
      | message | user         | #"(?s)^Sender \(untrusted metadata\):\nsender: alice\nguild_name: Planet Express\nhello$" |
      | message | assistant    | ok                                                                               |

  Scenario: was_mentioned is true when bot id is in the mentions array
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | ok      |
    When Discord sends MESSAGE_CREATE:
      | channel_id    | C999        |
      | guild_id      | G789        |
      | author.id     | 123         |
      | content       | hey bot     |
      | mentions.0.id | bot-default |
    Then the system prompt contains "was_mentioned"
    And the system prompt contains ":true"

  Scenario: was_mentioned is false when bot id is not in the mentions array
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | ok      |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then the system prompt contains "was_mentioned"
    And the system prompt contains ":false"
