# test-logs

Server logs from the **test machine**, copied here automatically and committed when a test
session ends (step 8 of `L3-run.ps1`, i.e. after `.sd` or closing a server window).

- `game/` — GameServer logs. `java0.log` is the current one; `java1`/`java2` are older rotations.
  Everything L3 code logs (agent spawns, AI decisions, LLM calls) lands here.
- `login/` — LoginServer logs, including `error0.log`.

**Why this exists.** The build box has no client and never runs a live session, so this is how it
sees what actually happened during a test: boot errors, stack traces, and L3 agent behaviour.

**Notes**
- The live log directories (`server/dist/{game,login}/log/`) stay gitignored — they churn during a
  run and hold `.lck` lock files. Only these copies are tracked (see the negations in
  `.gitignore`).
- Each session **overwrites** these files rather than accumulating timestamped copies, so the repo
  does not grow without bound; the history is still in git if you need an older run.
- Files here are copies. Deleting them loses nothing that matters — the next session repopulates
  them.
