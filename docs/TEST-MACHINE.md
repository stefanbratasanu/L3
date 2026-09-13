# Running L3 on the test machine

This is the machine that runs the **real Lineage 2 Interlude client** and the L3
server. The other machine (the "build box") only writes code and commits it.

Everything here is **portable and needs no administrator rights.**

---

## One-time setup

1. **Install git** (if you don't have it): https://git-scm.com/download/win — default options are fine.

2. **Clone the repo** into `C:\Agentic\L3` (the scripts assume this path):
   ```
   mkdir C:\Agentic
   cd C:\Agentic
   git clone <the L3 repo HTTPS URL> L3
   ```

3. That's it. The first time you run it (next step), it will automatically download
   a portable **JDK** and **MariaDB** into `C:\Agentic\tools`. No installers, no admin.
   > If the auto-download is blocked on your network, run `powershell -ExecutionPolicy Bypass -File C:\Agentic\L3\L3-setup.ps1`
   > by hand, or drop portable copies into `C:\Agentic\tools\jdk-25` and `C:\Agentic\tools\mariadb` yourself.

---

## Every time you want to play/test

**Double-click `C:\Agentic\L3\L3-run.bat`.**

It will, in order:
1. `git pull` — grab the latest code + database from the build box.
2. Start MariaDB (a small console window).
3. Restore the database (only if it changed since last time).
4. Open **two server windows**: LoginServer, then GameServer.
5. Wait.

When both server windows say they're loaded, **launch your L2 client** and log in
(see below). Play / test as long as you want.

**When you're done: close both server windows** (LoginServer and GameServer).
The script then automatically:
- dumps the database (your characters, items, and later the AI agents' state),
- commits it, and
- pushes it back to GitHub —

…so the build box sees exactly what happened during your session. Then it prints
"Done" and you can close the window.

---

## Pointing the client at this machine

- The server is this machine. On the client, set the login server IP to this
  machine's LAN address (the build box currently sees it as **`192.168.1.141`** —
  confirm with `ipconfig` if your wifi assigned a different one).
- Edit the client's `system\l2.ini` (or the launcher's server list) so the login
  server host is that IP, port **2106**.
- **GameGuard is disabled server-side**, so you should not need the GameGuard
  process. If your client force-launches GG, use a no-GG / offline launcher.
- A test account is created automatically (login-server auto-create is on for dev).
  Just type a new username/password at the login screen; it registers on first use.

---

## Command-line options (optional)

Run from PowerShell in `C:\Agentic\L3` if you want more control:

| Command | Effect |
|---|---|
| `.\L3-run.ps1` | normal run (pull → start → on close: commit+push DB) |
| `.\L3-run.ps1 -NoPull` | don't pull first (offline / local iteration) |
| `.\L3-run.ps1 -NoPush` | commit the DB locally on close, but don't push |
| `.\L3-run.ps1 -NoCommit` | don't touch git at all on close |
| `.\L3-run.ps1 -FreshDb` | wipe & reload the DB from the pulled snapshot before starting |

---

## If Windows shows a UAC or SmartScreen prompt

That's your endpoint-security software inspecting an unrecognized portable
`java.exe` / `mariadbd.exe`. **The server does not need administrator rights.**
You can decline the prompt and everything still works. (If it nags every launch,
ask IT to allow `C:\Agentic\tools\jdk-25\bin\java.exe`.)

---

## How the sync works (so you know what git is doing)

- **Code + schema** flow **build box → here** (you `git pull`).
- **Database state** flows **here → build box**: on close we dump the DB to a single
  text file `server/dist/db_snapshot/l2jmobiusinterlude.sql` and commit/push it.
- Only this machine runs the live server, so only this machine changes that file —
  there are no merge conflicts in normal use.
- The raw MariaDB data folder (`C:\Agentic\data\mariadb`) is **never** committed —
  it's big binary state; the `.sql` snapshot is the portable, reviewable form.
