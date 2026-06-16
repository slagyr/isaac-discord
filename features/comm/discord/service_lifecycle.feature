@wip
Feature: Discord service vs comm slot lifecycle
  The comm slot registers config with the discord Service on load; only
  server boot starts the Service and opens the gateway client.

  Background:
    Given default Grover setup
    And the discord module is registered
    And config:
      | key         | value |
      | server.port | 0     |
    And the Discord Gateway is faked in-memory

  Scenario: discord config loads without starting the client
    Given config:
      | key                            | value      |
      | comms.discord.discord/token    | test-token |
      | comms.discord.discord/allow-from.users | ["123"] |
      | comms.discord.crew             | main       |
    When the config is reloaded
    Then the Discord client is disconnected
    And the log has no entries matching:
      | event                   |
      | :discord.client/started |
      | :service/started        |

  Scenario: server start opens the discord client
    Given config:
      | key                            | value      |
      | comms.discord.discord/token    | test-token |
      | comms.discord.discord/allow-from.users | ["123"] |
      | comms.discord.crew             | main       |
    When the config is reloaded
    When the discord Isaac server boots
    Then the log has entries matching:
      | level | event            | service |
      | :info | :service/started | discord |
    And the Discord client is connected
    When the Isaac server is stopped
    Then the Discord client is disconnected