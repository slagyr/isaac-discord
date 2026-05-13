Feature: Discord Gateway connection
  The Discord channel adapter opens a persistent WebSocket to the
  Discord Gateway, authenticates via IDENTIFY, and keeps the connection
  alive with scheduled heartbeats. Tests use an in-memory fake Gateway;
  a small @slow integration scenario exercises the real WSS transport.

  Background:
    Given the Discord Gateway is faked in-memory
    And config:
      | comms.discord.token | test-token |

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
