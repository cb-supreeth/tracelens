/* Local diagnostic SQL display only. This module never executes SQL. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensSqlValues = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const MAX_SQL = 16384, MAX_OUTPUT = 65536, MAX_PARAMETERS = 128, MAX_VALUE = 512;
  const parameterTypes = new Set("Null Boolean Byte Short Int Long Float Double BigDecimal String Bytes Date Time Timestamp AsciiStream UnicodeStream BinaryStream Object CharacterStream Ref Blob Clob Array URL RowId NString NCharacterStream NClob SQLXML".split(" "));
  const integerTypes = new Set(["Byte", "Short", "Int", "Long"]);
  const decimalTypes = new Set(["Float", "Double", "BigDecimal"]);
  const stringTypes = new Set(["String", "NString", "Date", "Time", "Timestamp"]);
  // These tokens introduce an expression rather than finish its left operand.
  // Unknown words before a bare PostgreSQL ? are deliberately ambiguous.
  const expressionKeywords = new Set(("SELECT DISTINCT ALL WHERE AND OR NOT XOR WHEN THEN ELSE ON HAVING BY LIMIT OFFSET " +
    "VALUES SET RETURNING AS IN IS LIKE ILIKE BETWEEN CASE USING INTO FROM JOIN UPDATE INSERT DELETE FETCH FIRST NEXT " +
    "TOP CALL INTERVAL ESCAPE AT TIME ZONE DEFAULT DO WITH RECURSIVE UNION EXCEPT INTERSECT").split(" "));
  const identifierStart = character => character !== undefined && /[A-Za-z_\u0080-\uffff]/.test(character);
  const identifierPart = character => character !== undefined && /[A-Za-z0-9_$\u0080-\uffff]/.test(character);
  const clip = (value, limit) => value.slice(0, limit - (/[\ud800-\udbff]/.test(value.charAt(limit - 1)) ? 1 : 0));

  function scan(sql, system, backslashQuoted) {
    const positions = [];
    let i = 0, previous = null, ambiguous = false;
    const fail = reason => ({positions, reason});
    const endsExpression = token => token && (token.kind === "value" || token.kind === "identifier"
      || token.kind === "placeholder" || token.text === ")" || token.text === "]"
      || token.kind === "word" && !expressionKeywords.has(token.text.toUpperCase()));
    while (i < sql.length) {
      const ch = sql[i], next = sql[i + 1];
      if (/\s/.test(ch)) { i++; continue; }
      // Unlike PostgreSQL, MySQL requires whitespace/control after --.
      if (ch === "-" && next === "-" && (system === "postgresql" || !sql[i + 2] || /[\s\u0000-\u001f]/.test(sql[i + 2]))
          || system === "mysql" && ch === "#") {
        i += ch === "#" ? 1 : 2;
        while (i < sql.length && sql[i] !== "\n" && sql[i] !== "\r") i++;
        continue;
      }
      if (ch === "/" && next === "*") {
        if (system === "mysql" && sql[i + 2] === "!") return fail("MySQL executable comments make parameter mapping uncertain; the template is retained.");
        let depth = 1; i += 2;
        while (i < sql.length && depth) {
          if (system === "postgresql" && sql[i] === "/" && sql[i + 1] === "*") { depth++; i += 2; }
          else if (sql[i] === "*" && sql[i + 1] === "/") { depth--; i += 2; }
          else i++;
        }
        if (depth) return fail("An unterminated SQL comment prevents reliable parameter mapping.");
        continue;
      }
      if (system === "postgresql" && ch === "$" && !identifierPart(sql[i - 1])) {
        const delimiter = /^\$(?:[A-Za-z_\u0080-\uffff][A-Za-z0-9_\u0080-\uffff]*)?\$/.exec(sql.slice(i));
        if (delimiter) {
          const end = sql.indexOf(delimiter[0], i + delimiter[0].length);
          if (end < 0) return fail("An unterminated PostgreSQL dollar literal prevents reliable parameter mapping.");
          i = end + delimiter[0].length; previous = {kind: "value"}; continue;
        }
      }
      if (ch === "'" || ch === '"' || ch === "`") {
        if (ch === "`" && system === "postgresql") return fail("Backtick syntax is not supported for PostgreSQL parameter reconstruction.");
        const escapeString = system === "postgresql" && ch === "'" && /[eE]/.test(sql[i - 1] || "") && !identifierPart(sql[i - 2]);
        const escapes = escapeString || backslashQuoted && (system === "mysql" || ch === "'");
        let closed = false; i++;
        while (i < sql.length) {
          if (escapes && sql[i] === "\\") { i += 2; continue; }
          if (sql[i++] === ch) {
            if (sql[i] === ch) { i++; continue; }
            closed = true; break;
          }
        }
        if (!closed) return fail("An unterminated SQL quote prevents reliable parameter mapping.");
        previous = {kind: ch === "'" ? "value" : "identifier"}; continue;
      }
      if (ch === "?") {
        if (system === "postgresql" && (next === "?" || next === "|" || next === "&")) {
          // Preserve both JDBC's ?? escape and PostgreSQL's question-mark operators.
          i += 2; previous = {kind: "operator"}; continue;
        }
        if (system === "postgresql" && endsExpression(previous)) ambiguous = true;
        positions.push(i++); previous = {kind: "placeholder"}; continue;
      }
      if (system === "postgresql" && ch === "@" && next === "?") {
        i += 2; previous = {kind: "operator"}; continue;
      }
      if (identifierStart(ch)) {
        const start = i++;
        while (identifierPart(sql[i])) i++;
        previous = {kind: "word", text: sql.slice(start, i)}; continue;
      }
      if (/[0-9]/.test(ch)) {
        const number = /^\d+(?:\.\d*)?(?:[eE][+-]?\d+)?/.exec(sql.slice(i))[0];
        i += number.length; previous = {kind: "value"}; continue;
      }
      previous = {kind: "punctuation", text: ch}; i++;
    }
    return {positions, ...(ambiguous ? {reason: "A bare PostgreSQL ? may be an operator; the template is retained instead of guessing parameter positions."} : {})};
  }

  function literal(entry, system) {
    if (entry.status === "null" && entry.value === null) return {sql: "NULL"};
    if (entry.status === "truncated") return {reason: "Truncated or display-normalized parameter values remain as ?; they are not complete original values."};
    if (entry.status !== "captured") return {reason: "Unsupported parameter values remain as ?."};
    const value = entry.value;
    if (integerTypes.has(entry.type)) {
      if (!/^-?(?:0|[1-9]\d*)$/.test(value)) return {reason: "An invalid numeric parameter remains as ?."};
      // Preserve Long exactly; never round values through JavaScript Number.
      const ranges = {Byte: [-128n, 127n], Short: [-32768n, 32767n], Int: [-2147483648n, 2147483647n], Long: [-9223372036854775808n, 9223372036854775807n]};
      const number = BigInt(value), [min, max] = ranges[entry.type];
      return number >= min && number <= max ? {sql: value} : {reason: "An out-of-range numeric parameter remains as ?."};
    }
    if (decimalTypes.has(entry.type)) {
      if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)
          || entry.type !== "BigDecimal" && !Number.isFinite(Number(value))) return {reason: "A non-finite or invalid numeric parameter remains as ?."};
      return {sql: value};
    }
    if (entry.type === "Boolean") return /^(true|false)$/.test(value) ? {sql: value.toUpperCase()} : {reason: "An invalid Boolean parameter remains as ?."};
    if (entry.type === "Object") return {reason: "setObject runtime types were not captured; these values remain as ? instead of guessing a SQL literal type."};
    if (!stringTypes.has(entry.type)) return {reason: "This JDBC setter type cannot be reconstructed reliably; its value remains as ?."};
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/.test(value)) return {reason: "A parameter contains non-displayable characters and remains as ?."};
    if (system === "mysql" && value.includes("\\")) return {reason: "MySQL backslash-containing values remain as ? because the session SQL mode was not captured."};
    const escaped = value.replace(/'/g, "''");
    return {sql: system === "postgresql" && value.includes("\\") ? "E'" + escaped.replace(/\\/g, "\\\\") + "'" : "'" + escaped + "'"};
  }

  /**
   * Derive a bounded, display-only SQL string without mutating captured evidence.
   * A prepared batch requires one explicit parameterSet; sets are never combined.
   * complete describes capture/mapping completeness, not executable SQL equivalence.
   */
  function reconstruct(sqlInfo, system, parameterSet) {
    const info = sqlInfo && typeof sqlInfo === "object" ? sqlInfo : {};
    const rawSql = typeof info.sql === "string" ? info.sql : "", sql = clip(rawSql, MAX_SQL);
    const fallback = (reason, unresolved = 0) => ({sql, substituted: 0, unresolved, complete: false, reason});
    if (!rawSql) return fallback("SQL text is unavailable.");
    if (!["postgresql", "mysql"].includes(system)) return fallback("The SQL dialect is unavailable; the template is retained.");
    if (info.sqlSource !== "prepared") return fallback(info.sqlSource === "batch"
      ? "Statement or mixed batch templates cannot be paired with one parameter set."
      : "Only prepared SQL has positional captured parameters to reconstruct.");
    if (info.sqlTruncated || rawSql.length > MAX_SQL) return fallback("The SQL template is truncated or display-normalized; parameter positions cannot be verified.");
    const regular = scan(sql, system, false), escaped = scan(sql, system, true);
    if (regular.reason !== escaped.reason || regular.positions.length !== escaped.positions.length
        || regular.positions.some((position, index) => position !== escaped.positions[index])) {
      return fallback("SQL quoting depends on an uncaptured session mode; the template is retained instead of guessing parameter positions.", regular.positions.length);
    }
    if (regular.reason) return fallback(regular.reason, regular.positions.length);
    const positions = regular.positions;
    if (positions.length > MAX_PARAMETERS) return fallback("The number of SQL placeholders exceeds the reconstruction limit.", positions.length);
    const explicitSet = parameterSet !== undefined;
    if (!explicitSet && (info.sqlParameterSets !== undefined || info.sqlParametersStatus === "batch")) return fallback("Select a captured prepared-batch entry to reconstruct its values.", positions.length);
    const entries = explicitSet ? parameterSet : info.sqlParameters;
    if (!Array.isArray(entries)) return fallback("Captured parameter values are unavailable; placeholders remain as ?.", positions.length);
    if (!explicitSet && info.sqlParametersStatus !== "captured" && info.sqlParametersStatus !== "unavailable"
        || explicitSet && !["batch", "unavailable", "captured"].includes(info.sqlParametersStatus)) return fallback("Parameter capture status is unavailable; the template is retained.", positions.length);
    if (entries.length > MAX_PARAMETERS) return fallback("The captured parameter count exceeds the reconstruction limit.", positions.length);
    const byIndex = new Map();
    for (const entry of entries) {
      if (!entry || typeof entry !== "object" || Array.isArray(entry) || !Number.isInteger(entry.index)
          || entry.index < 1 || entry.index > positions.length || byIndex.has(entry.index) || !parameterTypes.has(entry.type)
          || !["captured", "null", "unsupported", "truncated"].includes(entry.status)
          || (["captured", "truncated"].includes(entry.status) ? typeof entry.value !== "string" || entry.value.length > MAX_VALUE : entry.value !== null)) {
        return fallback("Captured parameter indices, counts or values do not match the template; no values were substituted.", positions.length);
      }
      byIndex.set(entry.index, entry);
    }
    const parts = [], reasons = new Set();
    let end = 0, length = 0, substituted = 0;
    for (let index = 0; index < positions.length; index++) {
      const position = positions[index], entry = byIndex.get(index + 1);
      const rendered = entry ? literal(entry, system) : {reason: "Missing parameter values remain as ? at their original indices."};
      const replacement = rendered.sql === undefined ? "?" : rendered.sql;
      const prefix = sql.slice(end, position);
      length += prefix.length + replacement.length;
      if (length + sql.length - position - 1 > MAX_OUTPUT) return fallback("Reconstructed SQL exceeds the display limit; the template is retained.", positions.length);
      parts.push(prefix, replacement); end = position + 1;
      if (rendered.sql !== undefined) substituted++;
      if (rendered.reason) reasons.add(rendered.reason);
    }
    parts.push(sql.slice(end));
    if (info.sqlParametersStatus === "unavailable") reasons.add("Parameter capture is incomplete; only observed values were substituted.");
    // A batch's global limit can refer to other entries, so preserve uncertainty
    // even when this selected entry has a value for every visible placeholder.
    if (info.sqlParametersTruncated || info.sqlParameterSetsOmitted > 0) reasons.add("Parameter capture/display limits were reached; the captured evidence is incomplete.");
    return {sql: parts.join(""), substituted, unresolved: positions.length - substituted, complete: reasons.size === 0,
      ...(reasons.size ? {reason: Array.from(reasons).join(" ")} : {})};
  }

  return {reconstruct};
});
