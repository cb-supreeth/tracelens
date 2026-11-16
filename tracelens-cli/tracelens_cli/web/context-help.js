/* Static contextual guidance only: no recording, routing, storage or network access. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else {
    root.TraceLensContextHelp = api;
    api.create(root.document, {window: root});
  }
})(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';

  const HELP = Object.freeze({
    'live-requests': {
      title: 'Live requests',
      text: 'Captured API and other non-job requests still retained by connected services—not just requests running now. Filter by API path and time, or use Request data keyword → Search for captured API params and bodies. Nothing showing? Try increasing the time window.',
    },
    'scheduled-jobs': {
      title: 'Scheduled jobs',
      text: 'Captured background job runs, kept separate from API requests. Search a job name or ID and expand a group to inspect a run; jobs outside recording coverage will not appear.',
    },
    errors: {
      title: 'Errors',
      text: 'Captured HTTP errors, timeouts and execution failures grouped by signature. The count is unique requests matching the time window and Errors filters, not failing segments or groups. Filter by failing service or status, then expand a group; these retained samples are not an error rate.',
    },
    'performance-insights': {
      title: 'Performance insights',
      text: 'Choose captured requests or a saved capture, then Analyze to check slow queries, repeated SQL patterns and suspected N+1. Reads at most ten recent matching requests on demand; it does not enable recording or monitor traffic continuously. Findings are evidence to investigate, not confirmed defects.',
    },
    'saved-captures': {
      title: 'Saved captures',
      text: 'Snapshots saved on this viewer’s machine, plus older browser-only captures. Open one to revisit fixed evidence after live traces expire; snapshot links need access to this viewer.',
    },
    investigation: {
      title: 'Start an investigation',
      text: 'Enter the exact backend API path and choose Check capture readiness to check capture setup and look for existing traces, not diagnose application bugs. To save a fresh reproduction, choose Watch next request & save and reproduce after “Watching” appears. This does not enable recording.',
    },
    'capture-tools': {
      title: 'Compare & search saved captures',
      text: 'Save snapshots to the viewer first, then compare a baseline and candidate or search saved method, class, exception and destination observations. Uses saved evidence only; an absent observation does not prove code is unused or removed.',
    },
    'request-flow': {
      title: 'Flow diagram',
      text: 'Follow captured services and external boundaries. Linked HTTP hops start hidden; Show HTTP hops reveals their cards. Arrow numbers show causal/local order; decimal branches require overlapping caller-local calls, and ? means unknown order. Click a service to inspect its code; missing boxes do not prove a system was never called.',
    },
    'service-details': {
      title: 'Service details',
      text: 'Read the same request as a nested list of services and external calls. Open a service’s execution flow for its code; durations belong to each service and should not be added together.',
    },
    'code-flow': {
      title: 'Code flow',
      text: 'Read observed method entries in order within each thread section, with indentation showing nesting. Expand a method or search for code; separate threads do not establish an asynchronous handoff.',
    },
    'call-tree': {
      title: 'Call tree',
      text: 'Explore observed method nesting as a diagram. Expand branches, search or focus a selected branch; this is captured nesting, not a complete static call graph.',
    },
    timeline: {
      title: 'Timeline / source',
      text: 'Inspect retained events and available source lines, or filter to boundaries and exceptions. Live source uses the current checkout; snapshots show the source lines saved with them.',
    },
  });

  function create(document, options) {
    const win = options.window;
    const cleanups = [];
    const entries = [];
    let active = null;
    let pinned = false;
    let closeTimer = null;

    function on(target, event, handler, capture) {
      target.addEventListener(event, handler, capture);
      cleanups.push(() => target.removeEventListener(event, handler, capture));
    }
    function cancelClose() {
      if (closeTimer !== null) win.clearTimeout(closeTimer);
      closeTimer = null;
    }
    function hide() {
      cancelClose();
      if (active) active.tip.hidden = true;
      active = null;
      pinned = false;
    }
    function position() {
      if (!active) return;
      const {button, tip} = active;
      const anchor = button.getBoundingClientRect();
      const width = win.innerWidth;
      const height = win.innerHeight;
      if (!button.getClientRects().length || anchor.bottom <= 0 || anchor.top >= height
          || anchor.right <= 0 || anchor.left >= width) {
        hide();
        return;
      }
      const box = tip.getBoundingClientRect();
      const left = Math.max(12, Math.min(anchor.right - box.width, width - box.width - 12));
      const below = anchor.bottom + 8;
      const top = below + box.height <= height - 12 ? below : Math.max(12, anchor.top - box.height - 8);
      tip.style.left = left + 'px';
      tip.style.top = top + 'px';
    }
    function show(entry) {
      cancelClose();
      if (active !== entry) {
        hide();
        active = entry;
      }
      entry.tip.hidden = false;
      position();
    }
    function closeSoon(entry) {
      cancelClose();
      closeTimer = win.setTimeout(() => {
        closeTimer = null;
        if (active === entry && !pinned && !entry.hovered && !entry.tipHovered
            && document.activeElement !== entry.button) hide();
      }, 120);
    }
    function describe(element, id) {
      const previous = element.getAttribute('aria-describedby');
      const ids = (previous || '').split(/\s+/).filter(Boolean);
      if (!ids.includes(id)) ids.push(id);
      element.setAttribute('aria-describedby', ids.join(' '));
      cleanups.push(() => {
        if (previous === null) element.removeAttribute('aria-describedby');
        else element.setAttribute('aria-describedby', previous);
      });
    }

    for (const primary of document.querySelectorAll('[data-help]')) {
      const key = primary.getAttribute('data-help');
      const copy = HELP[key];
      const host = primary.closest('.context-help-host');
      if (!copy || !host) continue;
      const id = 'context-help-' + key;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'context-help-info';
      button.setAttribute('aria-label', 'About ' + copy.title);
      button.setAttribute('aria-describedby', id);
      // Decorative, fixed icon; all help text below is assigned with textContent.
      button.innerHTML = '<svg viewBox="0 0 20 20" aria-hidden="true" focusable="false"><circle cx="10" cy="10" r="7.5"/><path d="M10 9v5M10 6h.01"/></svg>';
      const tip = document.createElement('div');
      tip.id = id;
      tip.className = 'context-help-tooltip';
      tip.setAttribute('role', 'tooltip');
      tip.hidden = true;
      const heading = document.createElement('strong');
      heading.textContent = copy.title;
      const body = document.createElement('p');
      body.textContent = copy.text;
      tip.append(heading, body);
      host.append(button);
      document.body.append(tip);
      describe(primary, id);
      const entry = {button, tip, hovered: false, tipHovered: false};
      entries.push(entry);
      if (primary.tagName === 'SUMMARY') {
        // Keep the independent help button next to the title even when the
        // details panel grows to the full row. Do not nest a button in summary.
        const placeInfo = () => {
          if (!primary.getClientRects().length) return;
          const title = primary.getBoundingClientRect();
          const parent = host.getBoundingClientRect();
          const icon = button.getBoundingClientRect();
          button.style.left = Math.max(0, title.right - parent.left - icon.width - 9) + 'px';
          button.style.top = title.top - parent.top + Math.max(0, (title.height - icon.height) / 2) + 'px';
          position();
        };
        placeInfo();
        on(primary.parentElement, 'toggle', placeInfo);
        on(win, 'resize', placeInfo);
        if (typeof win.ResizeObserver === 'function') {
          const observer = new win.ResizeObserver(placeInfo);
          observer.observe(primary);
          observer.observe(button);
          cleanups.push(() => observer.disconnect());
        }
      }
      on(button, 'pointerenter', () => { entry.hovered = true; show(entry); });
      on(button, 'pointerleave', () => { entry.hovered = false; closeSoon(entry); });
      on(tip, 'pointerenter', () => { entry.tipHovered = true; cancelClose(); });
      on(tip, 'pointerleave', () => { entry.tipHovered = false; closeSoon(entry); });
      on(button, 'focus', () => show(entry));
      on(button, 'blur', () => closeSoon(entry));
      on(button, 'click', event => {
        event.preventDefault();
        event.stopPropagation();
        if (active === entry && pinned) hide();
        else { show(entry); pinned = true; }
      });
    }
    on(document, 'keydown', event => {
      if (event.key === 'Escape' && active) {
        hide();
        event.preventDefault();
        event.stopPropagation();
      }
    });
    on(document, 'pointerdown', event => {
      if (active && !active.button.contains(event.target) && !active.tip.contains(event.target)) hide();
    });
    // Opening another view may use an in-page button without changing the hash.
    on(document, 'click', event => {
      if (active && !active.button.contains(event.target) && !active.tip.contains(event.target)) hide();
    });
    on(win, 'resize', position);
    on(win, 'scroll', position, true);
    on(win, 'hashchange', hide);
    return {
      destroy() {
        hide();
        for (const cleanup of cleanups) cleanup();
        for (const {button, tip} of entries) { button.remove(); tip.remove(); }
      },
    };
  }
  return {HELP, create};
});
