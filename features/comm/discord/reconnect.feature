Feature: Discord Gateway reconnect
  The Discord client handles Gateway disconnects: resumable close
  codes trigger RESUME with the preserved session_id and last
  sequence; non-resumable codes re-IDENTIFY with a fresh handshake;
  fatal codes (invalid token, etc.) surface as errors without
  reconnecting. Exponential backoff on repeated failures is covered
  by unit spec, not feature.

  Background:
    Given default Grover setup in "/test/discord-reconnect"
    And the Discord Gateway is faked in-memory
    And config:
      | key                         | value      |
      | log.output                  | memory     |
      | comms.discord.discord/token | test-token |
    And the Discord client is ready as bot "bot-default"

  Scenario: resumable disconnect triggers RESUME with session_id and last sequence
    When Discord closes the connection with code 4000
    And the test clock advances 1000 milliseconds
    Then the Discord client sends RESUME:
      | token      | test-token   |
      | session_id | fake-session |
      | seq        | 1            |

  Scenario: fatal disconnect (invalid token) is logged and does not reconnect
    When Discord closes the connection with code 4004
    Then the log has entries matching:
      | level | event                        |
      | :error | :discord.gateway/fatal-close |

  Scenario: abnormal disconnect (1006) triggers IDENTIFY reconnect
    When Discord closes the connection with code 1006
    And the test clock advances 1000 milliseconds
    Then the Discord client sends IDENTIFY:
      | token   | test-token |
      | intents | 37377      |
    And the Discord client is connected
