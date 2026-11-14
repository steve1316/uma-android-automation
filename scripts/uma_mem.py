# /// script
# requires-python = ">=3.10"
# dependencies = ["perfetto==0.59.1"]
# ///
# pyright: reportAttributeAccessIssue=false
"""Record the bot's memory over a whole run and report what grew and when.

Android's low-memory killer gives no warning and the bot's own log is lost with the process, so everything here is kept outside the app. The trace is
written by the system's `traced` service, and the log and memory samples are written straight to the PC, so all of it survives the kill.

Setup: build with `ORG_GRADLE_PROJECT_uaaProfileable=true yarn build` and install it. Open the app, press Start so the overlay shows, run `record`, and then
press the overlay's play button. Attaching during app launch ends the profile with `Failed to find stackend`.

Usage:
    uv run --script scripts/uma_mem.py record [--out DIR] [--interval SECONDS] [--serial ADDRESS]
    uv run --script scripts/uma_mem.py stop [--serial ADDRESS]
    uv run --script scripts/uma_mem.py report DIR

`record` runs until the app dies, `stop` is called, or Ctrl-C, then writes `report.txt` to the output folder. Java heap dumps are left out on purpose: a
recording with them froze the app's main thread until the profiler was detached.
"""

import argparse
import collections
import csv
import datetime as dt
import itertools
import json
import os
import pathlib
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import threading
import time

PACKAGE = "com.steve1316.uma_android_automation"
DEFAULT_SERIAL = os.environ.get("UMA_ADB_SERIAL", "192.168.0.102:5555")
CONFIG_PATH = pathlib.Path(__file__).with_suffix(".pbtxt")
DEVICE_TRACE_DIR = "/data/misc/perfetto-traces"
SAMPLE_FIELDS = ["time", "pss_kb", "native_kb", "java_kb", "graphics_kb", "swap_kb", "rss_kb", "avail_kb"]
MAX_ADB_FAILURES = 10
TIMELINE_ROWS = 40
JUMP_ROWS = 8
ALLOCATOR_ROWS = 12
APP_PACKAGE_PREFIX = f"{PACKAGE}."
UNWIND_FAILED = "unwind failed"
PROFILE_STALE_SECONDS = 180
LOG_KEEP = re.compile(r"\[UAA\]|\[AUTO_LIB\]|perfetto|heapprofd|lmkd|killinfo|am_proc_died|am_low_memory|am_pss.*uma_android|AndroidRuntime")
LOG_DROP = re.compile(r"avc: denied|auditd")
LOG_TIME = re.compile(r"^(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})")
MEM_LABEL = re.compile(r"\[MEM\] (\S+) pss=")
TOPIC = re.compile(r"\[UAA\][^:]*: \[([A-Z_]+)\]")
GENERIC_TOPICS = {"INFO", "DEBUG", "WARN", "ERROR", "MEM"}
SUMMARY_ROWS = {"java_kb": "Java Heap", "native_kb": "Native Heap", "graphics_kb": "Graphics"}
TOTALS = re.compile(r"TOTAL PSS:\s+(\d+)\s+TOTAL RSS:\s+(\d+)\s+TOTAL SWAP (?:PSS|\(KB\)):\s+(\d+)")
MEM_AVAILABLE = re.compile(r"MemAvailable:\s+(\d+)")


# ////////////////////////////////////////////////////////////////////////////////////////////////////
# ////////////////////////////////////////////////////////////////////////////////////////////////////
# Parsing


def parse_meminfo(text: str) -> dict | None:
    """Read the App Summary of `dumpsys meminfo`.

    Args:
        text: The command's output.

    Returns:
        Values in KB by field name, or None if the process was not found.
    """
    totals = TOTALS.search(text)
    if not totals:
        return None
    result = {"pss_kb": int(totals.group(1)), "rss_kb": int(totals.group(2)), "swap_kb": int(totals.group(3))}
    for field, label in SUMMARY_ROWS.items():
        # The colon separates the summary row from the detail table's row of the same name.
        row = re.search(rf"{label}:\s+(\d+)", text)
        result[field] = int(row.group(1)) if row else 0
    return result


def parse_exit_info(text: str, pid: int) -> dict | None:
    """Find a process's record in `dumpsys activity exit-info`.

    Args:
        text: The command's output.
        pid: The process to look for.

    Returns:
        The record's `timestamp`, `reason`, and `subreason`, or None if the process has no record.
    """
    for block in re.split(r"ApplicationExitInfo #\d+:", text)[1:]:
        if not re.search(rf"^\s*pid={pid}\s*$", block, re.MULTILINE):
            continue
        timestamp = re.search(r"timestamp=(.+)", block)
        reason = re.search(r"reason=\d+ \((.+)\)", block)
        subreason = re.search(r"subreason=\d+ \((.+)\)", block)
        return {
            "timestamp": timestamp.group(1).strip() if timestamp else "?",
            "reason": reason.group(1) if reason else "?",
            "subreason": subreason.group(1) if subreason else "?",
        }
    return None


def keep_log_line(line: str) -> bool:
    """Decide whether a logcat line belongs in the run's log.

    Args:
        line: The line.

    Returns:
        True for bot, profiler, and kill lines, but not SELinux noise that happens to name them.
    """
    return bool(LOG_KEEP.search(line)) and not LOG_DROP.search(line)


def log_time(line: str, year: int) -> dt.datetime | None:
    """Read a logcat line's time.

    Args:
        line: The line.
        year: The year, which logcat leaves out.

    Returns:
        The time, or None if the line has none.
    """
    match = LOG_TIME.match(line)
    if not match:
        return None
    month, day, hour, minute, second, milli = (int(part) for part in match.groups())
    return dt.datetime(year, month, day, hour, minute, second, milli * 1000)


def mem_label(line: str) -> str | None:
    """Read the label of a `[MEM]` line.

    Args:
        line: The line.

    Returns:
        The label, such as `day=12`, or None if this is not a `[MEM]` line.
    """
    match = MEM_LABEL.search(line)
    return match.group(1) if match else None


def topics(lines: list[str]) -> str:
    """Summarize what the bot was doing from its section tags.

    Args:
        lines: Log lines from one stretch of time.

    Returns:
        The most common sections with their counts.
    """
    tags = TOPIC.findall("\n".join(lines))
    counts = collections.Counter(tag for tag in tags if tag not in GENERIC_TOPICS)
    if not counts:
        return "no bot log"
    return ", ".join(f"{tag} x{count}" for tag, count in counts.most_common(3))


def profiler_warnings(lines: list[str]) -> list[str]:
    """Find log lines saying the native profile stopped early.

    Args:
        lines: Log lines from the run.

    Returns:
        One message per problem.
    """
    found = []
    for line in lines:
        if "disconnected from heapprofd (ds shutting down: 0)" in line or "Failed to find stackend" in line:
            clock = line[6:14]
            found.append(f"{clock} Native profile ended early, so later growth is missing from it: {line.split('perfetto:', 1)[-1].strip()}")
    return found


# ////////////////////////////////////////////////////////////////////////////////////////////////////
# ////////////////////////////////////////////////////////////////////////////////////////////////////
# Analysis


def short_frame(name: str) -> str:
    """Shorten a Java frame to its class and method.

    Args:
        name: The frame's full name.

    Returns:
        The last two parts of the name.
    """
    return ".".join(name.split(".")[-2:])


def attribute(frames: list[tuple[str, str]]) -> str:
    """Name who is responsible for an allocation.

    The nearest app frame is often a shared helper, so the next one up is included to show who called it.

    Args:
        frames: The stack as `(function, library path)`, from the allocation outward.

    Returns:
        The library that allocated, then the nearest app frames.
    """
    if any(library == "/ERROR" for _, library in frames):
        return UNWIND_FAILED
    native = [frame for frame in frames if not re.search(r"libc\.so|libc\+\+|heapprofd", frame[1])]
    allocator = native[0] if native else frames[0]
    library = allocator[1].split("/")[-1]
    app_frames = list(dict.fromkeys(short_frame(name) for name, _ in frames if name.startswith(APP_PACKAGE_PREFIX)))
    site = " <- ".join(app_frames[:2]) if app_frames else allocator[0][:70]
    return f"{library}: {site}"


def growth(series: list[float]) -> float:
    """Measure lasting growth, ignoring the sawtooth of memory waiting on a garbage collection.

    Args:
        series: Live size at each snapshot.

    Returns:
        The median of the last third minus the median of the first third, or 0 with too few snapshots to tell.
    """
    third = len(series) // 3
    if third == 0:
        return 0
    return statistics.median(series[-third:]) - statistics.median(series[:third])


def biggest_jumps(samples: list[dict], count: int) -> list[dict]:
    """Find the largest rises between neighboring samples.

    Args:
        samples: Memory samples in time order.
        count: How many to return.

    Returns:
        Rises as `start`, `end`, and `delta_kb`, largest first.
    """
    jumps = [{"start": a["time"], "end": b["time"], "delta_kb": b["pss_kb"] - a["pss_kb"]} for a, b in zip(samples, samples[1:]) if b["pss_kb"] > a["pss_kb"]]
    return sorted(jumps, key=lambda jump: jump["delta_kb"], reverse=True)[:count]


def load_native(trace: pathlib.Path, device_start: dt.datetime, uptime_start: float) -> dict | None:
    """Read the native profile out of a trace.

    Args:
        trace: The trace file.
        device_start: The device's wall clock when the recording started.
        uptime_start: The device's uptime in seconds at that moment, which is the clock the trace uses.

    Returns:
        Snapshot times, live totals, and per-allocator series in MB, or None if the trace holds no native data.
    """
    from perfetto.trace_processor import TraceProcessor  # Imported here so the tests and `stop` run without the dependency.

    processor = TraceProcessor(trace=str(trace))
    try:
        busiest = list(processor.query("select upid, count(*) n from heap_profile_allocation group by upid order by n desc limit 1"))
        if not busiest:
            return None
        upid = busiest[0].upid
        parents, frame_of = {}, {}
        for row in processor.query("select id, parent_id, frame_id from stack_profile_callsite"):
            parents[row.id] = row.parent_id
            frame_of[row.id] = row.frame_id
        frames = {}
        for row in processor.query("select f.id, f.name, m.name lib from stack_profile_frame f join stack_profile_mapping m on m.id = f.mapping"):
            frames[row.id] = (row.name or "?", row.lib or "?")

        def stack_of(callsite: int) -> list[tuple[str, str]]:
            stack = []
            while callsite in frame_of:
                stack.append(frames.get(frame_of[callsite], ("?", "?")))
                callsite = parents[callsite]
            return stack

        names: dict[int, str] = {}
        deltas: dict[int, dict[str, float]] = collections.defaultdict(lambda: collections.defaultdict(float))
        for row in processor.query(f"select ts, callsite_id, sum(size) s from heap_profile_allocation where upid = {upid} group by ts, callsite_id"):
            if row.callsite_id not in names:
                names[row.callsite_id] = attribute(stack_of(row.callsite_id))
            deltas[row.ts][names[row.callsite_id]] += row.s / 1048576
    finally:
        processor.close()

    timestamps = sorted(deltas)
    series: dict[str, list[float]] = collections.defaultdict(lambda: [0.0] * len(timestamps))
    for index, ts in enumerate(timestamps):
        for name, delta in deltas[ts].items():
            series[name][index] = delta
    groups = [{"name": name, "series_mb": list(itertools.accumulate(values))} for name, values in series.items()]
    return {
        "times": [device_start + dt.timedelta(seconds=ts / 1e9 - uptime_start) for ts in timestamps],
        "live_mb": [sum(group["series_mb"][index] for group in groups) for index in range(len(timestamps))],
        "groups": groups,
    }


# ////////////////////////////////////////////////////////////////////////////////////////////////////
# ////////////////////////////////////////////////////////////////////////////////////////////////////
# Report


def latest_before(pairs: list[tuple[dt.datetime, object]], moment: dt.datetime, default):
    """Find the most recent value at a moment.

    Args:
        pairs: `(time, value)` in time order.
        moment: The moment to look up.
        default: What to return if nothing came before it.

    Returns:
        The latest value not after the moment.
    """
    result = default
    for when, value in pairs:
        if when > moment:
            break
        result = value
    return result


def thin(samples: list[dict], limit: int) -> list[dict]:
    """Reduce samples to a readable number of rows, always keeping the first, peak, and last.

    Args:
        samples: Memory samples in time order.
        limit: The rough number of rows wanted.

    Returns:
        The kept samples in time order.
    """
    if len(samples) <= limit:
        return samples
    step = len(samples) / limit
    keep = {int(index * step) for index in range(limit)} | {len(samples) - 1, max(range(len(samples)), key=lambda index: samples[index]["pss_kb"])}
    return [samples[index] for index in sorted(keep)]


def render_samples(run: dict, year_lines: list[tuple[dt.datetime, str]]) -> list[str]:
    """Write the memory headline, timeline, and biggest jumps.

    Args:
        run: The run's data.
        year_lines: Log lines with their times.

    Returns:
        The report's lines.
    """
    samples, native = run["samples"], run["native"]
    if not samples:
        return ["No memory samples were recorded."]
    peak = max(samples, key=lambda sample: sample["pss_kb"])
    minutes = (samples[-1]["time"] - samples[0]["time"]).total_seconds() / 60
    out = [f"Window: {samples[0]['time']:%H:%M:%S} to {samples[-1]['time']:%H:%M:%S} ({minutes:.1f} min), {len(samples)} samples"]
    out.append(f"PSS: first {samples[0]['pss_kb'] // 1024}MB, peak {peak['pss_kb'] // 1024}MB at {peak['time']:%H:%M:%S}, last {samples[-1]['pss_kb'] // 1024}MB")

    labels = [(when, label) for when, line in year_lines if (label := mem_label(line))]
    native_points = list(zip(native["times"], native["live_mb"])) if native else []
    if native:
        native_times, native_live = native["times"], native["live_mb"]
        # Snapshots are a minute apart and memory swings by hundreds of MB between them, so say how close this one is.
        nearest = min(range(len(native_times)), key=lambda index: abs(native_times[index] - peak["time"]))
        distance = abs(native_times[nearest] - peak["time"]).total_seconds()
        # The profiler only sees allocations made after it attaches, so what was already held shows up as a gap.
        out.append(
            f"At the peak, Android counted {peak['native_kb'] // 1024}MB of native heap. The nearest profile snapshot, {distance:.0f}s away, "
            f"explains {native_live[nearest]:.0f}MB of it, and {samples[0]['native_kb'] // 1024}MB was allocated before it attached."
        )

    out += ["", "Timeline (MB)", f"{'time':8} {'where':10} {'pss':>6} {'native':>6} {'java':>5} {'gfx':>5} {'swap':>5} {'avail':>6} {'profiled':>8}"]
    for sample in thin(samples, TIMELINE_ROWS):
        where = latest_before(labels, sample["time"], "-")
        live = latest_before(native_points, sample["time"], None)
        profiled = f"{live:.0f}" if live is not None else "-"
        out.append(
            f"{sample['time']:%H:%M:%S} {where:10} {sample['pss_kb'] // 1024:6} {sample['native_kb'] // 1024:6} {sample['java_kb'] // 1024:5} "
            f"{sample['graphics_kb'] // 1024:5} {sample['swap_kb'] // 1024:5} {sample['avail_kb'] // 1024:6} {profiled:>8}"
        )

    out += ["", "Biggest jumps"]
    for jump in biggest_jumps(samples, JUMP_ROWS):
        window = [line for when, line in year_lines if jump["start"] <= when <= jump["end"]]
        where = latest_before(labels, jump["end"], "-")
        out.append(f"+{jump['delta_kb'] // 1024:5}MB {jump['start']:%H:%M:%S}-{jump['end']:%H:%M:%S} {where:10} {topics(window)}")
    return out


def render_native(native: dict) -> list[str]:
    """Write the allocator tables.

    Args:
        native: The native profile.

    Returns:
        The report's lines.
    """
    final = native["live_mb"][-1]
    unwind_failed = next((group["series_mb"][-1] for group in native["groups"] if group["name"] == UNWIND_FAILED), 0.0)
    out = [f"Native profile: {len(native['times'])} snapshots, {final:.0f}MB live at the end, {unwind_failed:.0f}MB of it with a failed unwind."]
    columns = f"{'growth':>7} {'first':>6} {'peak':>6} {'last':>6}  allocator"

    def row(group: dict) -> str:
        values = group["series_mb"]
        return f"{growth(values):+7.0f} {values[0]:6.0f} {max(values):6.0f} {values[-1]:6.0f}  {group['name']}"

    by_growth = sorted(native["groups"], key=lambda group: growth(group["series_mb"]), reverse=True)[:ALLOCATOR_ROWS]
    by_size = sorted(native["groups"], key=lambda group: group["series_mb"][-1], reverse=True)[:ALLOCATOR_ROWS]
    out += ["", "Growth over the run (MB, median of the last third minus the first third)", columns]
    out += [row(group) for group in by_growth]
    out += ["", "Largest at the end (MB)", columns]
    out += [row(group) for group in by_size]
    return out


def render_report(run: dict) -> str:
    """Write the whole report, answer first.

    Args:
        run: The run's `started`, `ended`, `samples`, `log_lines`, and `native` data.

    Returns:
        The report's text.
    """
    year = run["started"].year
    year_lines = [(when, line) for line in run["log_lines"] if (when := log_time(line, year))]
    out = ["Memory report", f"Ended: {run['ended']}"]
    out += render_samples(run, year_lines)
    warnings = profiler_warnings(run["log_lines"])
    # The log's wording can change, so also check the snapshots themselves for a profile that stopped early.
    if run["native"] and run["samples"] and (run["samples"][-1]["time"] - run["native"]["times"][-1]).total_seconds() > PROFILE_STALE_SECONDS:
        warnings.append(f"{run['native']['times'][-1]:%H:%M:%S} Native profile has no snapshots after this time, so later growth is missing from it.")
    if warnings:
        out += ["", "Warnings"] + warnings
    out.append("")
    out += render_native(run["native"]) if run["native"] else ["No native profile in this recording, so memory cannot be tied to code."]
    return "\n".join(out) + "\n"


def load_run(folder: pathlib.Path) -> dict:
    """Read a recording's files back.

    Args:
        folder: The recording's folder.

    Returns:
        The run's data for `render_report`.

    Raises:
        SystemExit: If the folder is not a recording.
    """
    meta_path = folder / "meta.json"
    if not meta_path.exists():
        raise SystemExit(f"{folder} has no meta.json, so it is not a recording.")
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    started = dt.datetime.fromisoformat(meta["device_start"])
    samples = []
    samples_path = folder / "samples.csv"
    if samples_path.exists():
        with samples_path.open(encoding="utf-8", newline="") as handle:
            for row in csv.DictReader(handle):
                sample = {field: int(row[field] or 0) for field in SAMPLE_FIELDS if field != "time"}
                sample["time"] = dt.datetime.fromisoformat(row["time"])
                samples.append(sample)
    log_path = folder / "logcat.txt"
    log_lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines() if log_path.exists() else []
    trace = folder / "trace.pftrace"
    native = load_native(trace, started, meta["uptime_start"]) if trace.exists() else None
    return {"started": started, "ended": meta.get("ended", "Recording did not finish cleanly."), "samples": samples, "log_lines": log_lines, "native": native}


def write_report(folder: pathlib.Path) -> pathlib.Path:
    """Write `report.txt` for a recording.

    Args:
        folder: The recording's folder.

    Returns:
        The report's path.
    """
    report = folder / "report.txt"
    report.write_text(render_report(load_run(folder)), encoding="utf-8")
    return report


# ////////////////////////////////////////////////////////////////////////////////////////////////////
# ////////////////////////////////////////////////////////////////////////////////////////////////////
# Device


class Device:
    """The adb commands this tool runs."""

    def __init__(self, serial: str) -> None:
        """Find adb and remember which device to use.

        Args:
            serial: The device's adb address.

        Raises:
            SystemExit: If adb cannot be found.
        """
        sdk_adb = pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        adb = os.environ.get("UMA_ADB") or shutil.which("adb") or (str(sdk_adb) if sdk_adb.exists() else None)
        if not adb:
            raise SystemExit("adb was not found. Put it on PATH or set UMA_ADB.")
        self.base = [adb, "-s", serial]

    def run(self, *args: str, timeout: float = 60, stdin: bytes | None = None) -> subprocess.CompletedProcess:
        """Run an adb command.

        Args:
            *args: The arguments after the device selection.
            timeout: Seconds before giving up.
            stdin: Bytes to send to the command.

        Returns:
            The finished command, with output as text.
        """
        result = subprocess.run([*self.base, *args], input=stdin, capture_output=True, timeout=timeout)
        return subprocess.CompletedProcess(result.args, result.returncode, result.stdout.decode("utf-8", "replace").replace("\r", ""), result.stderr.decode("utf-8", "replace").replace("\r", ""))

    def shell(self, command: str, timeout: float = 60) -> str:
        """Run a shell command on the device.

        Args:
            command: The command.
            timeout: Seconds before giving up.

        Returns:
            The command's output.
        """
        return self.run("shell", command, timeout=timeout).stdout

    def pid_of(self, name: str) -> int | None:
        """Find a process by name.

        Args:
            name: The process name.

        Returns:
            Its pid, or None if it is not running.

        Raises:
            ConnectionError: If the device did not answer, so nothing is known about the process.
        """
        output = self.shell(f"pidof {name}; echo answered").split()
        if "answered" not in output:
            raise ConnectionError("The device did not answer.")
        return int(output[0]) if output[0] != "answered" else None

    def sample(self) -> dict | None:
        """Take one memory sample. The time comes from the device so it lines up with the log.

        Returns:
            The sample, or None if the app has no process to read.
        """
        output = self.shell(f"date '+%Y-%m-%d %H:%M:%S'; dumpsys meminfo {PACKAGE}; grep MemAvailable /proc/meminfo", timeout=90)
        memory = parse_meminfo(output)
        if not memory:
            return None
        available = MEM_AVAILABLE.search(output)
        return {"time": output.splitlines()[0].strip(), "avail_kb": int(available.group(1)) if available else 0, **memory}


def mirror_log(device: Device, path: pathlib.Path, since: str, not_profileable: threading.Event) -> subprocess.Popen:
    """Copy the device log to a file, flushing each line so it survives anything that follows.

    Args:
        device: The device.
        path: The file to write.
        since: The device time to start from, in logcat's format.
        not_profileable: Set if the log says this build cannot be profiled.

    Returns:
        The running logcat process.
    """
    process = subprocess.Popen([*device.base, "shell", f"logcat -v threadtime -b main,system,crash,events -T '{since}'"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    def pump() -> None:
        with path.open("w", encoding="utf-8") as handle:
            for raw in process.stdout:
                line = raw.decode("utf-8", "replace").rstrip()
                if "is not profileable" in line:
                    not_profileable.set()
                if keep_log_line(line):
                    handle.write(line + "\n")
                    handle.flush()

    threading.Thread(target=pump, daemon=True).start()
    return process


def start_recording(device: Device, device_trace: str) -> None:
    """Start the system recording from the checked-in config.

    Args:
        device: The device.
        device_trace: Where the device writes the trace.

    Raises:
        SystemExit: If Perfetto refuses to start.
    """
    config = CONFIG_PATH.read_bytes().replace(b"\r\n", b"\n")
    result = device.run("shell", f"perfetto --txt -c - -o {device_trace} --background", stdin=config)
    if not device.pid_of("perfetto"):
        raise SystemExit(f"Perfetto did not start:\n{result.stdout}\n{result.stderr}")


def stop_recording(device: Device) -> None:
    """Ask the recording to flush and exit, then wait for it.

    Args:
        device: The device.
    """
    pid = device.pid_of("perfetto")
    if pid:
        device.shell(f"kill -TERM {pid}")
    for _ in range(30):
        if not device.pid_of("perfetto"):
            return
        time.sleep(2)
    print("Warning: Perfetto was still running after a minute, so the trace may be cut short.", file=sys.stderr)


def describe_end(device: Device, pid: int, app_died: bool) -> str:
    """Say why the recording ended and keep the system's exit record.

    Args:
        device: The device.
        pid: The app process that was being recorded.
        app_died: True if that process is gone.

    Returns:
        One line for the report.
    """
    if not app_died:
        return "Recording ended with the app still running."
    record = parse_exit_info(device.shell(f"dumpsys activity exit-info {PACKAGE}"), pid)
    if not record:
        return f"App process {pid} disappeared with no exit record."
    return f"App died: {record['reason']} ({record['subreason']}) at {record['timestamp']}"


def watch(device: Device, pid: int, interval: float, path: pathlib.Path, not_profileable: threading.Event) -> bool:
    """Sample memory until the recording should end.

    Args:
        device: The device.
        pid: The app process being recorded.
        interval: Seconds between samples.
        path: The samples file to write.
        not_profileable: Set if the log says this build cannot be profiled.

    Returns:
        True if the app process died.
    """
    failures = 0
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=SAMPLE_FIELDS)
        writer.writeheader()
        while not not_profileable.is_set():
            try:
                if not device.pid_of("perfetto"):
                    print("The recording ended.", flush=True)
                    return False
                current = device.pid_of(PACKAGE)
                if current != pid:
                    print(f"App process {pid} is gone (now {current}).", flush=True)
                    return True
                sample = device.sample()
                failures = 0
                if sample:
                    writer.writerow(sample)
                    handle.flush()
            except (subprocess.TimeoutExpired, ConnectionError):
                failures += 1
                if failures >= MAX_ADB_FAILURES:
                    print("The device stopped answering.", file=sys.stderr)
                    return False
            time.sleep(interval)
    print("This build cannot be profiled. Rebuild with ORG_GRADLE_PROJECT_uaaProfileable=true yarn build.", file=sys.stderr)
    return False


def record(args: argparse.Namespace) -> None:
    """Record until the app dies, `stop` is called, or Ctrl-C, then write the report.

    Args:
        args: The command line.

    Raises:
        SystemExit: If the app is not running, or a recording is already going.
    """
    device = Device(args.serial)
    pid = device.pid_of(PACKAGE)
    if not pid:
        raise SystemExit("The app is not running. Open it and press Start first, then record before pressing the overlay's play button.")
    if device.pid_of("perfetto"):
        raise SystemExit("A recording is already running on the device. End it with `stop` first.")
    if device.shell("getprop persist.heapprofd.enable").strip() != "1":
        device.shell("setprop persist.heapprofd.enable 1")

    stamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    folder = pathlib.Path(args.out) if args.out else pathlib.Path(tempfile.gettempdir()) / "uma_mem" / stamp
    folder.mkdir(parents=True, exist_ok=True)
    device_trace = f"{DEVICE_TRACE_DIR}/uma_mem_{stamp}.pftrace"

    clock = device.shell("date '+%Y-%m-%d %H:%M:%S.000'; cut -d' ' -f1 /proc/uptime").split("\n")
    device_start = dt.datetime.fromisoformat(clock[0].strip())
    meta = {"device_start": clock[0].strip(), "uptime_start": float(clock[1]), "pid": pid, "serial": args.serial}
    (folder / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"Recording pid {pid} to {folder}", flush=True)

    not_profileable = threading.Event()
    logcat = mirror_log(device, folder / "logcat.txt", device_start.strftime("%m-%d %H:%M:%S.000"), not_profileable)
    start_recording(device, device_trace)

    app_died = False
    try:
        app_died = watch(device, pid, args.interval, folder / "samples.csv", not_profileable)
    except KeyboardInterrupt:
        print("Stopping...", flush=True)
    finally:
        logcat.terminate()
        finish_recording(device, folder, meta, device_trace, app_died)


def finish_recording(device: Device, folder: pathlib.Path, meta: dict, device_trace: str, app_died: bool) -> None:
    """Stop the recording, bring the trace back, and write the report.

    Args:
        device: The device.
        folder: The recording's folder.
        meta: The recording's details, updated with how it ended.
        device_trace: Where the device wrote the trace.
        app_died: True if the app process is gone.
    """
    local_trace = folder / "trace.pftrace"
    try:
        stop_recording(device)
        meta["ended"] = describe_end(device, meta["pid"], app_died)
        pulled = device.run("pull", device_trace, str(local_trace), timeout=600)
        if pulled.returncode == 0 and local_trace.exists() and local_trace.stat().st_size > 0:
            device.shell(f"rm -f {device_trace}")
        else:
            print(f"Could not pull the trace, so it is still on the device at {device_trace}: {pulled.stderr.strip()}", file=sys.stderr)
    except (subprocess.TimeoutExpired, ConnectionError):
        # The samples and log are already on the PC, so still report them.
        meta["ended"] = f"Lost contact with the device. The trace may still be on it at {device_trace}."
    (folder / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(f"Report: {write_report(folder)}", flush=True)


def main() -> None:
    """Run the command line."""
    parser = argparse.ArgumentParser(description="Record the bot's memory over a whole run and report what grew and when.")
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("record", help="Record until the app dies, stop is called, or Ctrl-C.")
    start.add_argument("--out", help="Output folder. Defaults to a timestamped folder in the system temp directory.")
    start.add_argument("--interval", type=float, default=30, help="Seconds between memory samples.")
    start.add_argument("--serial", default=DEFAULT_SERIAL, help="The device's adb address.")
    end = commands.add_parser("stop", help="End a running recording, so the record command finishes and writes its report.")
    end.add_argument("--serial", default=DEFAULT_SERIAL, help="The device's adb address.")
    report = commands.add_parser("report", help="Write the report again for a recording folder.")
    report.add_argument("folder", help="The recording's folder.")
    args = parser.parse_args()

    if args.command == "record":
        record(args)
    elif args.command == "stop":
        stop_recording(Device(args.serial))
    else:
        print(write_report(pathlib.Path(args.folder)).read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
