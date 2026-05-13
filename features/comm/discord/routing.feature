Feature: Discord session routing
  Each Discord channel maps to a single session. The default session
  name is "discord-<channel-id>". Crew and model resolve first from
  per-channel Discord config, then Discord-wide config, then the normal
  crew/default config path. Multiple authors in one channel share one
  session — routing is a pure function of config + payload with no
  routing-table state file.

  Background:
    Given default Grover setup in "/test/discord-routing"
    And the Discord Gateway is faked in-memory
    And config:
      | comms.discord.token             | test-token |
      | comms.discord.allow-from.users  | 123        |
      | comms.discord.allow-from.guilds | G789       |
      | sessions.naming-strategy        | sequential |
    And the Discord client is ready as bot "bot-default"

  Scenario: first message in a channel creates a session named discord-<channel-id>
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | got it  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then session "discord-C999" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | got it          |

  Scenario: second author in the same channel routes to the same session
    Given the following sessions exist:
      | name         |
      | discord-C999 |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | got it  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 456   |
      | content    | hello |
    Then session "discord-C999" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | got it          |

  Scenario: per-channel session override routes to the configured session
    Given the following sessions exist:
      | name    |
      | kitchen |
    And config:
      | comms.discord.channels.C999.session | kitchen |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | got it  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then session "kitchen" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | got it          |

  Scenario: Discord-wide crew and model apply when the channel has no override
    Given config:
      | comms.discord.crew   | marvin                    |
      | comms.discord.model  | bender                    |
      | crew.marvin.model    | grover                    |
      | crew.marvin.soul     | Bite my shiny metal prompts. |
      | models.bender.model  | echo-bender               |
      | models.bender.provider | grover                  |
      | models.bender.context-window | 32768            |
    And the following model responses are queued:
      | model       | type | content |
      | echo-bender | text | got it  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then the system prompt contains "Bite my shiny metal prompts."
    And session "discord-C999" has transcript matching:
      | type    | message.role | message.model | message.content |
      | message | user         |               | hello           |
      | message | assistant    | echo-bender   | got it          |

  Scenario: per-channel model override wins over the Discord-wide model
    Given config:
      | comms.discord.crew                 | marvin                    |
      | comms.discord.model                | bender                    |
      | comms.discord.channels.C999.model  | chef-bender               |
      | crew.marvin.model                  | grover                    |
      | crew.marvin.soul                   | Bite my shiny metal prompts. |
      | models.bender.model                | echo-bender               |
      | models.bender.provider             | grover                    |
      | models.bender.context-window       | 32768                     |
      | models.chef-bender.model           | echo-chef                 |
      | models.chef-bender.provider        | grover                    |
      | models.chef-bender.context-window  | 32768                     |
    And the following model responses are queued:
      | model     | type | content |
      | echo-chef | text | got it  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | C999  |
      | guild_id   | G789  |
      | author.id  | 123   |
      | content    | hello |
    Then session "discord-C999" has transcript matching:
      | type    | message.role | message.model | message.content |
      | message | user         |               | hello           |
      | message | assistant    | echo-chef     | got it          |
