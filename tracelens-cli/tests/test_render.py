from tracelens_cli.render import render


def _event(seq, etype, thread="main", cls="com.example.Foo", method=None, line=-1,
           nanos=0, message=None, attrs=None):
    e = {
        "seq": seq,
        "epochMillis": 1000,
        "nanoTime": nanos,
        "thread": thread,
        "type": etype,
        "className": cls,
        "line": line,
    }
    if method:
        e["methodName"] = method
    if message:
        e["message"] = message
    if attrs:
        e["attributes"] = attrs
    return e


def _trace(events):
    return {
        "traceId": "abc12345-dead-beef",
        "lensId": "my-flow",
        "user": "me@chargebee.com",
        "entryChannel": "HTTP",
        "status": "COMPLETED",
        "startedAt": 1000,
        "startNanos": 0,
        "durationMs": 12,
        "eventCount": len(events),
        "truncated": False,
        "attributes": {"httpStatus": "200"},
        "events": events,
    }


def test_renders_call_tree_with_indentation():
    trace = _trace([
        _event(1, "METHOD_ENTER", method="doFilter", nanos=0),
        _event(2, "LINE", line=32, nanos=1_000_000),
        _event(3, "METHOD_ENTER", method="retrieve", nanos=2_000_000),
        _event(4, "METHOD_EXIT", method="retrieve", nanos=3_000_000),
        _event(5, "METHOD_EXIT", method="doFilter", nanos=4_000_000),
    ])
    out = render(trace, show_source=False)

    assert 'trace abc12345… "my-flow"' in out
    assert "http=200" in out
    assert "▶ Foo.doFilter" in out
    assert "▶ Foo.retrieve" in out
    # nested enter is indented deeper than the outer one
    lines = out.splitlines()
    outer = next(l for l in lines if "▶ Foo.doFilter" in l)
    inner = next(l for l in lines if "▶ Foo.retrieve" in l)
    assert inner.index("▶") > outer.index("▶")


def test_collapses_repeated_events():
    events = [_event(1, "METHOD_ENTER", method="loop", nanos=0)]
    for i in range(5):
        events.append(_event(2 + i, "LINE", line=10, nanos=i))
    events.append(_event(7, "METHOD_EXIT", method="loop", nanos=9))
    out = render(_trace(events), show_source=False, collapse=True)
    assert "(x5)" in out

    out_full = render(_trace(events), show_source=False, collapse=False)
    assert "(x5)" not in out_full
    assert out_full.count("Foo:10") == 5


def test_thread_lane_banner_on_hop():
    trace = _trace([
        _event(1, "LINE", thread="http-nio-8082-exec-3", line=1, nanos=0),
        _event(2, "LINE", thread="emsAsync-302", line=2, nanos=1),
    ])
    out = render(trace, show_source=False)
    assert "── thread: http-exec-3 " in out
    assert "── thread: emsAsync-302 " in out


def test_exception_rendering():
    trace = _trace([
        _event(1, "EXCEPTION", method="boom", nanos=0,
               message="bad things", attrs={"exceptionType": "IllegalStateException"}),
    ])
    out = render(trace, show_source=False)
    assert "✖ Foo.boom threw IllegalStateException: bad things" in out
