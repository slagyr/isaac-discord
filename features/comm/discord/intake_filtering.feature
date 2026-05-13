Feature: Discord intake filtering
  Filter incoming MESSAGE_CREATE events by sender. Drops are logged
  at debug level so misconfigurations are diagnosable.

  Background:
    Given the Discord Gateway is faked in-memory
    And config:
      | log.output          | memory     |
      | comms.discord.token | test-token |

  Scenario: guild post accepted when its guild is allowlisted
    Given config:
      | comms.discord.allow-from.guilds | 789012 |
    And the Discord client is ready as bot "bot-default"
    When Discord sends MESSAGE_CREATE:
      | channel_id | 999001 |
      | guild_id   | 789012 |
      | author.id  | 274692 |
      | content    | hello  |
    Then the Discord client accepted a message with:
      | content   | hello  |
      | author.id | 274692 |

  Scenario: guild post dropped when its guild is not allowlisted
    Given config:
      | comms.discord.allow-from.guilds | 789012 |
    And the Discord client is ready as bot "bot-default"
    When Discord sends MESSAGE_CREATE:
      | channel_id | 999001 |
      | guild_id   | 888888 |
      | author.id  | 274692 |
      | content    | hi     |
    Then the Discord client accepted no messages
    And the log has entries matching:
      | level  | event                             | reason |
      | :debug | :discord.gateway/message-rejected | :guild |

  Scenario: DM accepted when its author is allowlisted
    Given config:
      | comms.discord.allow-from.users | 274692 |
    And the Discord client is ready as bot "bot-default"
    When Discord sends MESSAGE_CREATE:
      | channel_id | 555001 |
      | author.id  | 274692 |
      | content    | dm hi  |
    Then the Discord client accepted a message with:
      | content   | dm hi  |
      | author.id | 274692 |

  Scenario: DM dropped when its author is not allowlisted
    Given config:
      | comms.discord.allow-from.users | 274692 |
    And the Discord client is ready as bot "bot-default"
    When Discord sends MESSAGE_CREATE:
      | channel_id | 555001 |
      | author.id  | 999999 |
      | content    | spam   |
    Then the Discord client accepted no messages
    And the log has entries matching:
      | level  | event                             | reason |
      | :debug | :discord.gateway/message-rejected | :user  |

  Scenario: bot's own message is dropped even when allowlists match
    Given config:
      | comms.discord.allow-from.guilds | 789012 |
      | comms.discord.allow-from.users  | 555    |
    And the Discord client is ready as bot "555"
    When Discord sends MESSAGE_CREATE:
      | channel_id | 999001 |
      | guild_id   | 789012 |
      | author.id  | 555    |
      | content    | echo   |
    Then the Discord client accepted no messages
    And the log has entries matching:
      | level  | event                             | reason |
      | :debug | :discord.gateway/message-rejected | :self  |
