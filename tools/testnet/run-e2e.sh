#!/usr/bin/env bash
# Runs PrivateChat's end-to-end test over a private Tor network on this machine, built with
# Tor's own testing tool (chutney). No connection to the real Tor network is needed.
#
# Requires: tor and tor-gencert (e.g. `apt install tor`), python3, git.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${TESTNET_WORK_DIR:-$ROOT/build/testnet}"
mkdir -p "$WORK"

if [ ! -d "$WORK/chutney" ]; then
    git clone --depth 1 https://gitlab.torproject.org/tpo/core/chutney.git "$WORK/chutney"
fi
if [ ! -x "$WORK/chutney/venv/bin/chutney" ]; then
    python3 -m venv "$WORK/chutney/venv"
    "$WORK/chutney/venv/bin/pip" install -q -e "$WORK/chutney"
fi

chutney() { "$WORK/chutney/venv/bin/chutney" --data-dir "$WORK/net" "$@"; }

rm -rf "$WORK/net"
chutney init --net-from-script-path "$ROOT/tools/testnet/privatechat-relays" \
    --listen-address 127.0.0.1 --disable-ipv6 true --enable-controlsocket false --tor-sandbox false
chutney configure
trap 'chutney stop >/dev/null 2>&1 || true' EXIT
chutney start
chutney wait_for_bootstrap

TORRC="$(ls "$WORK"/net/nodes/*/torrc | head -1)"
{ echo "TestingTorNetwork 1"; grep '^DirAuthority' "$TORRC"; } > "$WORK/testnet.torrc"

cd "$ROOT"
PRIVATECHAT_TESTNET_TORRC="$WORK/testnet.torrc" ./gradlew :core:test --tests '*TorTestnetEndToEndTest*' --rerun
echo "End-to-end test over Tor passed."
