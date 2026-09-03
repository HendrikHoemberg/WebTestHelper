// checks/cookie-banner.js — returns the overlay's container id, or null.
() => {
  const HINTS = ['cookie', 'consent', 'cmp', 'gdpr', 'dsgvo', 'privacy', 'datenschutz',
                 'usercentrics', 'cookiebot', 'borlabs', 'klaro', 'onetrust', 'complianz'];
  const vw = innerWidth * innerHeight;
  const candidates = [...document.querySelectorAll('body *')].filter(el => {
    const hay = (el.id + ' ' + el.className + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
    if (!HINTS.some(h => hay.includes(h)) && el.getAttribute('role') !== 'dialog') return false;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    if (cs.position !== 'fixed' && cs.position !== 'sticky') return false;
    if ((parseInt(cs.zIndex, 10) || 0) < 100) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && (r.width * r.height) / vw >= 0.03;
  });
  // Outermost wins: a CMP nests a dialog inside its overlay and both match.
  const root = candidates.find(el => !candidates.some(o => o !== el && o.contains(el)));
  if (root) {
    root.setAttribute('data-wth-banner', '1');   // a stable handle for the Java side
    return root.id || root.className || root.tagName.toLowerCase();
  }

  // Shadow DOM hosts (e.g. #usercentrics-root or any host matching HINTS with shadowRoot)
  const shadowHost = [...document.querySelectorAll('body *')].find(el => {
    if (!el.shadowRoot) return false;
    const hay = (el.id + ' ' + el.className).toLowerCase();
    return HINTS.some(h => hay.includes(h));
  });
  if (shadowHost) {
    shadowHost.setAttribute('data-wth-banner', '1');
    return shadowHost.id || shadowHost.className || shadowHost.tagName.toLowerCase();
  }

  return null;
}
