# /// script
# requires-python = ">=3.10"
# dependencies = ["mcp==2.3.0"]
# ///
"""MCP server giving an AI coding assistant the bot's state, log, frames, template matching, OCR, and input while it is held.

The assistant starts this with `uv run --script` (see `.mcp.json`), so `mcp` lives in uv's own environment. Each tool forwards to the app's
developer remote (`DevMcpServer.kt`), which runs while "Developer MCP Server" is on in Debug Settings. On start, and whenever the app stops
answering, this script runs `adb forward` so the PC reaches the app's loopback port.

Run `uv run --script scripts/uma_mcp.py --selftest` to check the forward and the app without an MCP client.
"""

import asyncio
import base64
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

from mcp.server.mcpserver import Image, MCPServer
from mcp.server.mcpserver.exceptions import ToolError

# The port the app listens on, on the device's loopback.
PORT = 9100
# Where the forwarded port answers on the PC.
URL = f"http://127.0.0.1:{PORT}"
# The device to forward from. Override with `UMA_ADB_SERIAL`.
SERIAL = os.environ.get("UMA_ADB_SERIAL", "192.168.0.102:5555")
# Seconds to wait for a reply. Above the app's own 15 s wait for a hold to land, so its clearer reply arrives first.
TIMEOUT = 30
# Seconds between pings, which keep "Assistant connected" in Debug Settings. Below the app's 15 s timeout.
HEARTBEAT = 5

server = MCPServer(
    "uma",
    instructions="Inspects and steers the Umamusume bot running on the Android device. Coordinates are screen pixels of the full frame, "
    "the same space `find_template` returns. Log lines start with an elapsed stamp like 00:12:34.567, and `frame_at` takes that stamp to "
    "show what the bot saw then. Hold the bot before tapping or swiping, and release it afterwards.",
)


def log(message):
    """Write a diagnostic line to stderr, since stdout carries the MCP protocol.

    Args:
        message: The line to write.
    """
    print(f"[uma_mcp] {message}", file=sys.stderr, flush=True)


def adb():
    """Find the adb executable.

    Returns:
        The path from `UMA_ADB`, or `adb` from PATH, or the default Android SDK location.
    """
    sdk_adb = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
    return os.environ.get("UMA_ADB") or shutil.which("adb") or sdk_adb


def forward():
    """Connect to the device and forward the app's port to the PC. Failures are logged, not raised, so the bridge still starts.

    Returns:
        True if the forward was set up.
    """
    try:
        if ":" in SERIAL:
            subprocess.run([adb(), "connect", SERIAL], capture_output=True, timeout=10)
        result = subprocess.run([adb(), "-s", SERIAL, "forward", f"tcp:{PORT}", f"tcp:{PORT}"], capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired) as error:
        log(f"adb forward failed: {error}")
        return False
    if result.returncode != 0:
        log(f"adb forward failed: {result.stderr.strip()}")
        return False
    return True


def call(command, **kwargs):
    """Send one command to the app and return its result.

    Args:
        command: The remote command name, one of `DevMcpServer.commands`.
        **kwargs: The command's arguments. None values are left out.

    Returns:
        The command's result, decoded from JSON.

    Raises:
        ToolError: The app is not answering, or the command failed there. Any other exception reaches the assistant as a bare "Error executing
            tool", without its message.
    """
    body = json.dumps({key: value for key, value in kwargs.items() if value is not None}).encode("utf-8")
    request = urllib.request.Request(f"{URL}/{command}", data=body, method="POST", headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            reply = json.load(response)
    except urllib.error.URLError as error:
        raise ToolError(
            f"nothing answering on {URL} - turn on Developer MCP Server in the app's Debug Settings and check adb reaches {SERIAL} ({error.reason})"
        ) from error
    if not reply["ok"]:
        raise ToolError(reply["error"])
    return reply["result"]


def image(result):
    """Turn an image result from the app into MCP content.

    Args:
        result: A result with base64 `image` and a `note`.

    Returns:
        The image and its note.
    """
    return [Image(data=base64.b64decode(result["image"]), format="jpeg"), result["note"]]


def ping():
    """Tell the app this bridge is alive for as long as the assistant keeps it open. A failed ping re-runs the forward, in case adb dropped it."""
    while True:
        try:
            call("ping")
        except Exception:
            forward()
        time.sleep(HEARTBEAT)


@server.tool()
def status() -> dict:
    """Whether the bot and screen capture run, the hold state, the frame log session, the run progress, and the trainee's live state."""
    return call("status")


@server.tool()
def logs(since: int | None = None, limit: int | None = None, contains: str | None = None) -> dict:
    """Lines from the bot's message log. Pass `since` (the `nextIndex` from the last call) to poll new lines only. Defaults to the last 100."""
    return call("logs", since=since, limit=limit, contains=contains)


@server.tool()
def run_summary() -> dict | str:
    """The run analytics summary (per-turn stats, energy, mood, and actions) that the Remote Log Viewer charts use."""
    return call("run_summary")


@server.tool()
def screenshot(scale: float = 0.5) -> list:
    """The current frame from the bot's screen capture, as a JPEG resized by `scale` (0.1 to 1.0)."""
    return image(call("screenshot", scale=scale))


@server.tool()
def find_template(name: str, confidence: float | None = None, region: list[int] | None = None) -> dict:
    """Run the bot's template matching for one template on the current frame. Only works while the bot runs.

    `name` is a path under assets/images without the extension, like `components/button/back`. `region` is `[x, y, width, height]`.
    """
    return call("find_template", name=name, confidence=confidence, region=region)


@server.tool()
def ocr_region(x: int, y: int, width: int, height: int, grayscale: bool = True, threshold: bool = True) -> str:
    """Run the bot's OCR on a rectangle of the current frame, in screen pixels."""
    return call("ocr_region", x=x, y=y, width=width, height=height, grayscale=grayscale, threshold=threshold)


@server.tool()
def settings(category: str | None = None) -> dict:
    """The saved settings by category, read-only. Secrets are redacted. Pass `category` (like `training` or `debug`) for one group."""
    return call("settings", category=category)


@server.tool()
def stop() -> str:
    """Stop the bot and its screen capture, the same as pressing Stop in the app."""
    return call("stop")


@server.tool()
def hold() -> dict:
    """Pause the bot at its next safe point, waiting up to 15 s for it to land. A held bot does nothing until `release`."""
    return call("hold")


@server.tool()
def release() -> dict:
    """Resume a held bot. It re-reads the current screen first, so whatever you tapped while it was held is picked up."""
    return call("release")


@server.tool()
def tap(x: float, y: float) -> dict:
    """Tap a point in screen pixels. The bot must be held. Waits 1 s afterwards so the game can react."""
    return call("tap", x=x, y=y)


@server.tool()
def swipe(x1: float, y1: float, x2: float, y2: float, duration_ms: int = 500) -> dict:
    """Swipe between two points in screen pixels. The bot must be held. Waits 1 s afterwards so the game can react."""
    return call("swipe", x1=x1, y1=y1, x2=x2, y2=y2, durationMs=duration_ms)


@server.tool()
def frames(session: str | None = None, since: str | None = None, until: str | None = None, limit: int | None = None) -> dict:
    """List the frame log: one half-scale frame per INFO, WARN, or ERROR log line, at most one a second.

    `since` and `until` are log stamps like 00:12:34.567. Defaults to the last 200 frames of the current or newest session.
    """
    return call("frames", session=session, since=since, until=until, limit=limit)


@server.tool()
def frame_at(time: str, session: str | None = None) -> list:
    """What the bot saw at a log line: the last saved frame at or before `time`, a log stamp like 00:12:34.567."""
    return image(call("frame_at", time=time, session=session))


async def selftest():
    """List the tools, then call `status` and print it, to check the bridge without an MCP client.

    Returns:
        The process exit code.
    """
    tools = await server.list_tools()
    log(f"{len(tools)} tools: {', '.join(tool.name for tool in tools)}")
    log(f"forward to {SERIAL}: {'ok' if forward() else 'failed'}")
    try:
        log(f"status: {json.dumps(call('status'))[:400]}")
    except ToolError as error:
        log(f"status failed: {error}")
        return 1
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(asyncio.run(selftest()))
    forward()
    threading.Thread(target=ping, name="ping", daemon=True).start()
    server.run()
