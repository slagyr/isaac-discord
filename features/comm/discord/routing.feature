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
      | comms.discord.discord/token             | test-token |
      | comms.discord.discord/allow-from.users  | ["123"]    |
      | comms.discord.discord/allow-from.guilds | ["G789"]   |
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
      | comms.discord.discord/channels.C999.session | kitchen |
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

  Scenario: an accepted Discord message logs its resolved routing
    And config:
      | comms.discord.discord/token                          | test-token    |
      | comms.discord.discord/allow-from.users               | cordelia      |
      | comms.discord.discord/allow-from.guilds              | harbor-guild  |
      | comms.discord.discord/channels.lantern-room.session  | signal-loft   |
      | comms.discord.discord/channels.lantern-room.crew     | harbormaster  |
      | comms.discord.discord/channels.lantern-room.with-model | harbor-echo |
      | crew.harbormaster.model                              | grover        |
      | crew.harbormaster.soul                               | You keep the lights and ledgers. |
      | models.harbor-echo.model                             | echo          |
      | models.harbor-echo.provider                          | grover        |
      | models.harbor-echo.context-window                    | 32768         |
      | sessions.naming-strategy                             | sequential    |
    And the Discord client is ready as bot "harbormaster-bot"
    And the following model responses are queued:
      | model | type | content                    |
      | echo  | text | Lantern trimmed and ready. |
    When Discord sends MESSAGE_CREATE:
      | channel_id | lantern-room |
      | guild_id   | harbor-guild |
      | author.id  | cordelia     |
      | content    | lamp report  |
    Then the log has entries matching:
      | level  | event                  | channelId    | guildId      | session     | crew         | model       | channelOverride |
      | :debug | :discord.route/inbound | lantern-room | harbor-guild | signal-loft | harbormaster | harbor-echo | true            |

  Scenario: creating a new Discord session logs the created session name
    And config:
      | comms.discord.discord/token             | test-token    |
      | comms.discord.discord/allow-from.users  | cordelia      |
      | comms.discord.discord/allow-from.guilds | harbor-guild  |
      | sessions.naming-strategy                | sequential    |
    And the Discord client is ready as bot "harbormaster-bot"
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | Ledger opened. |
    When Discord sends MESSAGE_CREATE:
      | channel_id | lantern-room |
      | guild_id   | harbor-guild |
      | author.id  | cordelia     |
      | content    | open the log |
    Then the log has entries matching:
      | level | event                         | session              | crew |
      | :info | :discord.route/session-created | discord-lantern-room | main |
  @wip
  Scenario: a hot-reloaded channel session override applies to both inbound routing and outbound reply
    Given the discord Isaac server is started
    When the isaac EDN file "config/isaac.edn" exists with:
      | path                                        | value            |
      | comms.discord.discord/token                 | test-token       |
      | comms.discord.discord/allow-from.users      | cordelia         |
      | comms.discord.discord/allow-from.guilds     | harbor-guild     |
      | comms.discord.discord/channels.lantern-room.session | signal-loft |
      | sessions.naming-strategy                    | sequential       |
    And the config is reloaded
    Given the Discord client is ready as bot "harbormaster-bot"
    And the following model responses are queued:
      | model | type | content                  |
      | echo  | text | First light at six bells. |
    When Discord sends MESSAGE_CREATE:
      | channel_id | lantern-room |
      | guild_id   | harbor-guild |
      | author.id  | cordelia     |
      | content    | tide tables  |
    Then session "signal-loft" has transcript matching:
      | type    | message.role | message.content            |
      | message | user         | tide tables                |
      | message | assistant    | First light at six bells.  |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/lantern-room/messages" matches:
      | method                | POST           |
      | headers.Authorization | Bot test-token |
      | body.content          | First light at six bells. |
    And the log has no entries matching:
      | event                   |
      | :discord.client/started |
      | :discord.client/stopped |

  Scenario: per-channel session override with bare numeric channel key
    Given the following sessions exist:
      | name    |
      | kitchen |
    And Discord channels map has numeric key:
      | channel_id          | session |
      | 1491164414794272848 | kitchen |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | got it  |
    When Discord sends MESSAGE_CREATE:
      | channel_id | 1491164414794272848 |
      | guild_id   | G789                |
      | author.id  | 123                 |
      | content    | hello               |
    Then session "kitchen" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | got it          |

  Scenario: Discord-wide crew and model apply when the channel has no override
    And config:
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
    And config:
      | comms.discord.crew                 | marvin                    |
      | comms.discord.model                | bender                    |
      | comms.discord.discord/channels.C999.with-model | chef-bender           |
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

  Scenario: an invalid Discord config logs a routing config-load failure
    And config:
      | comms.discord.discord/allow-from.users  | cordelia     |
      | comms.discord.discord/allow-from.guilds | harbor-guild |
    And the Discord client is ready as bot "harbormaster-bot"
    And the isaac EDN file "config/isaac.edn" contains:
      """
      {:comms {:discord {:discord/token "test-token"
                         :discord/allow-from {:users ["cordelia"]
                                              :guilds ["harbor-guild"]}
                         :discord/channels {lantern-room {:session "signal-loft"}}}}
      """
    When Discord sends MESSAGE_CREATE:
      | channel_id | lantern-room |
      | guild_id   | harbor-guild |
      | author.id  | cordelia     |
      | content    | lamp report  |
    Then the log has entries matching:
      | level  | event                            | channelId    |
      | :error | :discord.route/config-load-failed | lantern-room |
  @wip
  Scenario: a hot-reloaded channel crew override applies without reconnecting the Discord client
    Given the discord Isaac server is started
    When the isaac EDN file "config/isaac.edn" exists with:
      | path                                        | value                            |
      | comms.discord.discord/token                 | test-token                       |
      | comms.discord.discord/allow-from.users      | cordelia                         |
      | comms.discord.discord/allow-from.guilds     | harbor-guild                     |
      | comms.discord.discord/channels.lantern-room.session | signal-loft               |
      | comms.discord.discord/channels.lantern-room.crew    | harbormaster             |
      | crew.harbormaster.model                     | harbor-echo                      |
      | crew.harbormaster.soul                      | You keep the lights and ledgers. |
      | models.harbor-echo.model                    | echo                             |
      | models.harbor-echo.provider                 | grover                           |
      | models.harbor-echo.context-window           | 32768                            |
      | sessions.naming-strategy                    | sequential                       |
    And the config is reloaded
    Given the Discord client is ready as bot "harbormaster-bot"
    And the following model responses are queued:
      | model | type | content                    |
      | echo  | text | Lantern trimmed and ready. |
    When Discord sends MESSAGE_CREATE:
      | channel_id | lantern-room |
      | guild_id   | harbor-guild |
      | author.id  | cordelia     |
      | content    | lamp report  |
    Then the system prompt contains "You keep the lights and ledgers."
    And session "signal-loft" has transcript matching:
      | type    | message.role | message.model | message.content              |
      | message | user         |               | lamp report                  |
      | message | assistant    | echo          | Lantern trimmed and ready.   |
    And an outbound HTTP request to "https://discord.com/api/v10/channels/lantern-room/messages" matches:
      | method                | POST           |
      | headers.Authorization | Bot test-token |
      | body.content          | Lantern trimmed and ready. |
