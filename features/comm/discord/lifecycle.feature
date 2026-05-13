Feature: Discord client lifecycle
  Isaac connects to Discord on server startup if config is present,
  starts the client when config is added at runtime, stops it when
  config is removed, and does NOT restart when config merely changes
  (the runtime reads fresh cfg per message so updates take effect
  without bouncing the connection).

  Background:
    Given default Grover setup
    And config:
      | key         | value |
      | server.port | 0     |
    And the Discord Gateway is faked in-memory

  Scenario: Discord client starts on isaac server startup when config is present
    Given config:
      | key                            | value      |
      | comms.discord.token            | test-token |
      | comms.discord.allow-from.users | ["123"]    |
      | comms.discord.crew             | main       |
    And the Isaac server is started
    Then the Discord client is connected

  Scenario: Discord client starts when config is added mid-run
    Given the Isaac server is started
    When the isaac EDN file "config/isaac.edn" exists with:
      | path                            | value      |
      | comms.discord.token             | test-token |
      | comms.discord.allow-from.users  | ["123"]    |
      | comms.discord.crew              | main       |
    Then the log has entries matching:
      | level | event              | path           | impl    |
      | :info | :config/reloaded   | isaac.edn      |         |
      | :info | :lifecycle/started | comms.discord  | discord |
    And the Discord client is connected

  Scenario: Discord client stops when its config is removed mid-run
    Given config:
      | key                            | value      |
      | comms.discord.token            | test-token |
      | comms.discord.allow-from.users | ["123"]    |
      | comms.discord.crew             | main       |
    And the Isaac server is started
    And the Discord client is connected
    When the isaac EDN file "config/isaac.edn" exists with:
      | path           | value  |
      | defaults.crew  | main   |
      | defaults.model | grover |
    Then the log has entries matching:
      | level | event              | path           | impl    |
      | :info | :config/reloaded   | isaac.edn      |         |
      | :info | :lifecycle/stopped | comms.discord  | discord |
    And the Discord client is disconnected

  Scenario: allow-from updates take effect without restart
    Given config:
      | key                            | value      |
      | comms.discord.token            | test-token |
      | comms.discord.allow-from.users | ["123"]    |
      | comms.discord.crew             | main       |
    And the Isaac server is started
    And the Discord client is ready as bot "bot-default"
    When the isaac EDN file "config/isaac.edn" exists with:
      | path                            | value         |
      | comms.discord.token             | test-token    |
      | comms.discord.allow-from.users  | ["123","456"] |
      | comms.discord.crew              | main          |
    Then the log has entries matching:
      | level | event            | path      |
      | :info | :config/reloaded | isaac.edn |
    And Discord sends MESSAGE_CREATE:
      | channel_id | 555001 |
      | author.id  | 456    |
      | content    | hi     |
    Then the Discord client accepted a message with:
      | content   | hi  |
      | author.id | 456 |
    And the log has no entries matching:
      | event                   |
      | :discord.client/started |
      | :discord.client/stopped |
