// checks/locale-links.js — harvest every anchor; Java decides which are a language switch (D83).
() => [...document.querySelectorAll('a')].slice(0, 300).map((a, i) => {
  a.setAttribute('data-wth-locale', String(i));
  const r = a.getBoundingClientRect();
  return {index: i, href: a.href || '', hreflang: a.getAttribute('hreflang') || '',
          label: (a.getAttribute('aria-label') || a.textContent || '').trim().slice(0, 40),
          visible: r.width > 0 && r.height > 0};
})
