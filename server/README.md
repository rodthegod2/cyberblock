# Server templates

Templates only; nothing here is a live server.

| File | Used by | What it is |
|---|---|---|
| `test-server.properties` | `tools/mpcheck.py`, the `runServer` Gradle task | an offline-mode, loopback-only `server.properties` for the local test servers |
| `eula.txt.template` | the same | the `eula.txt` those test servers need (the Minecraft EULA acceptance, for the throw-away run folder only) |
| `server.json.example` | `tools/mpcheck.py`, the Apex server notes | `config/cyberblock/server.json` with the defaults of the megaplan section 1.13 (`maxHosts` 1) |

The harness copies them into a temporary run folder and adds the per-run settings (server port, a random RCON password) there;
the repository never holds a password, an address or a world. Run `uv run python -m tools.mpcheck --help` from a worktree.
