Feature: A delivery into a Discord channel lands in that channel's session (isaac-rjeg)
  Follow-up to isaac-mve9. An inbound MESSAGE_CREATE records the channel
  on its session's :channels ("discord:<channel-id>"), and send! reports
  :channel, the channel id it posted to (a configured name resolves to
  its id). The agent delivery worker then appends a delivery posted by
  another session (cron to, attention, comm__send) to the channel's
  session as an assistant message:
    [sent here by crew <crew> from session <session>] <content>
  The channel's own replies are not appended twice.

  Background:
    Given default Grover setup in "/test/discord-continuity"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.users  | ["123"]    |
      | comms.discord.discord/allow-from.guilds | ["G789"]   |
      | sessions.naming-strategy                | sequential |
    And the Discord client is ready as bot "bot-default"
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | got it  |
    And Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |

  @wip
  Scenario: a delivery from another session into a talked-in channel lands there as a marked note
    Given the isaac EDN file "comm/delivery/pending/DC1.edn" exists with:
      | path     | value          |
      | id       | DC1            |
      | comm     | :discord       |
      | target   | C999           |
      | content  | Guard fired.   |
      | crew     | herald         |
      | session  | cron-heartbeat |
      | attempts | 0              |
    When the delivery worker ticks
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method       | POST         |
      | body.content | Guard fired. |
    And session "discord-C999" has transcript matching:
      | type    | message.role | message.content                                                         |
      | message | user         | hello                                                                   |
      | message | assistant    | got it                                                                  |
      | message | assistant    | #"\[sent here by crew herald from session cron-heartbeat\] Guard fired\." |

  @wip
  Scenario: a delivery into a channel nobody has talked in appends nothing
    Given the isaac EDN file "comm/delivery/pending/DC2.edn" exists with:
      | path     | value          |
      | id       | DC2            |
      | comm     | :discord       |
      | target   | C777           |
      | content  | Broadcast.     |
      | crew     | herald         |
      | session  | cron-heartbeat |
      | attempts | 0              |
    When the delivery worker ticks
    Then an outbound HTTP request to "https://discord.com/api/v10/channels/C777/messages" matches:
      | method       | POST       |
      | body.content | Broadcast. |
    And session "discord-C999" has 3 transcript entries
