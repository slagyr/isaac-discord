Feature: Discord Gateway connection
  The Discord channel adapter opens a persistent WebSocket to the
  Discord Gateway, authenticates via IDENTIFY, and keeps the connection
  alive with scheduled heartbeats. Tests use an in-memory fake Gateway;
  a small @slow integration scenario exercises the real WSS transport.

  Background:
    Given an empty Isaac root at "/test"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.discord/token | test-token |

  Scenario: client sends IDENTIFY after receiving HELLO
    When the Discord client connects
    And Discord sends HELLO:
      | heartbeat_interval | 45000 |
    Then the Discord client sends IDENTIFY:
      | token   | test-token |
      | intents | 37377      |

  Scenario: client sends HEARTBEAT at the interval from HELLO
    When the Discord client connects
    And Discord sends HELLO:
      | heartbeat_interval | 45000 |
    And the test clock advances 45000 milliseconds
    Then the Discord client sends HEARTBEAT

  Scenario: client is connected after receiving READY
    When the Discord client connects
    And Discord sends HELLO:
      | heartbeat_interval | 45000 |
    And Discord sends READY:
      | session_id | fake-session |
    Then the Discord client is connected

  # isaac-ceeq: opcode 7 (Reconnect) used to produce a double auth — the eager
  # auth in do-reconnect! plus an unconditional IDENTIFY on the reconnected
  # socket's HELLO. Discord rejected the second, heartbeats died. The reconnect
  # must send exactly one auth and keep heartbeating.
  Scenario: client reconnects cleanly after opcode 7 without a duplicate auth
    When the Discord client connects
    And Discord sends HELLO:
      | heartbeat_interval | 45000 |
    And Discord sends READY:
      | session_id | fake-session |
    And Discord sends opcode 7
    And the reconnect delay passes
    And Discord sends HELLO:
      | heartbeat_interval | 45000 |
    Then the Discord client sends exactly one RESUME or IDENTIFY on reconnect
    And the Discord client continues sending HEARTBEATs
    And no "Already authenticated" reconnect failure is logged
