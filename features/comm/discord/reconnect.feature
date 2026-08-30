Feature: Discord Gateway reconnect
  The Discord client handles Gateway disconnects: resumable close
  codes trigger RESUME with the preserved session_id and last
  sequence; non-resumable codes re-IDENTIFY with a fresh handshake;
  fatal codes (invalid token, etc.) surface as errors without
  reconnecting. Reconnect is single-flight: opcode 7 plus a racing
  socket close produce one auth, and opcode 7 resumes the session
  rather than IDENTIFYing. Repeated failures park after a bounded
  retry budget instead of IDENTIFY-storming. Backoff timing stays
  unit spec.

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

  # Zanebot 2026-08-29: 2477 IDENTIFY in one day (peak 72/min, 989
  # same-second pairs). Opcode 7 schedules RESUME; the socket close
  # that follows (often 1000) scheduled a second reconnect as IDENTIFY.
  # Two uuid reconnect tasks then IDENTIFY'd in the same millisecond.
  @wip
  Scenario: opcode 7 plus a racing close sends one RESUME
    When Discord sends opcode 7
    And Discord closes the connection with code 1000
    And the reconnect delay passes
    Then the Discord client sends RESUME:
      | token      | test-token   |
      | session_id | fake-session |
      | seq        | 1            |
    And the Discord client sends exactly one RESUME or IDENTIFY on reconnect
    And the log has entries matching:
      | level | event                                |
      | :warn | :discord.gateway/reconnect-requested |

  @wip
  Scenario: opcode 7 reconnects with RESUME not IDENTIFY
    When Discord sends opcode 7
    And the reconnect delay passes
    Then the Discord client sends RESUME:
      | token      | test-token   |
      | session_id | fake-session |
      | seq        | 1            |
    And the Discord client sends exactly one RESUME or IDENTIFY on reconnect
    And the log has entries matching:
      | level | event                                |
      | :warn | :discord.gateway/reconnect-requested |

  @wip
  Scenario: reconnect failures park instead of IDENTIFY-storming
    Given the Discord Gateway fails subsequent connections
    When Discord closes the connection with code 1006
    And the test clock advances 180000 milliseconds
    Then the log has entries matching:
      | level  | event                                |
      | :error | :discord.gateway/reconnect-exhausted |
    And the Discord client sends no further IDENTIFY or RESUME

  @wip
  Scenario: stopping the client cancels a pending reconnect
    When Discord closes the connection with code 1006
    And the Discord client is stopped
    And the reconnect delay passes
    Then the Discord client is disconnected
    And the Discord client sends no further IDENTIFY or RESUME
    And the log has no entries matching:
      | event                              |
      | :discord.gateway/reconnect-attempt |
