Feature: Discord Gateway reconnect
  The Discord client handles Gateway disconnects: resumable close
  codes trigger RESUME with the preserved session_id and last
  sequence; non-resumable codes re-IDENTIFY with a fresh handshake;
  fatal codes (invalid token, etc.) surface as errors without
  reconnecting. Exponential backoff on repeated failures is covered
  by unit spec, not feature.

  Background:
    Given the Discord Gateway is faked in-memory
    And config:
      | log.output        | memory     |
      | comms.discord.token | test-token |
    And the Discord client is ready as bot "bot-default"

  Scenario: resumable disconnect triggers RESUME with session_id and last sequence
    When Discord closes the connection with code 4000
    Then the Discord client sends RESUME:
      | token      | test-token   |
      | session_id | fake-session |
      | seq        | 1            |

  Scenario: fatal disconnect (invalid token) is logged and does not reconnect
    When Discord closes the connection with code 4004
    Then the log has entries matching:
      | level | event                        |
      | :error | :discord.gateway/fatal-close |
