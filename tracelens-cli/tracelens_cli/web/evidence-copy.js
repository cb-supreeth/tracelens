/* Copy only explicitly displayed evidence, on a user click. No clipboard reads. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.TraceLensEvidenceCopy = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  function button(document, text, label) {
    const wrapper = document.createElement("span"); wrapper.className = "evidence-copy";
    const control = document.createElement("button");
    control.type = "button"; control.className = "evidence-copy-button"; control.textContent = "Copy";
    control.setAttribute("aria-label", label); control.title = label;
    const status = document.createElement("span"); status.className = "evidence-copy-status";
    status.setAttribute("role", "status"); status.setAttribute("aria-live", "polite");
    wrapper.append(control, status);
    const view = document.defaultView;
    let pending = false, resetTimer = null;
    control.onclick = async event => {
      event.preventDefault(); event.stopPropagation();
      if (pending) return;
      pending = true; control.disabled = true;
      if (resetTimer !== null) view?.clearTimeout?.(resetTimer);
      status.textContent = ""; status.className = "evidence-copy-status";
      control.textContent = "Copying…";
      try {
        const clipboard = view?.navigator?.clipboard;
        if (typeof text !== "string" || !clipboard?.writeText) throw new Error("Clipboard unavailable");
        await clipboard.writeText(text);
        control.textContent = "Copied";
        status.textContent = "Copied to clipboard.";
        if (view?.setTimeout) resetTimer = view.setTimeout(() => {
          control.textContent = "Copy"; status.textContent = ""; resetTimer = null;
        }, 1600);
      } catch (_) {
        control.textContent = "Retry copy";
        status.className = "evidence-copy-status evidence-copy-error";
        status.textContent = "Could not copy. Select the text and copy it manually.";
      } finally {
        pending = false; control.disabled = false;
      }
    };
    return wrapper;
  }
  return Object.freeze({button});
});
