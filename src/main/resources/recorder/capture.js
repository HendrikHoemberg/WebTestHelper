(function() {
  if (window.__wth_capture_installed__) return;
  window.__wth_capture_installed__ = true;

  function getTestId(el) {
    if (!el || !el.getAttribute) return null;
    return el.getAttribute('data-testid')
        || el.getAttribute('data-test')
        || el.getAttribute('data-cy')
        || el.getAttribute('data-test-id')
        || null;
  }

  function getImplicitRole(el) {
    const tag = el.tagName ? el.tagName.toLowerCase() : '';
    if (tag === 'button') return 'button';
    if (tag === 'a' && el.hasAttribute('href')) return 'link';
    if (tag === 'select') return 'combobox';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'form') return 'form';
    if (tag === 'img') return 'img';
    if (tag === 'summary') return 'button';
    if (tag === 'dialog') return 'dialog';
    if (tag === 'nav') return 'navigation';
    if (tag === 'main') return 'main';
    if (/^h[1-6]$/.test(tag)) return 'heading';

    if (tag === 'input') {
      const type = (el.getAttribute('type') || 'text').toLowerCase();
      if (type === 'button' || type === 'submit' || type === 'reset' || type === 'image') return 'button';
      if (type === 'checkbox') return 'checkbox';
      if (type === 'radio') return 'radio';
      if (['text', 'password', 'email', 'tel', 'url', 'search', 'number'].includes(type)) return 'textbox';
    }
    return null;
  }

  function getRole(el) {
    if (!el || !el.getAttribute) return null;
    const explicitRole = el.getAttribute('role');
    if (explicitRole && explicitRole.trim()) {
      return explicitRole.trim().toLowerCase();
    }
    return getImplicitRole(el);
  }

  function getLabelText(el) {
    if (!el) return null;
    if (el.labels && el.labels.length > 0) {
      const text = el.labels[0].textContent ? el.labels[0].textContent.trim().replace(/\s+/g, ' ') : '';
      if (text) return text;
    }
    if (el.id) {
      try {
        const lbl = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
        if (lbl && lbl.textContent) {
          const text = lbl.textContent.trim().replace(/\s+/g, ' ');
          if (text) return text;
        }
      } catch (ignored) {}
    }
    const parentLabel = el.closest ? el.closest('label') : null;
    if (parentLabel && parentLabel.textContent) {
      const text = parentLabel.textContent.trim().replace(/\s+/g, ' ');
      if (text) return text;
    }
    return null;
  }

  function getAccessibleName(el) {
    if (!el || !el.getAttribute) return null;
    const ariaLabel = el.getAttribute('aria-label');
    if (ariaLabel && ariaLabel.trim()) {
      return ariaLabel.trim();
    }

    const ariaLabelledby = el.getAttribute('aria-labelledby');
    if (ariaLabelledby && ariaLabelledby.trim()) {
      const ids = ariaLabelledby.trim().split(/\s+/);
      const parts = [];
      for (const id of ids) {
        const ref = document.getElementById(id);
        if (ref && ref.textContent) {
          parts.push(ref.textContent.trim());
        }
      }
      if (parts.length > 0) {
        return parts.join(' ').replace(/\s+/g, ' ');
      }
    }

    const tag = el.tagName ? el.tagName.toLowerCase() : '';
    if (['input', 'textarea', 'select'].includes(tag)) {
      const labelText = getLabelText(el);
      if (labelText) return labelText;
      if (tag === 'input') {
        const type = (el.getAttribute('type') || 'text').toLowerCase();
        if (['button', 'submit', 'reset'].includes(type) && el.value) {
          return el.value.trim();
        }
      }
    }

    if (tag === 'img') {
      const alt = el.getAttribute('alt');
      if (alt && alt.trim()) return alt.trim();
    }

    if (el.innerText || el.textContent) {
      const text = (el.innerText || el.textContent).trim().replace(/\s+/g, ' ');
      if (text) return text;
    }

    const title = el.getAttribute('title');
    if (title && title.trim()) return title.trim();

    const placeholder = el.getAttribute('placeholder');
    if (placeholder && placeholder.trim()) return placeholder.trim();

    return null;
  }

  function getTextContent(el) {
    if (!el) return null;
    const tag = el.tagName ? el.tagName.toLowerCase() : '';
    if (['input', 'select', 'textarea'].includes(tag)) {
      return null;
    }
    if (el.textContent) {
      const text = el.textContent.trim().replace(/\s+/g, ' ');
      return text || null;
    }
    return null;
  }

  function getValue(el) {
    if (!el) return null;
    const tag = el.tagName ? el.tagName.toLowerCase() : '';
    if (tag === 'select') {
      if (el.selectedIndex >= 0 && el.options && el.options[el.selectedIndex]) {
        return el.options[el.selectedIndex].value !== undefined
            ? String(el.options[el.selectedIndex].value)
            : el.options[el.selectedIndex].text;
      }
      return el.value !== undefined ? String(el.value) : null;
    }
    if (tag === 'input' || tag === 'textarea') {
      return el.value !== undefined ? String(el.value) : null;
    }
    return null;
  }

  function computeCssPath(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return '';
    if (el === document.body) return 'body';
    const path = [];
    let curr = el;
    while (curr && curr.nodeType === Node.ELEMENT_NODE && curr !== document.documentElement) {
      const tag = curr.tagName.toLowerCase();
      let segment = tag;
      if (curr.id && /^[a-zA-Z][a-zA-Z0-9_-]*$/.test(curr.id)) {
        segment = '#' + curr.id;
        path.unshift(segment);
        break;
      }
      const parent = curr.parentElement;
      if (parent) {
        const siblings = Array.from(parent.children).filter(c => c.tagName === curr.tagName);
        if (siblings.length > 1) {
          const index = siblings.indexOf(curr) + 1;
          segment += ':nth-of-type(' + index + ')';
        }
      }
      path.unshift(segment);
      curr = parent;
    }
    return path.join(' > ');
  }

  function report(kind, el, valueOverride) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return;
    // Count every report on the page so tests (and the recorder) can wait for the capture script
    // to have observed an event before draining it on the Java side, whose delivery is async.
    window.__wth_capture_count__ = (window.__wth_capture_count__ || 0) + 1;
    try {
      const payload = {
        kind: kind,
        tagName: el.tagName ? el.tagName.toLowerCase() : '',
        id: el.id || el.getAttribute('id') || null,
        testId: getTestId(el),
        role: getRole(el),
        accessibleName: getAccessibleName(el),
        labelText: getLabelText(el),
        textContent: getTextContent(el),
        value: valueOverride !== undefined ? valueOverride : getValue(el),
        cssPath: computeCssPath(el),
        inputType: (el.tagName && el.tagName.toLowerCase() === 'input' && el.type) ? el.type.toLowerCase() : (el.getAttribute && el.getAttribute('type') ? el.getAttribute('type').toLowerCase() : null)
      };
      if (window.__wth_capture__) {
        window.__wth_capture__(payload);
      }
    } catch (e) {
      // Capture errors must never disrupt page execution
    }
  }

  function isCookieBannerElement(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return false;
    const HINTS = ['cookie', 'consent', 'cmp', 'gdpr', 'dsgvo', 'privacy', 'datenschutz',
                   'usercentrics', 'cookiebot', 'borlabs', 'klaro', 'onetrust', 'complianz'];
    let curr = el;
    while (curr && curr !== document.body && curr !== document.documentElement) {
      if (curr.hasAttribute && curr.hasAttribute('data-wth-banner')) return true;
      const id = (curr.id || '').toLowerCase();
      const className = (curr.className && typeof curr.className === 'string' ? curr.className : '').toLowerCase();
      if (id === 'usercentrics-root') return true;
      if (HINTS.some(h => id.includes(h) || className.includes(h))) {
        if (id.includes('root') || id.includes('banner') || id.includes('overlay') || id.includes('modal')
            || id.includes('dialog') || id.includes('consent') || id.includes('cmp') || id.includes('usercentrics')
            || className.includes('banner') || className.includes('overlay') || className.includes('modal')
            || className.includes('dialog') || className.includes('usercentrics')) {
          return true;
        }
      }
      curr = curr.parentElement;
    }
    return false;
  }

  window.addEventListener('click', function(e) {
    const path = typeof e.composedPath === 'function' ? e.composedPath() : [];
    for (const node of path) {
      if (node && node.nodeType === Node.ELEMENT_NODE && isCookieBannerElement(node)) {
        return;
      }
    }
    const interactive = e.target && e.target.closest
        ? e.target.closest('button, a[href], input[type="button"], input[type="submit"], input[type="reset"], [role="button"], [role="link"], select, summary')
        : null;
    const target = interactive || (e.target && e.target.nodeType === Node.ELEMENT_NODE ? e.target : (e.target ? e.target.parentElement : null));
    if (target) {
      if (isCookieBannerElement(target)) {
        return;
      }
      report('CLICK', target);
    }
  }, true);

  window.addEventListener('input', function(e) {
    if (e.target && e.target.nodeType === Node.ELEMENT_NODE) {
      report('INPUT', e.target);
    }
  }, true);

  window.addEventListener('change', function(e) {
    if (e.target && e.target.nodeType === Node.ELEMENT_NODE) {
      report('CHANGE', e.target);
    }
  }, true);

  window.addEventListener('submit', function(e) {
    if (e.target && e.target.nodeType === Node.ELEMENT_NODE) {
      report('SUBMIT', e.target);
    }
  }, true);
})();
