Feature: Discord client lifecycle
  Isaac connects to Discord on server startup if config is present,
  starts the client when config is added at runtime, stops it when
  config is removed, and does NOT restart when config merely changes
  (the runtime reads fresh cfg per message so updates take effect
  without bouncing the connection).

  Background:
    Given default Grover setup
    And config:
      | key               | value  |
      | log.output        | memory |
      | server.port       | 0      |
    And the Discord Gateway is faked in-memory

  Scenario: Discord client starts on isaac server startup when config is present
    Given config:
      | key                            | value      |
      | comms.discord.discord/token            | test-token |
      | comms.discord.discord/allow-from.users | ["123"]    |
      | comms.discord.crew             | main       |
    And the discord Isaac server is started
    Then the Discord client is connected

  Scenario: adding a Discord token mid-run starts the client
    Given the discord Isaac server is started
    Then the Discord client is disconnected
    When the isaac EDN file "config/isaac.edn" exists with:
      | path                                   | value        |
      | comms.discord.discord/token            | test-token   |
      | comms.discord.discord/allow-from.users | ["123"]      |
      | comms.discord.crew                     | main         |
    And the isaac config is reloaded
    Then the log has entries matching:
      | level | event                   | path      |
      | :info | :discord.client/started |           |
      | :info | :config/reloaded        | isaac.edn |
    And the Discord client is connected

  Scenario: removing a Discord token mid-run stops the client
    Given config:
      | key                                    | value      |
      | comms.discord.discord/token            | test-token |
      | comms.discord.discord/allow-from.users | ["123"]    |
      | comms.discord.crew                     | main       |
    And the discord Isaac server is started
    And the Discord client is connected
    When the isaac EDN file "config/isaac.edn" exists with:
      | path           | value    |
      | comms.discord  | #delete  |
      | defaults.crew  | main     |
      | defaults.model | grover   |
    And the isaac config is reloaded
    Then the log has entries matching:
      | level | event                   | path      |
      | :info | :discord.client/stopped |           |
      | :info | :config/reloaded        | isaac.edn |
    And the Discord client is disconnected

  Scenario: reloading unchanged Discord token does not reconnect the client
    Given config:
      | key                                    | value      |
      | comms.discord.discord/token            | test-token |
      | comms.discord.discord/allow-from.users | ["123"]    |
      | comms.discord.crew                     | main       |
    And the discord Isaac server is started
    And the Discord client is connected
    When the isaac EDN file "config/isaac.edn" exists with:
      | path                                   | value         |
      | comms.discord.discord/token            | test-token    |
      | comms.discord.discord/allow-from.users | ["123","456"] |
      | comms.discord.crew                     | main          |
    Then the log has entries matching:
      | level | event            | path      |
      | :info | :config/reloaded | isaac.edn |
    And the log has no entries matching:
      | event                   |
      | :discord.client/started |
      | :discord.client/stopped |
    And the Discord client is connected