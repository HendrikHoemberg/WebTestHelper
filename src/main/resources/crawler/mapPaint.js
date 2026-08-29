// One source of truth for "did the frame's map canvas paint" (spec 7.1's Maps case).
// Returns 'PAINTED' | 'NOT_PAINTED' | 'UNKNOWN'. The same file is inlined into extract.js for
// same-origin frames and evaluated directly in a cross-origin frame's own context, so the two
// paths can never drift.
//
// A blank verdict is confirmed with a second read after a short settle: the probe runs right after
// LOAD + the NETWORKIDLE cap, so a slow-but-healthy map whose tiles have not painted yet must not
// be read once as NOT_PAINTED — that is exactly the false ERROR this signal must not introduce.
const MAP_SETTLE_MS = 2000;

const readCanvasPixels = (canvases) => {
  let readable = 0;
  for (const canvas of canvases) {
    let data = null;
    try {
      const ctx = canvas.getContext('2d');
      data = ctx ? ctx.getImageData(0, 0, canvas.width, canvas.height).data : null;
    } catch (e) { data = null; }
    if (!data) continue;
    readable++;
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] !== 0) return 'PAINTED';
    }
  }
  return readable > 0 ? 'NOT_PAINTED' : 'UNKNOWN';
};

const mapPaintOf = async (doc) => {
  const canvases = [...doc.querySelectorAll('canvas')]
    .filter(c => (c.width || 0) > 0 && (c.height || 0) > 0);
  if (canvases.length === 0) return 'UNKNOWN';
  const first = readCanvasPixels(canvases);
  if (first !== 'NOT_PAINTED') return first;
  await new Promise(resolve => setTimeout(resolve, MAP_SETTLE_MS));
  const second = readCanvasPixels(canvases);
  return second === 'NOT_PAINTED' ? 'NOT_PAINTED'
       : second === 'PAINTED' ? 'PAINTED'
       : 'UNKNOWN';
};
