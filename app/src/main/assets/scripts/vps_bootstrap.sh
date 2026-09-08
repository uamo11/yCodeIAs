#!/usr/bin/env bash
# ==============================================================================
# yCode Universal VPS Bootstrap Script
# Supports: Debian, Ubuntu, CentOS, RHEL, Fedora, Arch, Alpine, Rocky, Alma, etc.
# Architectures: x86_64 (AVX2 & baseline), aarch64/arm64
# Inits: systemd, openrc, background supervisor
# ==============================================================================
set -e

PORT=${1:-4099}
ACTION=${2:-"setup"} # setup, status, start, stop, restart

log() {
    echo "[ycode-vps] $1"
}

# 1. Architecture Detection
ARCH=$(uname -m)
case "$ARCH" in
    x86_64|amd64)
        NORM_ARCH="x64"
        ;;
    aarch64|arm64)
        NORM_ARCH="arm64"
        ;;
    armv7l|armhf)
        NORM_ARCH="armv7"
        ;;
    *)
        NORM_ARCH="$ARCH"
        ;;
esac

# 2. OS & Distro Detection
DISTRO="unknown"
DISTRO_FAMILY="unknown"
if [ -f /etc/os-release ]; then
    . /etc/os-release
    DISTRO="$NAME $VERSION_ID"
    DISTRO_FAMILY="${ID_LIKE:-$ID}"
elif [ -f /etc/alpine-release ]; then
    DISTRO="Alpine $(cat /etc/alpine-release)"
    DISTRO_FAMILY="alpine"
elif [ -f /etc/debian_version ]; then
    DISTRO="Debian $(cat /etc/debian_version)"
    DISTRO_FAMILY="debian"
elif [ -f /etc/redhat-release ]; then
    DISTRO="$(cat /etc/redhat-release)"
    DISTRO_FAMILY="rhel"
fi

# 3. Libc Detection (glibc vs musl)
IS_MUSL=false
if [ -f /etc/alpine-release ]; then
    IS_MUSL=true
elif command -v ldd >/dev/null 2>&1; then
    if ldd --version 2>&1 | grep -qi musl; then
        IS_MUSL=true
    fi
fi

# 4. AVX2 / CPU Feature Detection (for x86_64)
NEEDS_BASELINE=false
if [ "$NORM_ARCH" = "x64" ]; then
    if ! grep -qwi avx2 /proc/cpuinfo 2>/dev/null; then
        NEEDS_BASELINE=true
    fi
fi

# 5. Determine Target Binary Name
# anomalyco/opencode release targets:
# linux-x64, linux-x64-baseline, linux-x64-musl, linux-arm64
TARGET="linux-$NORM_ARCH"
if [ "$NEEDS_BASELINE" = "true" ]; then
    TARGET="$TARGET-baseline"
fi
if [ "$IS_MUSL" = "true" ]; then
    TARGET="$TARGET-musl"
fi

log "Detected: OS='$DISTRO', Arch='$ARCH' ($NORM_ARCH), Musl=$IS_MUSL, Baseline=$NEEDS_BASELINE, Target='$TARGET'"

# 6. Ensure Prerequisites (curl/wget, tar, gzip)
ensure_package() {
    PKG=$1
    if command -v "$PKG" >/dev/null 2>&1; then
        return 0
    fi
    log "Installing missing dependency: $PKG..."
    if command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$PKG"
    elif command -v apk >/dev/null 2>&1; then
        apk add --no-cache "$PKG"
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y -q "$PKG"
    elif command -v yum >/dev/null 2>&1; then
        yum install -y -q "$PKG"
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Sy --noconfirm "$PKG"
    elif command -v zypper >/dev/null 2>&1; then
        zypper --non-interactive install "$PKG"
    else
        log "Warning: Cannot auto-install $PKG (unknown package manager)"
    fi
}

ensure_package "tar"
ensure_package "gzip"

DOWNLOAD_CMD=""
if command -v curl >/dev/null 2>&1; then
    DOWNLOAD_CMD="curl -fsSL"
elif command -v wget >/dev/null 2>&1; then
    DOWNLOAD_CMD="wget -qO-"
else
    ensure_package "curl"
    if command -v curl >/dev/null 2>&1; then
        DOWNLOAD_CMD="curl -fsSL"
    else
        ensure_package "wget"
        DOWNLOAD_CMD="wget -qO-"
    fi
fi

# 7. Install Directory
if [ "$(id -u)" -eq 0 ]; then
    BIN_DIR="/usr/local/bin"
    OPT_DIR="/opt/opencode"
else
    BIN_DIR="$HOME/.local/bin"
    OPT_DIR="$HOME/.opencode"
fi
mkdir -p "$BIN_DIR" "$OPT_DIR"

OPENCODE_BIN="$BIN_DIR/opencode"
if [ ! -f "$OPENCODE_BIN" ] && [ -f "$HOME/.opencode/bin/opencode" ]; then
    OPENCODE_BIN="$HOME/.opencode/bin/opencode"
fi

# 8. Check/Install OpenCode
NEED_DOWNLOAD=true
if [ -f "$OPENCODE_BIN" ]; then
    if "$OPENCODE_BIN" --version >/dev/null 2>&1; then
        CURRENT_VER=$("$OPENCODE_BIN" --version 2>/dev/null || echo "")
        log "OpenCode is already installed: version $CURRENT_VER at $OPENCODE_BIN"
        NEED_DOWNLOAD=false
    fi
fi

if [ "$NEED_DOWNLOAD" = "true" ]; then
    FILENAME="opencode-$TARGET.tar.gz"
    DOWNLOAD_URL="https://github.com/anomalyco/opencode/releases/latest/download/$FILENAME"
    log "Downloading $DOWNLOAD_URL..."
    
    TMP_DIR=$(mktemp -d)
    if ! $DOWNLOAD_CMD "$DOWNLOAD_URL" | tar -xz -C "$TMP_DIR" 2>/dev/null; then
        log "Failed to download $FILENAME. Trying official installer fallback..."
        curl -fsSL https://opencode.ai/install | bash
        OPENCODE_BIN="$HOME/.opencode/bin/opencode"
    else
        if [ -f "$TMP_DIR/opencode" ]; then
            mv "$TMP_DIR/opencode" "$BIN_DIR/opencode"
            chmod +x "$BIN_DIR/opencode"
            OPENCODE_BIN="$BIN_DIR/opencode"
        fi
        rm -rf "$TMP_DIR"
    fi
    log "Installed OpenCode successfully: $("$OPENCODE_BIN" --version)"
fi

# 9. Service Management (systemd or nohup daemon)
HAS_SYSTEMD=false
if command -v systemctl >/dev/null 2>&1 && systemctl status >/dev/null 2>&1; then
    HAS_SYSTEMD=true
fi

PID_FILE="$OPT_DIR/opencode.pid"
LOG_FILE="$OPT_DIR/opencode.log"

start_daemon() {
    log "Starting OpenCode service on port $PORT..."
    if [ "$HAS_SYSTEMD" = "true" ] && [ "$(id -u)" -eq 0 ]; then
        SERVICE_FILE="/etc/systemd/system/opencode.service"
        cat <<EOF > "$SERVICE_FILE"
[Unit]
Description=yCode OpenCode Server
After=network.target

[Service]
Type=simple
ExecStart=$OPENCODE_BIN serve --port $PORT --hostname 127.0.0.1
Restart=always
RestartSec=3
WorkingDirectory=$HOME
Environment=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$HOME/.local/bin

[Install]
WantedBy=multi-user.target
EOF
        systemctl daemon-reload
        systemctl enable opencode >/dev/null 2>&1 || true
        systemctl restart opencode
    else
        # Stop previous instance if running
        if [ -f "$PID_FILE" ]; then
            OLD_PID=$(cat "$PID_FILE")
            kill -9 "$OLD_PID" 2>/dev/null || true
            rm -f "$PID_FILE"
        fi
        pkill -f "$OPENCODE_BIN serve" 2>/dev/null || true
        sleep 1
        nohup "$OPENCODE_BIN" serve --port "$PORT" --hostname 127.0.0.1 > "$LOG_FILE" 2>&1 &
        echo $! > "$PID_FILE"
    fi
}

start_daemon

# 10. Verify Health
log "Verifying OpenCode health on 127.0.0.1:$PORT..."
HEALTHY=false
for i in $(seq 1 15); do
    sleep 1
    if command -v curl >/dev/null 2>&1; then
        HEALTH_RES=$(curl -s http://127.0.0.1:$PORT/global/health || true)
    elif command -v wget >/dev/null 2>&1; then
        HEALTH_RES=$(wget -qO- http://127.0.0.1:$PORT/global/health || true)
    else
        HEALTH_RES=""
    fi
    if echo "$HEALTH_RES" | grep -q '"healthy":true'; then
        HEALTHY=true
        break
    fi
done

if [ "$HEALTHY" = "true" ]; then
    FINAL_VERSION=$("$OPENCODE_BIN" --version 2>/dev/null || echo "1.0.0")
    log "SUCCESS: OpenCode is healthy and listening on port $PORT (version $FINAL_VERSION)"
    # Output final structured JSON marker for the Android app to easily parse
    cat <<JSON
__YCODE_RESULT_START__
{
  "status": "ok",
  "port": $PORT,
  "version": "$FINAL_VERSION",
  "distro": "$DISTRO",
  "arch": "$ARCH",
  "has_systemd": $HAS_SYSTEMD,
  "binary": "$OPENCODE_BIN"
}
__YCODE_RESULT_END__
JSON
else
    log "ERROR: OpenCode failed to respond on port $PORT"
    cat "$LOG_FILE" 2>/dev/null | tail -n 20 || journalctl -u opencode -n 20 --no-pager 2>/dev/null || true
    cat <<JSON
__YCODE_RESULT_START__
{
  "status": "error",
  "message": "Health check timed out on port $PORT"
}
__YCODE_RESULT_END__
JSON
    exit 1
fi
