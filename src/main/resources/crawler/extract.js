// Extracts everything a page check could need, in a single round-trip (spec 5.2).
// Returns plain JSON: Playwright hands it to Java as nested Maps and Lists.
async () => {
  const absolute = (value) => {
    try { return new URL(value, document.baseURI).href; } catch (e) { return null; }
  };

  const links = [...document.querySelectorAll('a[href]')]
    .map(a => ({
      raw: a.getAttribute('href'),
      abs: absolute(a.getAttribute('href')),
      text: (a.textContent || '').trim().slice(0, 200),
      rel: a.getAttribute('rel') || ''
    }))
    .filter(link => link.abs);

  // Images from three origins. <img> reports naturalWidth directly; srcset candidates and CSS
  // backgrounds are never decoded by the page, so measure them — "status 200" is not the test.
  const images = [];
  const measured = new Map();
  const measure = (url) => {
    if (measured.has(url)) return measured.get(url);
    const pending = new Promise(resolve => {
      const probe = new Image();
      probe.onload = () => resolve([probe.naturalWidth, probe.naturalHeight]);
      probe.onerror = () => resolve([0, 0]);
      probe.src = url;
      setTimeout(() => resolve([0, 0]), 5000);
    });
    measured.set(url, pending);
    return pending;
  };

  for (const img of document.querySelectorAll('img')) {
    const alt = img.getAttribute('alt') || '';
    images.push({ raw: img.getAttribute('src') || '', abs: img.currentSrc || img.src,
                  alt, w: img.naturalWidth, h: img.naturalHeight, origin: 'IMG' });
    for (const part of (img.getAttribute('srcset') || '').split(',')) {
      const candidate = part.trim().split(/\s+/)[0];
      if (candidate) {
        images.push({ raw: candidate, abs: absolute(candidate), alt, w: -1, h: -1,
                      origin: 'SRCSET' });
      }
    }
  }
  for (const element of document.querySelectorAll('*')) {
    const background = getComputedStyle(element).backgroundImage;
    if (!background || background === 'none') continue;
    for (const match of background.matchAll(/url\((['"]?)(.*?)\1\)/g)) {
      const candidate = match[2];
      if (candidate && !candidate.startsWith('data:')) {
        images.push({ raw: candidate, abs: absolute(candidate), alt: '', w: -1, h: -1,
                      origin: 'CSS_BACKGROUND' });
      }
    }
  }
  await Promise.all(images.filter(i => i.w < 0 && i.abs).map(async image => {
    const [width, height] = await measure(image.abs);
    image.w = width;
    image.h = height;
  }));

  // Media: readyState >= 1 and duration > 0 are the assertions (spec 7.1), so metadata has to
  // have been given a chance to load before they are read.
  const mediaElements = [...document.querySelectorAll('video'), ...document.querySelectorAll('audio')];
  await Promise.all(mediaElements.map(element => new Promise(resolve => {
    if (element.readyState >= 1 || element.error) return resolve();
    element.addEventListener('loadedmetadata', resolve, { once: true });
    element.addEventListener('error', resolve, { once: true });
    try { element.load(); } catch (e) { /* already loading */ }
    setTimeout(resolve, 4000);
  })));
  const media = mediaElements.map(element => {
    const sources = [];
    if (element.getAttribute('src')) sources.push(absolute(element.getAttribute('src')));
    for (const source of element.querySelectorAll('source[src]')) {
      sources.push(absolute(source.getAttribute('src')));
    }
    return {
      kind: element.tagName === 'VIDEO' ? 'VIDEO' : 'AUDIO',
      sources: sources.filter(Boolean),
      readyState: element.readyState,
      duration: isFinite(element.duration) ? element.duration : 0,
      error: element.error ? 'MEDIA_ERR_' + element.error.code : null
    };
  });

  const frames = [...document.querySelectorAll('iframe')].map(frame => {
    let sameOrigin = false;
    let textLength = 0;
    try {
      const doc = frame.contentDocument;
      if (doc) {
        sameOrigin = true;
        textLength = ((doc.body && doc.body.innerText) || '').trim().length;
      }
    } catch (e) { /* cross-origin: not an error, just opaque */ }
    return {
      src: absolute(frame.getAttribute('src') || ''),
      title: frame.getAttribute('title') || '',
      loaded: !!frame.contentWindow,
      sameOrigin,
      textLength
    };
  }).filter(frame => frame.src);

  const labelOf = (element) => {
    if (element.id) {
      const label = document.querySelector('label[for="' + CSS.escape(element.id) + '"]');
      if (label) return (label.textContent || '').trim();
    }
    const wrapping = element.closest('label');
    return wrapping ? (wrapping.textContent || '').trim() : '';
  };
  const forms = [...document.querySelectorAll('form')].map(form => ({
    id: form.getAttribute('id') || '',
    action: absolute(form.getAttribute('action') || '') || '',
    method: (form.getAttribute('method') || 'get').toLowerCase(),
    fields: [...form.querySelectorAll('input, textarea, select')].map(field => ({
      name: field.getAttribute('name') || '',
      type: (field.getAttribute('type') || field.tagName).toLowerCase(),
      label: labelOf(field),
      autocomplete: field.getAttribute('autocomplete') || '',
      required: !!field.required
    }))
  }));

  const alternates = [...document.querySelectorAll('link[rel~="alternate"][hreflang]')]
    .map(link => ({ lang: link.getAttribute('hreflang') || '',
                    abs: absolute(link.getAttribute('href') || '') }))
    .filter(alternate => alternate.abs);

  return {
    title: document.title || '',
    lang: document.documentElement.getAttribute('lang') || '',
    text: (document.body && document.body.innerText) || '',
    links, images, media, frames, forms, alternates
  };
};
