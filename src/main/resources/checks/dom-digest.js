// checks/dom-digest.js — one string that changes when anything a visitor could notice changes.
() => {
  const s = document.documentElement.outerHTML;
  let h = 0;
  for (let i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) | 0; }
  const media = [...document.querySelectorAll('video,audio')].map(m => m.paused ? '0' : '1').join('');
  return h + ':' + s.length + ':' + (document.body ? document.body.innerText.length : 0) + ':'
           + (document.body ? document.body.scrollHeight : 0) + ':' + media;
}
