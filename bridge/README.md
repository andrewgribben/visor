# Claude subscription bridge

`bridge.js` lets Visor answer with your Claude Pro or Max plan. It runs Claude Code in Termux on the phone and serves
it as an OpenAI-compatible API on `http://127.0.0.1:8080/v1`. From henriqueleote/visor (bridge 1.4.3), unchanged.

Claude Code does not support Android officially, so this is experimental, and your plan's limits apply.

## Setup

1. Install Termux from F-Droid or GitHub (not the Play Store), then in Termux:

   ```sh
   pkg update && pkg install proot-distro
   proot-distro install ubuntu
   proot-distro login ubuntu
   ```

2. In Ubuntu (`root@localhost`), install Node 20+ and Claude Code, and log in with the subscription:

   ```sh
   apt update && apt install -y nodejs npm curl
   npm install -g @anthropic-ai/claude-code
   claude            # pick the subscription login, open the link, paste the code back
   claude -p "Reply with OK"
   ```

3. Put the bridge at `/root/bridge.js` (the app's Start button runs exactly that), from this repo:

   ```sh
   cd /root && curl -O https://raw.githubusercontent.com/andrewgribben/visor/main/bridge/bridge.js
   node bridge.js    # prints "Claude voice bridge ... on http://127.0.0.1:8080"
   ```

4. In Visor: Settings > Meta AI > Provider > **Claude subscription**. No key is needed. Pick Haiku (fastest), Sonnet or
   Opus as the model, then **Try it**.

## Start and stop from Visor

1. In Termux (not Ubuntu): `mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties`,
   then `termux-reload-settings`, and restart Termux.
2. On the Bridge row tap **Allow**, then **Start** or **Stop**.
3. Turn off battery optimisation for Termux, or Android kills it in the background.

Any app on the phone can reach the bridge's loopback port while it runs.
