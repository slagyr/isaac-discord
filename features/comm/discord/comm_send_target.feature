Feature: Discord comm_send target resolves by channel name
  Outbound Discord sends accept :discord/target as either a channel
  snowflake ID or the friendly :name declared in :discord/channels.

  Background:
    Given default Grover setup in "/test/discord-send-target"
    And the Discord Gateway is faked in-memory
    And Discord is configured with:
      | key                          | value         |
      | discord/token                | test-token    |
      | discord/channels.C999.name   | announcements |
      | crew                         | main          |
    And Discord outbound comm is registered

  Scenario: send! resolves a configured channel name to its snowflake id
    When Discord comm send! is invoked with:
      | path           | value         |
      | discord/target | announcements |
      | content        | Red alert!    |
    Then a Discord outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method                | POST           |
      | headers.Authorization | Bot test-token |
      | body.content          | Red alert!     |

  Scenario: send! still accepts a numeric channel id unchanged
    When Discord comm send! is invoked with:
      | path           | value      |
      | discord/target | C999       |
      | content        | All clear. |
    Then a Discord outbound HTTP request to "https://discord.com/api/v10/channels/C999/messages" matches:
      | method                | POST           |
      | headers.Authorization | Bot test-token |
      | body.content          | All clear.     |