'use strict';
/* Company logos and country flags from the game, packed by the Windows app's map builder (etsassets.bin), decoded when first needed. */
class MapAssets {
  static async load(url) {
    const r = await fetch(url, { cache: 'no-cache' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const raw = await r.arrayBuffer(), dv = new DataView(raw), u8 = new Uint8Array(raw);
    if (String.fromCharCode(u8[0], u8[1], u8[2], u8[3]) !== 'ETSA') throw new Error('not an assets file');
    const a = new MapAssets(), dec = new TextDecoder('utf-8');
    const n = dv.getUint32(8, true);
    let o = 12;
    for (let i = 0; i < n; i++) {
      const kind = u8[o], kl = u8[o + 1], key = dec.decode(u8.subarray(o + 2, o + 2 + kl)); o += 2 + kl;
      const len = dv.getUint32(o, true); o += 4;
      (kind === 0 ? a.logos : a.flags).set(key, u8.subarray(o, o + len)); o += len;
    }
    return a;
  }

  constructor() { this.logos = new Map(); this.flags = new Map(); this.images = new Map(); }

  _image(key, bytes) {
    let e = this.images.get(key);
    if (!e) {
      e = { bmp: null };
      this.images.set(key, e);
      createImageBitmap(new Blob([bytes], { type: 'image/png' })).then((b) => { e.bmp = b; }).catch(() => {});
    }
    return e.bmp;
  }

  logo(id) { const b = this.logos.get(id.toLowerCase()); return b ? this._image('l:' + id.toLowerCase(), b) : null; }

  flag(cc) {
    let k = cc.toLowerCase(), b = this.flags.get(k);
    if (!b) { k = k === 'gb' ? 'uk' : k === 'uk' ? 'gb' : k; b = this.flags.get(k); }
    return b ? this._image('f:' + k, b) : null;
  }
}
