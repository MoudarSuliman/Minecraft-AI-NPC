# LLM Intelligence NPC

A Minecraft Java Edition (Fabric) mod that turns a villager into an embodied NPC driven by a large language model. The NPC understands natural language instructions typed in chat, perceives the world around it through a structured snapshot, and carries out a fixed set of validated actions: conversation, recipe lookup, entity search, area scouting, trading, and simple building. The LLM is used for interpretation and dialogue only; every world-modifying action is checked and executed by deterministic Java code before it happens.

## Features

- **Natural language interaction** — instruct the NPC in plain English via chat; no fixed command syntax for gameplay requests.
- **Grounded responses** — answers about the world or recipes are drawn from live game state and the Minecraft recipe registry, not the model's training data.
- **Multi-turn trade negotiation** — the NPC can quote, negotiate, accept, or decline trades within configurable price bounds.
- **Entity search and area scouting** — send the NPC to look for a specific entity or explore in a direction, with a spoken report when it's done.
- **Simple building** — the NPC can assemble a small structure from materials staged in a nearby chest.
- **Memory** — short-term dialogue context, active task state, and long-term episodic memory that persists across server restarts.
- **Two LLM backend modes** — a local model served through [Ollama](https://ollama.com), or a cloud provider (OpenAI, Anthropic, or Google Gemini), selectable per deployment or set to fail over automatically.
- **Safety policy** — a per-NPC action cooldown and a block deny-list run before any action reaches the game world.

## Requirements

- Java 21 or later
- Minecraft Java Edition 26.1.2
- Fabric Loader 0.19.2 or later
- The matching Fabric API build
- Either [Ollama](https://ollama.com) running locally, or an API key for OpenAI, Anthropic, or Google Gemini

## Installation

1. Install a Fabric server for Minecraft 26.1.2 (see the [Fabric installation guide](https://fabricmc.net/use/server/)).
2. Download the matching Fabric API build and place it in the server's `mods` folder.
3. Build this mod from source (see below), or use a pre-built JAR, and place it in the same `mods` folder.
4. Set up an LLM backend:
   - **Local**: install Ollama and pull a model, e.g. `ollama pull qwen2.5`.
   - **Cloud**: obtain an API key from OpenAI, Anthropic, or Google Gemini.
5. Start the server once to generate the default configuration file at `config/llm_npc/config.json`, then stop it and edit that file (see Configuration below).
6. Start the server again.

### Building from source

```
./gradlew build
```

The compiled mod JAR is written to `build/libs/`.

## Configuration

`config/llm_npc/config.json`, created automatically on first server start:

```json
{
  "model": "auto",
  "local_model": "llama3",
  "cloud_provider": "openai",
  "cloud_model": "gpt-4o",
  "cloud_api_key": ""
}
```

- `model` — `local` (Ollama only), `cloud` (the selected cloud provider only), or `auto` (try Ollama first, fall back to cloud).
- `local_model` — the Ollama model tag to use, e.g. `qwen2.5`.
- `cloud_provider` — `openai`, `anthropic`, or `gemini`.
- `cloud_model` — the cloud model name; defaults to a sensible model for the selected provider if left unset.
- `cloud_api_key` — the API key for the selected cloud provider. Can also be supplied via the `CLOUD_API_KEY` environment variable.

Trade item prices are configured separately at `config/llm_npc/trade_prices.json`, or in-game via `/llm_trade_prices`.

## Usage

| Command | Effect |
|---|---|
| `/llm_spawn <name>` | Spawns a new villager and registers it as an NPC agent. Name is optional. |
| `/llm_bind_nearest` | Registers the nearest existing villager as an NPC agent. |
| `/llm_tell <message>` | Sends a natural language instruction to the nearest bound NPC. |
| `/llm_status` | Reports the NPC's current state without an LLM call. |
| `/llm_debug` | Extended diagnostic output: owner, next think time, last decision, memory tier sizes. |
| `/llm_trade_prices` | Opens a GUI to edit per-item trade prices. |

NPC memory persists in `config/llm_npc/memory.db` (SQLite) and survives server restarts. Delete this file to reset an NPC's memory.

### Evaluation

| Command | Effect |
|---|---|
| `/llm_test` | Runs the automated intent classification suite (30 cases) against the nearest bound NPC. Results stream to chat and are written to `config/llm_npc/intent_tests.csv`. |
| `/llm_test_cancel` | Cancels a run that is in progress. |

Each case sends a fixed instruction and compares the classified intent against an expected value. A case that produces no result within 60 seconds is recorded as `timeout` and the run continues. If the NPC dies during a run, the run aborts and reports how far it got; partial results are still written to the CSV.

## License

This project is licensed under the GNU General Public License v3.0 or later (GPL-3.0-or-later). See the [LICENSE](LICENSE) file for the full text.
