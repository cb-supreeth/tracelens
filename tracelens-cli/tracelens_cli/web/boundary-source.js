/* Locate observed calling context for one retained boundary. No I/O or payload projection. */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory(require("./dependency-ui.js"));
  else root.TraceLensBoundarySource = factory(root.TraceLensDependencies);
})(typeof globalThis !== "undefined" ? globalThis : this, function (dependencies) {
  "use strict";
  const MAX_EVENTS = 100000, MAX_DEPTH = 512, MAX_THREADS = 64;
  const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
  const object = value => value !== null && typeof value === "object" && !Array.isArray(value);
  const key = value => typeof value === "string" && value.length <= 4096 && value.trim() ? value : null;
  const className = value => typeof value === "string" && value.length <= 4096 &&
    /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$/.test(value) ? value : null;
  const methodName = value => typeof value === "string" && value.length <= 256 &&
    /^(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>)$/.test(value) ? value : null;
  const positive = value => {
    if (typeof value === "string" && value.length <= 19 && /^[0-9]+$/.test(value)) value = Number(value);
    return typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : null;
  };
  const lineNumber = value => typeof value === "number" && Number.isSafeInteger(value) &&
    value > 0 && value <= 2147483647 ? value : null;
  const CONTEXT_NOTE = "Observed calling context only, not proof of an exact callsite or direct caller. " +
    "Thread names are not unique thread IDs; local source may differ from the captured build.";
  const STRONG_CONTEXT_NOTE = "Observed calling context only, not proof of an exact callsite or direct caller. " +
    "Runtime thread identity separates observed lanes; local source may differ from the captured build.";
  const NO_SOURCE_NOTE = "The boundary event is retained, but no reliable preceding source line was observed " +
    "on its active instrumented frame. Open the boundary in the timeline; no exact callsite is inferred.";
  function unavailable(reason) {
    return {eventIndex: null, sourceIndex: null, coordinate: null, methodName: null,
      note: "The exact retained boundary could not be identified safely. No calling source is inferred.", reason};
  }
  function eventOnly(eventIndex, reason) {
    return {eventIndex, sourceIndex: null, coordinate: null, methodName: null, note: NO_SOURCE_NOTE, reason};
  }
  function resolve(trace, node) {
    if (!object(trace) || !object(node) || !Array.isArray(trace.events)) return unavailable("invalid-input");
    if (!dependencies || typeof dependencies.boundaryMetadata !== "function") return unavailable("metadata-unavailable");
    const events = trace.events;
    // Looking only at a prefix cannot establish that a sequence is globally unique.
    if (events.length > MAX_EVENTS) return unavailable("event-limit");
    const targetSeq = positive(node.eventSeq);
    if (targetSeq === null) return unavailable("invalid-event-sequence");
    for (const field of ["segmentId", "serviceInstanceId", "service"]) {
      if (own(node, field) && node[field] != null &&
          (key(node[field]) === null || node[field] !== trace[field])) return unavailable("segment-identity-mismatch");
    }
    if (own(node, "kind") && node.kind !== "dependency-call") return unavailable("not-dependency-boundary");
    let target = -1;
    for (let index = 0; index < events.length; index++) {
      if (object(events[index]) && positive(events[index].seq) === targetSeq) {
        if (target !== -1) return unavailable("ambiguous-event-sequence");
        target = index;
      }
    }
    if (target === -1) return unavailable("event-not-retained");
    const boundary = events[target], metadata = dependencies.boundaryMetadata(boundary);
    if (!metadata) return unavailable("not-dependency-boundary");
    if (own(node, "thread") && node.thread != null &&
        (key(node.thread) === null || node.thread !== boundary.thread)) return unavailable("thread-mismatch");
    if (node.threadId != null && (typeof node.threadId !== "number" || positive(node.threadId) === null || node.threadId !== boundary.threadId)) return unavailable("thread-id-mismatch");
    // Compare the graph's canonical, payload-free fields with a fresh projection.
    // A malformed supplied value must not be normalized into an accidental match.
    for (const field of ["boundaryKind", "system", "direction", "destination", "operation", "status", "evidenceKind"]) {
      if (own(node, field) && node[field] !== metadata[field]) return unavailable("boundary-metadata-mismatch");
    }

    if (metadata.evidenceKind === "clickhouse-query") return {...eventOnly(target, "native-async-completion"),
      note: "Native ClickHouse evidence is recorded at response-future completion. The completion thread is not a reliable calling-code location; no source line is inferred."};
    return callingContext(events, target, false);
  }

  // HTTP calls are upserted metadata, not timeline events. Only a start-time
  // anchor retained by the agent can locate their caller; wall-clock proximity
  // and the response/completion thread are deliberately not used.
  function resolveHttp(trace, call) {
    if (!object(trace) || !object(call) || !Array.isArray(trace.events)) return unavailable("invalid-input");
    const missing = reason => ({...unavailable(reason), note:
      "The HTTP call was recorded, but its calling location was not captured. Reproduce the request with the updated agent and method/line capture enabled. No source line is guessed from timing or the destination service."});
    if (call.callingContextStatus !== "recorded") return missing("http-calling-context-not-recorded");
    const targetSeq = positive(call.callingEventSeq), thread = key(call.callingThread), threadId = positive(call.callingThreadId);
    if (targetSeq === null || (call.callingThreadId != null ? threadId === null : thread === null)) return unavailable("invalid-http-calling-context");
    const events = trace.events;
    if (events.length > MAX_EVENTS) return unavailable("event-limit");
    let target = -1;
    for (let index = 0; index < events.length; index++) {
      if (object(events[index]) && positive(events[index].seq) === targetSeq) {
        if (target !== -1) return unavailable("ambiguous-event-sequence");
        target = index;
      }
    }
    if (target === -1) return unavailable("event-not-retained");
    if (threadId !== null ? events[target].threadId !== threadId : events[target].threadId != null || events[target].thread !== thread) return unavailable("thread-mismatch");
    const context = callingContext(events, target, true);
    return {...context, note: context.coordinate ? context.note + " This context was recorded when the outgoing HTTP call started."
      : "The HTTP call's start-time context is retained, but no reliable source line was observed on its active instrumented frame. No exact callsite is inferred."};
  }

  function callingContext(events, target, includeAnchor) {
    const lanes = new Map();
    let previousSeq = null;
    const reset = () => { for (const stack of lanes.values()) stack.length = 0; };
    for (let index = 0; index <= target; index++) {
      const event = events[index];
      if (!object(event)) { reset(); previousSeq = null; continue; }
      const seq = positive(event.seq);
      if (event.detailGapBefore === true || seq === null || (previousSeq !== null && seq !== previousSeq + 1)) reset();
      previousSeq = seq;
      const name = key(event.thread), threadId = typeof event.threadId === "number" ? positive(event.threadId) : null;
      const thread = event.threadId != null ? threadId === null ? null : "id:" + threadId : name === null ? null : "name:" + name;
      if (thread === null) {
        // A threadless method/line may belong to any lane; do not keep stale context.
        reset();
        if (index === target) return eventOnly(target, "missing-thread-identity");
        continue;
      }
      if (!lanes.has(thread)) {
        if (lanes.size >= MAX_THREADS) return eventOnly(target, "thread-limit");
        lanes.set(thread, []);
      }
      const stack = lanes.get(thread);
      const cls = className(event.className), method = methodName(event.methodName);
      const descriptor = dependencies?.methodDescriptor?.(event.methodDescriptor) ?? null;
      const invalidDescriptor = event.methodDescriptor != null && descriptor === null;
      const currentContext = () => {
        const frame = stack[stack.length - 1];
        if (!frame || !frame.line) return eventOnly(target, "source-not-observed");
        return {eventIndex: target, sourceIndex: frame.line.index,
          coordinate: {className: frame.className, line: frame.line.number},
          methodName: frame.methodName, ...(frame.methodDescriptor ? {methodDescriptor: frame.methodDescriptor} : {}),
          note: threadId === null ? CONTEXT_NOTE : STRONG_CONTEXT_NOTE};
      };
      if (index === target && !includeAnchor) return currentContext();
      if (event.type === "METHOD_ENTER") {
        if (!cls || !method || seq === null || invalidDescriptor) { stack.length = 0; continue; }
        if (stack.length >= MAX_DEPTH) return eventOnly(target, "depth-limit");
        stack.push({className: cls, methodName: method, methodDescriptor: descriptor, line: null});
      } else if (event.type === "METHOD_EXIT" || event.type === "EXCEPTION") {
        let position = -1;
        if (cls && method && seq !== null && !invalidDescriptor) {
          for (let offset = stack.length - 1; offset >= 0; offset--) {
            if (stack[offset].className === cls && stack[offset].methodName === method && stack[offset].methodDescriptor === descriptor) { position = offset; break; }
          }
        }
        if (position === -1) stack.length = 0;
        else stack.length = position; // A matching exception exits its frame too.
      } else if (event.type === "LINE") {
        const frame = stack[stack.length - 1], number = lineNumber(event.line);
        if (frame && cls === frame.className && number !== null && seq !== null) {
          frame.line = {index, number};
        }
      }
      if (index === target) return currentContext();
    }
    return eventOnly(target, "source-not-observed");
  }
  return Object.freeze({resolve, resolveHttp});
});
