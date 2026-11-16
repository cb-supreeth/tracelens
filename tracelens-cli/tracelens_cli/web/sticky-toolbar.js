/* Keep sticky navigation offsets in sync with wrapped and hidden toolbars. */
(function (root, factory) {
  "use strict";
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else {
    root.TraceLensStickyToolbar = api;
    const start = () => { api.controller = api.mount(root.document, root); };
    if (root.document && root.document.readyState === "loading") {
      root.document.addEventListener("DOMContentLoaded", start, {once: true});
    } else if (root.document) start();
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const HEADER_HEIGHT = "--viewer-header-height";
  const TOOLBAR_HEIGHT = "--segment-toolbar-height";

  function height(element) {
    if (!element || element.hidden || typeof element.getBoundingClientRect !== "function") return 0;
    // display:none on an ancestor also removes client rects. A cached ResizeObserver
    // entry alone would otherwise leave a stale sticky offset after navigation.
    if (typeof element.getClientRects === "function" && !element.getClientRects().length) return 0;
    const value = element.getBoundingClientRect().height;
    return Number.isFinite(value) && value > 0 ? Math.ceil(value) : 0;
  }

  function mount(document, view) {
    const style = document && document.documentElement && document.documentElement.style;
    if (!style || typeof style.setProperty !== "function" || typeof document.querySelector !== "function") {
      return {update() {}, destroy() {}};
    }
    let stopped = false, elements = [], visibilityElements = [], values = new Map();
    const original = [HEADER_HEIGHT, TOOLBAR_HEIGHT].map(name => [
      name, typeof style.getPropertyValue === "function" ? style.getPropertyValue(name) : "",
      typeof style.getPropertyPriority === "function" ? style.getPropertyPriority(name) : ""
    ]);
    const resizeObserver = view && typeof view.ResizeObserver === "function"
      ? new view.ResizeObserver(update) : null;
    const mutationObserver = view && typeof view.MutationObserver === "function"
      ? new view.MutationObserver(update) : null;

    function observe(next, detail) {
      if (next.length !== elements.length || next.some((element, index) => element !== elements[index])) {
        elements = next;
        if (resizeObserver) {
          resizeObserver.disconnect();
          elements.forEach(element => resizeObserver.observe(element));
        }
      }
      // Visibility is toggled on #detail-view rather than on the toolbar.
      // Observe only these controls and their panel, not timeline rows or root styles.
      const nextVisibility = [...new Set([...elements, detail].filter(Boolean))];
      if (nextVisibility.length === visibilityElements.length &&
          nextVisibility.every((element, index) => element === visibilityElements[index])) return;
      visibilityElements = nextVisibility;
      if (mutationObserver) {
        mutationObserver.disconnect();
        visibilityElements.forEach(element => mutationObserver.observe(element, {
          attributes: true, attributeFilter: ["class", "style", "hidden"]
        }));
      }
    }

    function update() {
      if (stopped) return;
      const header = document.querySelector("body > header");
      const toolbar = document.querySelector("#segment-toolbar");
      // The primary back action belongs above the API heading. Offer its compact
      // equivalent in the sticky toolbar only after that breadcrumb scrolls away.
      const navigation = document.querySelector("#segment-navigation");
      const stickyBack = document.querySelector("#segment-back");
      if (stickyBack) {
        const bottom = navigation?.getBoundingClientRect?.().bottom;
        const show = height(navigation) > 0 && Number.isFinite(bottom) && bottom <= height(header);
        if (stickyBack.hidden !== !show) stickyBack.hidden = !show;
      }
      observe([header, toolbar].filter(Boolean), document.querySelector("#detail-view"));
      for (const [name, element] of [[HEADER_HEIGHT, header], [TOOLBAR_HEIGHT, toolbar]]) {
        const value = `${height(element)}px`;
        if (values.get(name) !== value) {
          values.set(name, value);
          style.setProperty(name, value);
        }
      }
    }

    function pageHide(event) {
      // A bfcache page retains its observers; pageshow refreshes its measurements.
      if (!event || !event.persisted) destroy();
    }

    function destroy() {
      if (stopped) return;
      stopped = true;
      resizeObserver?.disconnect();
      mutationObserver?.disconnect();
      if (view && typeof view.removeEventListener === "function") {
        view.removeEventListener("resize", update);
        view.removeEventListener("scroll", update);
        view.removeEventListener("pageshow", update);
        view.removeEventListener("pagehide", pageHide);
      }
      for (const [name, value, priority] of original) {
        if (value) style.setProperty(name, value, priority);
        else if (typeof style.removeProperty === "function") style.removeProperty(name);
      }
      elements = [];
      visibilityElements = [];
      values.clear();
    }

    if (view && typeof view.addEventListener === "function") {
      view.addEventListener("resize", update);
      view.addEventListener("scroll", update, {passive: true});
      view.addEventListener("pageshow", update);
      view.addEventListener("pagehide", pageHide);
    }
    update();
    return {update, destroy};
  }
  return {mount};
});
