/* Dependency summaries and explicitly opted-in JDBC evidence. No request replay. */
(function (root, factory) {
  const api = factory(typeof module === "object" && module.exports ? require("./sql-values.js") : root.TraceLensSqlValues,
    typeof module === "object" && module.exports ? require("./evidence-copy.js") : root.TraceLensEvidenceCopy);
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensDependencies = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (sqlValues, evidenceCopy) {
  "use strict";
  const text = value => typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ").slice(0, 256) : "";
  // Keep raw-event projection aligned with Dependencies.java. Clipping free text
  // is not a substitute for rejecting payload-like metadata or URL credentials.
  const token = (value, pattern = /^[A-Za-z0-9_$][A-Za-z0-9_.$:-]{0,127}$/) =>
    typeof value === "string" && value.length <= 128 && !/[^\x20-\x7e]/.test(value) && pattern.test(value) ? value : null;
  const operation = value => token(value, /^[A-Za-z_$][A-Za-z0-9_.$:-]{0,127}$/);
  const displayName = value => token(value, /^[A-Za-z0-9][A-Za-z0-9 _.:-]{0,127}$/);
  const cacheWrapperClass = "com.chargebee.ems.config.cache.ValkeyCacheService";
  const cacheWrapperClients = new Map([["get", "get"], ["put", "set"], ["evict", "delete"]]);
  const cacheWrapper = event => event?.type === "METHOD_ENTER" && event.className === cacheWrapperClass
    && cacheWrapperClients.has(event.methodName);
  function methodDescriptor(value) {
    if (typeof value !== "string" || value.length < 3 || value.length > 2048 || value[0] !== "(") return null;
    const type = start => {
      let position = start, dimensions = 0;
      while (value[position] === "[") { position++; if (++dimensions > 255) return -1; }
      if (position >= value.length) return -1;
      if ("BCDFIJSZ".includes(value[position])) return position + 1;
      if (value[position++] !== "L") return -1;
      const end = value.indexOf(";", position);
      if (end < 0 || !value.slice(position, end).split("/").every(part => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(part))) return -1;
      return end + 1;
    };
    let position = 1;
    while (position < value.length && value[position] !== ")") { position = type(position); if (position < 0) return null; }
    if (position >= value.length - 1) return null;
    position++;
    return (value[position] === "V" ? position + 1 : type(position)) === value.length ? value : null;
  }
  const jdbcOperations = new Set(["execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"]);
  const finite = value => (typeof value === "number" || typeof value === "string" && value.length <= 32 && /^\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value))
    && Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : null;
  const boundedInteger = (value, max) => {
    if (typeof value === "string" && (value.length > 10 || !/^\d+$/.test(value))) return null;
    const result = finite(value);
    return Number.isSafeInteger(result) && result <= max ? result : null;
  };
  const parameterTypes = new Set("Null Boolean Byte Short Int Long Float Double BigDecimal String Bytes Date Time Timestamp AsciiStream UnicodeStream BinaryStream Object CharacterStream Ref Blob Clob Array URL RowId NString NCharacterStream NClob SQLXML".split(" "));
  const nativeParameterTypes = new Set("Null Boolean Byte Short Int Long Float Double BigDecimal BigInteger String Character UUID Date Time Timestamp LocalDate LocalTime LocalDateTime OffsetTime OffsetDateTime Instant Object".split(" "));
  function parameterDetails(value, named = false) {
    const keys = ["sqlParameters", "sqlParameterSets", "sqlParametersStatus", "sqlParametersTruncated", "sqlParameterSetsOmitted"];
    if (!keys.some(key => value[key] != null)) return {};
    const validStatus = ["captured", "unavailable", "batch", "not-applicable"].includes(value.sqlParametersStatus);
    let status = validStatus ? value.sqlParametersStatus : "unavailable";
    let truncated = value.sqlParametersTruncated === true || value.sqlParametersTruncated === "true", size = 0;
    const parse = raw => {
      if (raw == null) return null;
      if (typeof raw === "string") { if (raw.length > 8192) throw Error(); raw = JSON.parse(raw); }
      const serialized = JSON.stringify(raw); size += serialized.length;
      if (size > 8192 || !Array.isArray(raw)) throw Error();
      return raw;
    };
    const entries = rows => {
      if (!Array.isArray(rows) || rows.length > 128) throw Error();
      const indices = new Set();
      return rows.map(row => {
        const identity = named ? row?.name : row?.index;
        if (!row || typeof row !== "object" || Array.isArray(row) || (named ? typeof identity !== "string" || !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(identity)
            : !Number.isInteger(identity) || identity < 1 || identity > 128) || indices.has(identity) || !(named ? nativeParameterTypes : parameterTypes).has(row.type)
            || !["captured", "null", "unsupported", "truncated"].includes(row.status)) throw Error();
        indices.add(identity);
        if (["captured", "truncated"].includes(row.status) ? typeof row.value !== "string" || row.value.length > 512 : row.value !== null) throw Error();
        const displayed = row.value === null ? null : row.value
          .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ");
        // Sanitized display text is not the captured value: never reconstruct it
        // as an exact SQL literal, including after a second normalized projection.
        const rowStatus = displayed !== row.value ? "truncated" : row.status;
        if (rowStatus === "truncated") truncated = true;
        return {...(named ? {name: identity} : {index: identity}), type: row.type, status: rowStatus, value: displayed};
      }).sort((a, b) => named ? a.name.localeCompare(b.name) : a.index - b.index);
    };
    try {
      const parameters = parse(value.sqlParameters), sets = parse(value.sqlParameterSets);
      if (named && (sets || status === "batch") || !validStatus && (parameters || sets) || parameters && sets || sets && sets.length > 10
          || status === "not-applicable" && (parameters || sets) || status === "captured" && (!parameters || sets)
          || status === "batch" && (!sets || parameters)) throw Error();
      const omitted = value.sqlParameterSetsOmitted == null ? null : boundedInteger(value.sqlParameterSetsOmitted, 2147483647);
      if (value.sqlParameterSetsOmitted != null && omitted === null) throw Error();
      const normalizedParameters = parameters ? entries(parameters) : null;
      const normalizedSets = sets ? sets.map(entries) : null;
      return {sqlParametersStatus: status, sqlParametersTruncated: truncated || omitted > 0,
        ...(normalizedParameters ? {sqlParameters: normalizedParameters} : {}),
        ...(normalizedSets ? {sqlParameterSets: normalizedSets} : {}), ...(omitted === null ? {} : {sqlParameterSetsOmitted: omitted})};
    } catch (_) {
      return {sqlParametersStatus: "unavailable", sqlParametersTruncated: true};
    }
  }
  // SQL and opt-in parameter values are raw local evidence. Never admit these
  // fields on generic boundaries, query-limit markers or compact summaries.
  function jdbcDetails(value) {
    if (!value || value.boundaryKind !== "database" || !["mysql", "postgresql", "clickhouse"].includes(value.system)
        || value.direction !== "outbound") return null;
    const nativeQuery = value.system === "clickhouse", kind = nativeQuery ? "clickhouse-query" : "jdbc-execution";
    const limitKind = nativeQuery ? "clickhouse-query-limit" : "jdbc-query-limit";
    if (value.evidenceKind === limitKind && value.operation === "query-limit" && value.status === "TRUNCATED") {
      const limit = boundedInteger(value.queryLimit, 1000);
      return {evidenceKind: limitKind, queryLimit: limit > 0 ? limit : null};
    }
    if (value.evidenceKind !== kind || (nativeQuery ? value.operation !== "query" : !jdbcOperations.has(value.operation))
        || !(nativeQuery ? ["OBSERVED", "ERROR", "SUBMITTED"] : ["OBSERVED", "ERROR"]).includes(value.status)) return null;
    let sqlEnd = 16384;
    if (typeof value.sql === "string" && value.sql.length > sqlEnd && /[\ud800-\udbff]/.test(value.sql.charAt(sqlEnd - 1))) sqlEnd--;
    const sql = typeof value.sql === "string" && value.sql.length ? value.sql.slice(0, sqlEnd)
      .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ") : null;
    return {evidenceKind: kind, sql,
      ...(typeof value.sqlPreview === "string" ? {sqlPreview: text(value.sqlPreview),
        sqlDetailsAvailable: value.sqlDetailsAvailable === true || value.sqlDetailsAvailable === "true"} : {}),
      sqlTruncated: value.sqlTruncated === true || value.sqlTruncated === "true"
        || typeof value.sql === "string" && !!value.sql.length && sql !== value.sql,
      sqlSource: (nativeQuery ? ["native-query"] : ["statement", "prepared", "batch", "unavailable"]).includes(value.sqlSource) ? value.sqlSource : null,
      durationMs: nativeQuery && value.status === "SUBMITTED" ? null : finite(value.durationMs), batchSize: boundedInteger(value.batchSize, 2147483647),
      errorType: typeof value.errorType === "string" && value.errorType.length <= 256 &&
        /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$/.test(value.errorType) ? value.errorType : null,
      ...(nativeQuery ? {clientThread: token(value.clientThread, /^[A-Za-z0-9_.$:#/\[\] -]{1,128}$/),
        completionKind: ["response-complete", "immediate-error", "observer-unavailable", "capture-capacity", "unavailable"].includes(value.completionKind) ? value.completionKind : null} : {}),
      ...parameterDetails(value, nativeQuery)};
  }
  const isSqlExecution = value => ["jdbc-execution", "clickhouse-query"].includes(value?.evidenceKind);
  const isSqlLimit = value => ["jdbc-query-limit", "clickhouse-query-limit"].includes(value?.evidenceKind);

  // A bounded lexical summary, not a SQL parser or an execution explanation.
  // Skip literals/comments before looking for top-level operations and tables;
  // nested subqueries must not replace the primary statement's table.
  function sqlPreview(boundary, includeBindings = true) {
    const info = jdbcDetails(boundary);
    if (!info || !isSqlExecution(info)) return "SQL capture limit";
    if (!info.sql) return typeof boundary.sqlPreview === "string" && boundary.sqlPreview
      ? boundary.sqlPreview.slice(0, 256).replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, " ") : "SQL text unavailable";
    if (info.sqlSource === "batch") return `SQL batch${info.batchSize === null ? "" : ` · ${info.batchSize} entries`} · template inventory`;
    const sql = info.sql, tokens = []; let i = 0, depth = 0;
    while (i < sql.length && tokens.length < 2048) {
      const rest = sql.slice(i);
      if (/^\s/.test(rest)) { i++; continue; }
      if (rest.startsWith("--") || rest.startsWith("#") && boundary.system === "mysql") {
        const end = sql.indexOf("\n", i); i = end < 0 ? sql.length : end + 1; continue;
      }
      if (rest.startsWith("/*")) {
        let comments = 1; i += 2;
        while (i < sql.length && comments) {
          if (sql.slice(i, i + 2) === "/*") { comments++; i += 2; }
          else if (sql.slice(i, i + 2) === "*/") { comments--; i += 2; } else i++;
        }
        continue;
      }
      const dollar = /^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$/.exec(rest);
      if (dollar) { const end = sql.indexOf(dollar[0], i + dollar[0].length); i = end < 0 ? sql.length : end + dollar[0].length; continue; }
      const quote = sql[i];
      if (quote === "'" || quote === '"' || quote === '`') {
        const start = i++; let closed = false;
        while (i < sql.length) {
          if (sql[i] === "\\") { i += 2; continue; }
          if (sql[i++] === quote) { if (sql[i] === quote) { i++; continue; } closed = true; break; }
        }
        if (quote !== "'" && closed) tokens.push({text: sql.slice(start, i), depth, identifier: true});
        continue;
      }
      if (quote === "(") { tokens.push({text: quote, depth}); depth++; i++; continue; }
      if (quote === ")") { depth = Math.max(0, depth - 1); tokens.push({text: quote, depth}); i++; continue; }
      const word = /^[A-Za-z_][A-Za-z0-9_$]*/.exec(rest);
      if (word) { tokens.push({text: word[0], depth, identifier: true}); i += word[0].length; }
      else { tokens.push({text: quote, depth}); i++; }
    }
    const top = tokens.filter(item => item.depth === 0), commands = new Set(["SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "SET", "RESET", "CALL", "TRUNCATE"]);
    const leading = top[0]?.text.toUpperCase();
    if (leading === "EXPLAIN") return "EXPLAIN · expand for details";
    const start = leading === "WITH" ? top.findIndex(item => !/^["`]/.test(item.text) && commands.has(item.text.toUpperCase()))
      : commands.has(leading) ? 0 : -1;
    if (start < 0) return "SQL statement · expand for details";
    const command = top[start].text.toUpperCase(), names = [];
    const nameAt = index => {
      let name = "";
      while (index < top.length && top[index]?.identifier) {
        name += top[index++].text;
        if (top[index]?.text !== "." || !top[index + 1]?.identifier) break;
        name += "."; index++;
      }
      return name.slice(0, 128);
    };
    if (["SET", "RESET", "CALL", "UPDATE", "TRUNCATE"].includes(command)) {
      let index = start + 1;
      if (command === "TRUNCATE" && top[index]?.text.toUpperCase() === "TABLE") index++;
      if (command === "UPDATE") while (["ONLY", "LOW_PRIORITY", "IGNORE"].includes(top[index]?.text.toUpperCase())) index++;
      if (command === "SET" && ["LOCAL", "SESSION"].includes(top[index]?.text.toUpperCase())) index++;
      const name = nameAt(index); if (name) names.push(name);
    } else {
      for (let index = start + 1; index < top.length && names.length < 3; index++) {
        if (top[index].text === ";") break;
        const keyword = top[index].text.toUpperCase();
        if ((["SELECT", "DELETE"].includes(command) && ["FROM", "JOIN"].includes(keyword))
            || (["INSERT", "MERGE"].includes(command) && keyword === "INTO")) {
          const name = nameAt(index + (top[index + 1]?.text.toUpperCase() === "ONLY" ? 2 : 1));
          if (name && !names.includes(name)) names.push(name);
        }
      }
      if (!names.length && command === "SELECT" && top[start + 1]?.identifier && top[start + 2]?.text === "(") names.push(`${top[start + 1].text}()`);
    }
    const parameters = info.sqlParameters?.length;
    return [command, names.join(" + ") || "expression / derived result", !includeBindings ? "" : info.sqlParameterSets ? `${info.sqlParameterSets.length} parameter sets`
      : parameters ? `${parameters} parameter binding${parameters === 1 ? "" : "s"}` : ""].filter(Boolean).join(" · ");
  }
  const sqlTitle = boundary => sqlPreview(boundary, false);
  function renderLazyJdbc(document, boundary, load) {
    if (typeof load !== "function" || isSqlLimit(boundary)) return renderJdbc(document, boundary);
    const panel = document.createElement("section"); panel.className = "jdbc-evidence";
    panel.textContent = "Loading retained SQL and bindings…";
    Promise.resolve().then(load).then(full => {
      const result = renderJdbc(document, full);
      if (!result) throw new Error("SQL details were not retained for this observation.");
      panel.replaceChildren(result);
    }).catch(error => { panel.textContent = `${error.message} Full SQL is unavailable; preview text is not offered as a copyable query.`; });
    return panel;
  }
  function renderJdbc(document, boundary) {
    const jdbc = jdbcDetails(boundary);
    if (!jdbc) return null;
    const panel = document.createElement("section"); panel.className = "jdbc-evidence";
    panel.setAttribute("aria-label", isSqlLimit(jdbc) ? "SQL capture limit" : "SQL query evidence");
    const append = (parent, tag, cls, value) => { const element = document.createElement(tag); element.className = cls;
      element.textContent = value; parent.append(element); return element; };
    const add = (tag, cls, value) => append(panel, tag, cls, value);
    const copy = (parent, value, label, standalone = false) => {
      if (!value || !evidenceCopy?.button) return;
      const actions = standalone ? append(parent, "div", "evidence-actions", "") : parent;
      actions.append(evidenceCopy.button(document, value, label));
    };
    if (isSqlLimit(jdbc)) {
      add("p", "jdbc-warning", `SQL capture limit reached${jdbc.queryLimit === null ? "" : ` (${jdbc.queryLimit} executions)`}. Further queries were not retained for this request. This is not an executed query.`);
      return panel;
    }
    const nativeQuery = jdbc.evidenceKind === "clickhouse-query";
    const submitted = nativeQuery && boundary.status === "SUBMITTED";
    add("p", "jdbc-metrics", [submitted ? "Submitted · completion not observed" : boundary.status === "ERROR" ? nativeQuery ? "Query failed" : "Execution threw" : nativeQuery ? "Response future completed" : "Execution returned",
      jdbc.durationMs === null ? "Duration unavailable" : `${jdbc.durationMs.toLocaleString(undefined, {maximumFractionDigits: 3})} ms client-side`,
      jdbc.batchSize === null ? "" : `${jdbc.batchSize} batch entries`, `${nativeQuery ? "Native ClickHouse" : "JDBC"} ${boundary.operation}`, jdbc.errorType || ""].filter(Boolean).join(" · "));
    add("p", "jdbc-warning", "Raw SQL · local opt-in capture. SQL literals and captured parameters may contain sensitive data; review before sharing a snapshot. Result rows are not captured.");
    const preparedBatch = jdbc.sqlSource === "prepared" && jdbc.sqlParameterSets?.length > 0;
    const hasBindings = jdbc.sqlSource === "prepared" && (jdbc.sqlParameters || preparedBatch);
    if (hasBindings) add("p", "jdbc-note", "SQL with captured values is a reconstructed display, not the exact SQL sent over the wire. It is never executed by TraceLens.");
    const sql = (parent, parameters) => {
      const preview = sqlValues?.reconstruct(jdbc, boundary.system, parameters);
      const displayedSql = preview?.sql ?? jdbc.sql;
      const template = !preview?.substituted && jdbc.sqlSource === "prepared";
      const heading = append(parent, "div", "evidence-heading", "");
      append(heading, "h4", "jdbc-sql-heading", preview?.substituted ? "SQL with captured values" : template ? "SQL template" : "Recorded SQL");
      copy(heading, displayedSql, template ? "Copy SQL template" : "Copy SQL");
      append(parent, "pre", preview?.substituted ? "jdbc-sql jdbc-resolved" : "jdbc-sql", displayedSql);
      if (preview && !preview.complete && hasBindings) append(parent, "p", "jdbc-warning", preview.reason || "Some values could not be substituted; unresolved placeholders remain as ?.");
      return preview?.substituted > 0;
    };
    const original = () => {
      const disclosure = add("details", "jdbc-original-template", "");
      append(disclosure, "summary", "", "Original SQL template");
      copy(disclosure, jdbc.sql, "Copy SQL template", true);
      append(disclosure, "pre", "jdbc-sql jdbc-template", jdbc.sql);
    };
    if (jdbc.sql !== null && jdbc.sql.length) {
      if (preparedBatch) original();
      else if (sql(panel)) original();
    } else add("p", "jdbc-unavailable", "SQL text unavailable for this execution; no statement is inferred.");
    if (jdbc.sqlTruncated) add("p", "jdbc-warning", "SQL text truncated or normalized for safe display. This is not the complete original statement or batch.");
    if (jdbc.sqlParameters || jdbc.sqlParameterSets) {
      const table = (parent, values) => {
        const list = document.createElement("dl"); list.className = "jdbc-parameters";
        const displayedParameters = [];
        for (const item of values) {
          const label = document.createElement("dt"), value = document.createElement("dd");
          label.textContent = `${nativeQuery ? item.name : `#${item.index}`} · ${item.type}`;
          value.textContent = item.status === "null" ? "NULL" : item.status === "unsupported" ? "Not captured (unsupported value type)"
            : (item.value === "" ? '""' : item.value) + (item.status === "truncated" ? " … [incomplete]" : "");
          displayedParameters.push(`${label.textContent}: ${value.textContent}`);
          list.append(label, value);
        }
        copy(parent, displayedParameters.join("\n"), "Copy parameters", true);
        parent.append(list);
      };
      if (jdbc.sqlParameters) {
        const disclosure = add("details", "jdbc-parameter-details", "");
        append(disclosure, "summary", "", `Captured parameters · ${jdbc.sqlParameters.length}`);
        table(disclosure, jdbc.sqlParameters);
      }
      for (const [index, values] of (jdbc.sqlParameterSets || []).entries()) {
        const group = document.createElement("details"), summary = document.createElement("summary");
        group.className = "jdbc-parameter-set"; summary.textContent = `Batch entry ${index + 1} · ${values.length} parameter bindings`;
        group.open = index === 0;
        group.append(summary);
        if (preparedBatch && jdbc.sql) sql(group, values);
        const bindings = append(group, "details", "jdbc-parameter-details", "");
        append(bindings, "summary", "", "Captured parameters"); table(bindings, values); panel.append(group);
      }
      if (jdbc.sqlParametersStatus === "unavailable") add("p", "jdbc-warning", "Parameter capture is incomplete; only observed values are shown.");
    } else if (jdbc.sqlSource === "prepared" || jdbc.sqlParametersStatus === "unavailable") {
      add("p", "jdbc-note", nativeQuery ? "Named parameter values were not captured for this query. Values already embedded in SQL are part of the recorded SQL text; request settings are not bindings."
        : "Parameter values were not captured for this execution. Enable captureJdbcParameters and restart the service for new captures; older recordings cannot recover these values.");
    }
    if (jdbc.sqlParametersTruncated) add("p", "jdbc-warning", "Parameter capture/display limit reached or evidence unavailable; this is not a complete set of values.");
    if (jdbc.sqlParameterSetsOmitted) add("p", "jdbc-warning", `${jdbc.sqlParameterSetsOmitted} batch parameter sets were not retained.`);
    add("p", "jdbc-note", submitted ? "The query was submitted, but response completion could not be observed. Duration, success, result consumption and transaction commit are unknown."
      : nativeQuery ? "Native client elapsed time ends at response-future completion, not server execution or result consumption. Completion-thread nesting is not the calling code. No transaction commit or result rows are inferred."
      : "Observed JDBC execution only; a returned call does not establish transaction commit or capture later result fetching.");
    return panel;
  }
  function destination(value) {
    if (typeof value !== "string" || !value || value.length > 2048 || /[^\x21-\x7e]/.test(value)) return null;
    if (value.includes("://")) {
      const parsed = /^(https?):\/\/([^/?#]*)([^?#]*)(?:\?[^#]*)?(?:#.*)?$/i.exec(value);
      if (!parsed) return null;
      const authority = parsed[2].slice(parsed[2].lastIndexOf("@") + 1);
      const hostPort = authority.startsWith("[") ? /^(\[[^\]]+\])(?::([0-9]*))?$/.exec(authority)
        : /^([^:]+)(?::([0-9]*))?$/.exec(authority);
      if (!hostPort) return null;
      if (authority.startsWith("[")) {
        try { new URL(parsed[1] + "://" + authority); } catch (_) { return null; }
      }
      const port = hostPort[2] ? Number(hostPort[2]) : null;
      if (port !== null && (!Number.isInteger(port) || port > 65535)) return null;
      value = parsed[1].toLowerCase() + "://" + hostPort[1].toLowerCase() +
        (port === null ? "" : ":" + port) + parsed[3];
    }
    return value.length <= 512 && /^[A-Za-z0-9_./:$\[\]-]+$/.test(value) ? value : null;
  }
  function boundaryMetadata(event) {
    if (cacheWrapper(event)) return {boundaryKind: "cache", system: "redis", direction: "outbound",
      destination: null, operation: event.methodName, status: "OBSERVED", evidenceKind: "wrapper-entry"};
    if (!event || !["EXTERNAL_CALL", "DB_ROUTE"].includes(event.type)) return null;
    const a = event.attributes && typeof event.attributes === "object" && !Array.isArray(event.attributes) ? event.attributes : {};
    if (event.type === "DB_ROUTE") return {boundaryKind: "database-route", system: "database", direction: "outbound",
      destination: destination(a.route), operation: "route", status: "OBSERVED", evidenceKind: "routing-marker"};
    if (!Object.prototype.hasOwnProperty.call(a, "boundaryKind")) return {boundaryKind: "external", system: token(a.system),
      direction: "outbound", destination: null, operation: operation(a.detail), status: "OBSERVED", evidenceKind: "wrapper-entry"};
    const jdbc = jdbcDetails(a);
    return {boundaryKind: ["messaging", "external", "database", "cache"].includes(a.boundaryKind) ? a.boundaryKind : "external",
      system: token(a.system), direction: ["publish", "consume", "outbound"].includes(a.direction) ? a.direction : null,
      destination: destination(a.destination), operation: operation(a.operation),
      status: ["ATTEMPTED", "SUBMITTED", "OBSERVED", "ERROR"].includes(a.status) || isSqlLimit(jdbc) ? a.status : null,
      evidenceKind: ["client-boundary", "wrapper-entry"].includes(a.evidenceKind) ? a.evidenceKind : null,
      ...(jdbc || {})};
  }
  function boundaryView(boundary, alias) {
    const messaging = boundary.boundaryKind === "messaging", database = boundary.boundaryKind === "database-route";
    const client = boundary.evidenceKind === "client-boundary", wrapper = boundary.evidenceKind === "wrapper-entry";
    const dataClient = client && ["database", "cache"].includes(boundary.boundaryKind);
    const cacheAccess = wrapper && boundary.boundaryKind === "cache", jdbc = jdbcDetails(boundary);
    return {kind: database ? "DB ROUTE" : messaging ? boundary.direction === "consume" ? "QUEUE CONSUME"
      : boundary.direction === "publish" ? "QUEUE PUBLISH" : "MESSAGING BOUNDARY"
      : jdbc ? isSqlLimit(jdbc) ? "SQL CAPTURE LIMIT" : "SQL QUERY"
      : cacheAccess ? "CACHE ACCESS" : wrapper ? "EXTERNAL WRAPPER" : dataClient ? boundary.boundaryKind === "cache" ? "CACHE CALL" : "DATABASE CALL" : "EXTERNAL BOUNDARY",
      direction: boundary.direction, evidenceKind: boundary.evidenceKind,
      destination: [database ? null : boundary.system, displayName(alias) || boundary.destination].filter(Boolean).join(" · ") || (database ? "Unknown route" : "Unknown dependency"),
      operation: database ? "Routing decision" : boundary.operation || (wrapper ? "Wrapper entry" : "Operation unknown"),
      status: boundary.status || "Unknown",
      evidence: jdbc ? isSqlLimit(jdbc) ? "SQL capture limit reached; further query evidence was not retained"
        : jdbc.evidenceKind === "clickhouse-query" ? boundary.status === "SUBMITTED" ? "Native ClickHouse submission; completion and success were not observed."
          : "Native ClickHouse response completion; not server execution time, result consumption or transaction commit."
        : "Observed JDBC execution; returned does not imply transaction commit. Raw SQL is local opt-in evidence."
        : database ? "Pool selection only; no query or network success implied"
        : dataClient ? "Observed client operation; transaction/pipeline completion, cache hit/miss and remote execution are not inferred"
        : cacheAccess ? "Observed cache wrapper invocation; cache hit/miss and remote execution are not established"
        : client ? boundary.direction === "consume" ? "Local consume boundary; message receipt, producer and processing completion are not inferred"
          : "Observed client boundary; delivery and remote processing not implied"
        : wrapper ? "Configured wrapper entry; a network operation is not confirmed"
          : "Boundary recorded; evidence kind unknown; a network operation is not confirmed"};
  }
  function eventBoundary(event) {
    const boundary = boundaryMetadata(event);
    return boundary ? boundaryView(boundary) : null;
  }
  function summaryBoundary(row) {
    row = row && typeof row === "object" && !Array.isArray(row) ? row : {};
    const database = row.boundaryKind === "database-route";
    const attributes = database ? {route: row.destination} : Object.fromEntries(
      ["boundaryKind", "system", "direction", "destination", "operation", "status", "evidenceKind", "queryLimit"].map(key => [key, row[key]]));
    return boundaryView(boundaryMetadata({type: database ? "DB_ROUTE" : "EXTERNAL_CALL", attributes}), row.destinationName);
  }
  // Derive only exact known wrapper observations. A nested client observation
  // supersedes its nearest matching wrapper, never a merely nearby operation.
  // The original records and their event sequences remain untouched.
  function boundaryEvents(input) {
    const events = Array.isArray(input) ? input : [], rows = [], suppressed = new Set(), lanes = new Map();
    const cls = value => typeof value === "string" && value.length <= 4096 &&
      /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$/.test(value) ? value : null;
    const method = value => typeof value === "string" && value.length <= 256 &&
      /^(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>)$/.test(value) ? value : null;
    const sequence = value => {
      if (typeof value === "string" && value.length <= 19 && /^[0-9]+$/.test(value)) value = Number(value);
      return typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : null;
    };
    let previousSeq = null, nestingEnabled = true, scanLimited = events.length > 100000;
    const reset = () => { for (const stack of lanes.values()) stack.length = 0; };
    for (let index = 0; index < Math.min(events.length, 100000); index++) {
      const event = events[index];
      if (!event || typeof event !== "object" || Array.isArray(event)) { reset(); previousSeq = null; continue; }
      const metadata = boundaryMetadata(event);
      if (metadata) rows.push({event, index, metadata});
      const seq = sequence(event.seq), name = token(event.thread, /^[A-Za-z0-9_.$:#/\[\] -]{1,128}$/);
      const thread = event.threadId != null ? typeof event.threadId === "number" && Number.isSafeInteger(event.threadId) && event.threadId > 0 ? "id:" + event.threadId : null : name?.trim() ? "name:" + name : null;
      if (event.detailGapBefore === true || seq === null || previousSeq !== null && seq !== previousSeq + 1) reset();
      previousSeq = seq;
      if (thread === null || !thread.trim()) { reset(); continue; }
      if (!nestingEnabled) continue;
      if (!lanes.has(thread)) {
        if (lanes.size >= 64) { reset(); scanLimited = true; nestingEnabled = false; continue; }
        lanes.set(thread, []);
      }
      const stack = lanes.get(thread), className = cls(event.className), methodName = method(event.methodName);
      const descriptor = methodDescriptor(event.methodDescriptor), invalidDescriptor = event.methodDescriptor != null && descriptor === null;
      if (event.type === "METHOD_ENTER") {
        if (!className || !methodName || seq === null || invalidDescriptor) { stack.length = 0; continue; }
        if (stack.length >= 512) { reset(); scanLimited = true; nestingEnabled = false; continue; }
        stack.push({className, methodName, methodDescriptor: descriptor, wrapperIndex: cacheWrapper(event) ? index : null});
      } else if (event.type === "METHOD_EXIT" || event.type === "EXCEPTION") {
        let position = -1;
        if (className && methodName && seq !== null && !invalidDescriptor) {
          for (let offset = stack.length - 1; offset >= 0; offset--) {
            if (stack[offset].className === className && stack[offset].methodName === methodName && stack[offset].methodDescriptor === descriptor) { position = offset; break; }
          }
        }
        if (position === -1) stack.length = 0; else stack.length = position;
      } else if (metadata?.boundaryKind === "cache" && metadata.system === "redis"
          && metadata.evidenceKind === "client-boundary" && metadata.direction === "outbound"
          && metadata.status && seq !== null) {
        for (let offset = stack.length - 1; offset >= 0; offset--) {
          const frame = stack[offset];
          if (frame.wrapperIndex !== null && cacheWrapperClients.get(frame.methodName) === metadata.operation) {
            suppressed.add(frame.wrapperIndex); break;
          }
        }
      }
    }
    return {rows: rows.filter(row => !suppressed.has(row.index)), scanLimited};
  }
  function collect(trace, maxRows = 200) {
    trace = trace && typeof trace === "object" ? trace : {};
    const limit = Math.min(500, Math.max(1, Number.isInteger(maxRows) ? maxRows : 200));
    const rows = [];
    let total = 0, queryCount = 0, queryLimitCount = 0;
    const add = row => { total++; if (rows.length < limit) rows.push(row); };
    const calls = Array.isArray(trace.httpCalls) ? trace.httpCalls : [];
    for (const call of calls.slice(0, 5000)) {
      if (!call || typeof call !== "object") continue;
      add({kind: "HTTP OUT", destination: text(call.destinationName) || text(call.peer) || "Unknown peer",
        operation: [text(call.httpMethod), text(call.path)].filter(Boolean).join(" "),
        status: [text(call.status), /^\d{3}$/.test(String(call.httpStatus)) ? "HTTP " + call.httpStatus : ""].filter(Boolean).join(" · ") || "Unknown",
        evidence: "Caller-side observation; remote execution requires a linked service segment", propagation: text(call.propagationStatus)});
    }
    const observed = boundaryEvents(trace.events);
    for (const row of observed.rows) {
      add(boundaryView(row.metadata));
      if (isSqlExecution(row.metadata)) queryCount++;
      if (isSqlLimit(row.metadata)) queryLimitCount++;
    }
    return {rows, total, omitted: total - rows.length, queryCount, queryLimitCount,
      scanLimited: calls.length > 5000 || observed.scanLimited};
  }
  return Object.freeze({collect, eventBoundary, summaryBoundary, boundaryMetadata, boundaryEvents, methodDescriptor, jdbcDetails, renderJdbc, renderLazyJdbc, sqlPreview, sqlTitle, isSqlExecution, isSqlLimit});
});
