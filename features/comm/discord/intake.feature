Feature: Discord message intake
  The Discord channel adapter accepts MESSAGE_CREATE events only from
  users and guilds listed in allow-from. Other senders, and the bot's
  own messages, are silently ignored.

  Background:
    Given the Discord Gateway is faked in-memory
    And config:
      | comms.discord.token             | test-token |
      | comms.discord.allow-from.users  | 123456     |
      | comms.discord.allow-from.guilds | 789012     |
    And the Discord client is ready as bot "bot-default"

  Scenario: accept MESSAGE_CREATE from an allowed user and guild
    When Discord sends MESSAGE_CREATE:
      | channel_id | 999001 |
      | guild_id   | 789012 |
      | author.id  | 123456 |
      | content    | hello  |
    Then the Discord client accepted a message with:
      | content   | hello  |
      | author.id | 123456 |

  Scenario: ignore MESSAGE_CREATE from a guild not on the allow list
    When Discord sends MESSAGE_CREATE:
      | channel_id | 999001 |
      | guild_id   | 888888 |
      | author.id  | 123456 |
      | content    | hi     |
    Then the Discord client accepted no messages

  Scenario: ignore MESSAGE_CREATE from the bot itself even if on allow list
    Given config:
      | comms.discord.token             | test-token |
      | comms.discord.allow-from.users  | 555        |
      | comms.discord.allow-from.guilds | 789012     |
    And the Discord client is ready as bot "555"
    When Discord sends MESSAGE_CREATE:
      | channel_id | 999001 |
      | guild_id   | 789012 |
      | author.id  | 555    |
      | content    | echo   |
    Then the Discord client accepted no messages
