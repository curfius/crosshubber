/**
 * Best-effort navigation-tracking agent injected into SAME-ORIGIN iframes
 * (D7). It reports iframe-internal navigation to the portal as
 * `{ source: 'iframe:<moduleKey>', type: 'portal:navigate', path }` — the
 * source format the Bridge validates (SOURCE_RE) — and reports once on
 * injection so the portal learns the iframe's initial path.
 *
 * Cross-origin iframes cannot be injected; they must report
 * portal:navigate and react to portal:restore themselves (contract mode).
 */
export function buildIframeAgentScript(moduleKey: string, portalOrigin: string): string {
  return `(function () {
  var PORTAL_ORIGIN = ${JSON.stringify(portalOrigin)};
  var KEY = ${JSON.stringify(moduleKey)};
  var send = function (type, data) {
    var msg = { source: 'iframe:' + KEY, type: type };
    if (data) for (var k in data) msg[k] = data[k];
    window.parent.postMessage(msg, PORTAL_ORIGIN);
  };
  var report = function () {
    send('portal:navigate', { path: location.pathname + location.search + location.hash });
  };
  window.addEventListener('hashchange', report);
  window.addEventListener('popstate', report);
  var origPush = history.pushState && history.pushState.bind(history);
  var origReplace = history.replaceState && history.replaceState.bind(history);
  if (origPush) history.pushState = function () { origPush.apply(this, arguments); report(); };
  if (origReplace) history.replaceState = function () { origReplace.apply(this, arguments); report(); };
  report();
})();`;
}
