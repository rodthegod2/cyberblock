"""Megaplan 9-F1 measurement tools: tools/loadhost.py (the busy street a load host streams) and tools/perfcheck.py (its report)."""
import math

from tools import loadhost, perfcheck


def test_city_columns_are_a_street_with_buildings():
    assert loadhost.city_column(5, 0) == (63, 63, loadhost.ROAD)
    assert loadhost.city_column(5, -6)[2] == loadhost.SIDEWALK
    tops = {loadhost.city_column(x, z)[1] for x in range(0, 64) for z in range(7, 40)}
    assert min(tops) == 63 and 65 <= max(tops) <= 63 + loadhost.MAX_STOREYS
    assert loadhost.city_column(7, 10)[2] == loadhost.ALLEY  # x % 8 == 7: an alley between two lots
    assert loadhost.city_column(3, 20) == loadhost.city_column(3, 20)  # deterministic


def test_area_is_a_disc_round_v():
    cols = loadhost.area(0.5, 0, 10)
    assert (0, 0) in cols and (10, 0) in cols and (0, 10) in cols and (8, 8) not in cols
    assert abs(len(cols) - math.pi * 100) < 40


def test_messages_split_and_carry_codes():
    qs = loadhost.quads(loadhost.area(0.5, 0, 20), 0)
    ground = loadhost.messages("ground", qs)
    assert len(ground) == math.ceil(len(qs) / loadhost.QUADS_PER_MESSAGE)
    assert all(len(m["c"]) == 4 * len(m["m"]) <= 4 * loadhost.QUADS_PER_MESSAGE for m in ground)
    assert all("m" not in m for m in loadhost.messages("unground", qs))


def test_pacer_keeps_under_the_guard():
    p = loadhost.Pacer(cells_per_s=100, msgs_per_s=3)
    p.queue = [{"t": "ground", "c": [0, 0, 0, 39]} for _ in range(10)]  # 40 cells each
    first = p.due(0.0)
    assert len(first) == 2  # 100 cells: two messages of 40
    assert p.due(0.1) == [] and len(p.due(0.9)) == 2


def test_walk_streams_new_columns_and_ungrounds_old_ones():
    s = loadhost.Street(0)
    s.walk(0.0)
    first = list(s.pacer.queue)
    assert first and all(m["t"] == "ground" for m in first)
    s.pacer.queue.clear()
    s.walk(0.1)  # same metre: nothing new
    assert s.pacer.queue == []
    s.walk(2.0)  # 8 m east
    kinds = {m["t"] for m in s.pacer.queue}
    assert kinds == {"ground", "unground"}
    assert s.restart() == [{"t": "clear"}] and s.sent == set()


def test_figures_and_devices_stay_inside_the_protocol_limits():
    s = loadhost.Street(0)
    npcs = s.npcs(3.0)["list"]
    assert len(npcs) == 48 and len({e["id"] for e in npcs}) == 48
    assert all(abs(e["p"][0] - s.vx(3.0)) <= loadhost.RADIUS for e in npcs)
    devices = s.devices(3.0)["list"]
    assert len(devices) == 64 and len({d["id"] for d in devices}) == 64
    assert all(abs(d["p"][0] - s.vx(3.0)) <= 48 for d in devices)
    change = s.devstate(3.0, 5)
    assert change["id"] in {d["id"] for d in devices} and change["by"] == "npc"


def test_scaled_view_and_prefs_scale_follow_the_dll():
    assert loadhost.scaled_view((1920, 1080), 1.0) == (1920, 1080)
    assert loadhost.scaled_view((1920, 1080), 0.5) == (960, 540)
    assert loadhost.scaled_view((1366, 768), 0.75) == (1025, 576)  # 1024.5 rounds half up
    assert loadhost.prefs_scale({"scale": 0.2}, 1.0) == 0.5 and loadhost.prefs_scale({"scale": 3}, 0.5) == 1.0
    assert loadhost.prefs_scale({"scale": "x"}, 0.75) == 0.75 and loadhost.prefs_scale({}, 0.75) == 0.75
    assert loadhost.prefs_scale({"scale": True}, 0.75) == 0.75 and loadhost.prefs_scale({"scale": float("nan")}, 0.6) == 0.6


def test_tick_query_parsing_and_summary():
    text = "Target tick rate: 20.0 per second.\nAverage time per tick: 3.41ms (Target: 50.0ms)Percentiles: P50: 2.9ms P95: 6.2ms P99: 11.0ms. Sample: 100"
    s = perfcheck.parse_tick_query(text)
    assert s == {"avg": 3.41, "p50": 2.9, "p95": 6.2, "p99": 11.0, "sample": 100}
    assert perfcheck.parse_tick_query("The game is frozen") is None
    summary = perfcheck.summarize_ticks([s, {**s, "avg": 5.0, "p99": 20.0}])
    assert summary == {"samples": 2, "avg_ms": 4.21, "p50_ms": 2.9, "p95_max_ms": 6.2, "p99_max_ms": 20.0}


def test_report_lines_are_read_back():
    assert perfcheck.last_loads('[CbHost1] load: {"t": 1}\n[CbHost1] load: {"t": 2}')["CbHost1"] == {"t": 2}
    line = "frame export: 960x540, 59.8 fps, readback 12.1 ms avg (p95 20.4 ms), copy 0.81 ms avg (p95 1.20 ms)"
    assert perfcheck.export_lines(line) == [{"w": 960, "h": 540, "fps": 59.8, "readback_ms": 12.1, "readback_p95_ms": 20.4, "copy_ms": 0.81}]
    fig = "figures: 48.0 drawn of 96.0 near (max 48, cap 48), 20.5 animated; client 140 fps"
    assert perfcheck.figure_lines(fig) == [{"drawn": 48.0, "near": 96.0, "max": 48, "cap": "48", "animated": 20.5, "fps": 140}]
    assert perfcheck.BUDGET_LINE.findall("x\n[Server thread/INFO] (cyberblock) budget: last 60 s: replica 3 messages\ny") == ["budget: last 60 s: replica 3 messages"]
    assert perfcheck.relay_drops("relay: CbHost1 dropped ground x 3 (rate)\nrelay: CbHost1 dropped ground x 2 (rate)") == {"ground": 5}


def test_perf_ports_never_touch_the_real_link():
    ports = perfcheck.PerfPorts()
    ports.check()
    assert ports.client("CbHost1") == 25832 and ports.client("CbGuest3") == 0 and ports.server("anything") == 25992
    assert ports.frame("CbHost2") == "Local\\CyberblockFrame9FH2" and ports.frame("CbGuest4") == "Local\\CyberblockFrame9FG4"
    assert 25600 not in perfcheck.HOST_PORTS.values()
