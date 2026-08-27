// checks/contact-form.js — harvest every form; Java decides (D83). The five style/rect
// signals are D91's honeypot test; a rect-only read misses two of the five hiding techniques.
() => {
  const CAPTCHA = '.g-recaptcha,[data-sitekey],iframe[src*="recaptcha"],iframe[src*="hcaptcha"],.cf-turnstile,.h-captcha';
  return [...document.querySelectorAll('form')].slice(0, 20).map((f, fi) => {
    f.setAttribute('data-wth-form', String(fi));
    return {index: fi, id: f.id || '', action: f.getAttribute('action') || '',
            method: (f.method || '').toLowerCase(), role: f.getAttribute('role') || '',
            captcha: !!f.querySelector(CAPTCHA),
            fields: [...f.elements].slice(0, 60).map((el, i) => {
              el.setAttribute('data-wth-field', fi + '-' + i);
              const cs = getComputedStyle(el), r = el.getBoundingClientRect();
              return {index: i, tag: el.tagName.toLowerCase(), type: el.type || '',
                      name: el.name || '', id: el.id || '',
                      label: (el.labels && el.labels[0] ? el.labels[0].textContent : '').trim().slice(0, 80),
                      placeholder: el.placeholder || '',
                      autocomplete: el.getAttribute('autocomplete') || '', required: !!el.required,
                      display: cs.display, visibility: cs.visibility, opacity: parseFloat(cs.opacity),
                      width: Math.round(r.width), height: Math.round(r.height),
                      x: Math.round(r.x), y: Math.round(r.y),
                      optionValues: el.options ? [...el.options].map(o => o.value) : []};
            })};
  });
}
