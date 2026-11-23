# Needle Chat

A tiny agent harness for **Needle 3** (tool calling) and **Whistle** (speech) by Cactus,
running entirely on your Android phone. Hermes-style, rebuilt for a model that calls tools
instead of writing text.

| Hermes (LLM) | Needle Chat |
|---|---|
| Model thinks, calls tools, reads results, writes an answer | Needle makes one checked call; the harness runs it and feeds the JSON result back; Needle follows up or finishes (≤ 3 steps). The answer is the tools' results |
| All tools in the prompt | Tool retrieval: Needle embeddings pick the 4 tools that fit each message |
| System prompt | Facts: the date and your profile (`user: Sam; location: Berlin`) |
| MEMORY.md / USER.md | `remember` / `recall` over embedded facts, plus a profile pulled out with Needle's structured extraction |
| Asking for missing info | Slot filling from the profile (weather without a city uses yours) |
| Skills (markdown procedures) | Skills as macros: a named tool whose steps call other tools with `{slots}` |
| Approvals | Confidence gate, and tools that send things always ask (Run / Cancel) |
| Conversation context | Follow-ups: "and in Paris?" retries the last tool |

Tools: `set_timer` (Clock app), `get_weather` (Open-Meteo), `search_web` (browser),
`send_message` (messaging app, you press send), `create_note`, `remember`, `recall`, your
skills, and custom tools (shown, not run).

Type, or tap the mic (a pause ends a message). Settings: models, CPU threads per model, agent
limits, skills, memory, custom tools, facts. The app updates itself.

Download: https://github.com/imyuesei/Poke/releases/latest/download/poke.apk
