// checks/clickables.js — harvest every control; Java decides which may be clicked (D83, D85).
() => [...document.querySelectorAll('button, a, [role=button], input[type=button], input[type=submit]')]
  .slice(0, 200).map((el, i) => {
    el.setAttribute('data-wth-btn', String(i));
    const r = el.getBoundingClientRect();
    return {index: i, tag: el.tagName.toLowerCase(), type: el.getAttribute('type') || '',
            label: (el.getAttribute('aria-label') || el.value || el.textContent || '').trim().slice(0, 60),
            href: el.getAttribute('href') || '', inForm: !!el.closest('form'),
            disabled: !!el.disabled, visible: r.width > 0 && r.height > 0,
            target: el.getAttribute('target') || ''};
  })
