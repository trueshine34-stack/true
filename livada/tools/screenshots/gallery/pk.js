// Imaginile din pachete (versiunea online a galeriei, construită de tools/screenshots/pack_web.py).
// Un artefact publicat admite puține fișiere, așa că miniaturile stau în foi WebP (sprite-uri), iar capturile
// întregi în pachete JSON {cale: base64}. MANIFEST.pk spune pentru fiecare cale unde e:
//   [pachet, x, y, lățime, înălțime] — decupată dintr-o foaie;   [pachet] — dintr-un JSON.
// Se încarcă după manifest.js și înainte de gallery.js. Galeria pune căile ca de obicei:
//   - img.src = "img/…webp"   → aici se descarcă pachetul, apoi src devine un blob: (evenimentele load/error rămân);
//   - <img data-pk="img/…">   → la inserarea în pagină, la fel.
(function () {
  var M = window.MANIFEST;
  if (!M || !M.packs || !M.pk) return;
  var IDX = M.pk, packs = {}, urls = {}, pending = {};
  var desc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');

  function get(i) {
    if (packs[i]) return packs[i];
    var name = M.packs[i];
    var p = fetch(name).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return /\.json$/.test(name) ? r.json() : r.blob().then(function (b) { return createImageBitmap(b); });
    });
    packs[i] = p.catch(function (e) { delete packs[i]; throw e; });
    return packs[i];
  }
  // foile decodate ocupă ~20 MB fiecare, pachetele JSON câțiva MB: le eliberăm când nu mai sunt folosite câteva secunde
  var used = {};
  function release(i) {
    used[i] = Date.now();
    setTimeout(function () {
      if (Date.now() - used[i] < 2900 || !packs[i]) return;
      var p = packs[i]; delete packs[i];
      p.then(function (v) { if (v && v.close) v.close(); }, function () {});
    }, 3000);
  }
  function bytes(b64) {
    var s = atob(b64), n = s.length, a = new Uint8Array(n);
    for (var k = 0; k < n; k++) a[k] = s.charCodeAt(k);
    return a;
  }
  function crop(bmp, e) {
    var c = document.createElement('canvas');
    c.width = e[3]; c.height = e[4];
    c.getContext('2d').drawImage(bmp, e[1], e[2], e[3], e[4], 0, 0, e[3], e[4]);
    return new Promise(function (ok, no) { c.toBlob(function (b) { b ? ok(b) : no(new Error('toBlob')); }, 'image/webp', 0.9); });
  }
  function url(p) {
    if (urls[p]) return Promise.resolve(urls[p]);
    if (pending[p]) return pending[p];
    var e = IDX[p];
    if (!e) return Promise.resolve(p);
    pending[p] = get(e[0]).then(function (pk) {
      release(e[0]);
      return e.length > 1 ? crop(pk, e) : new Blob([bytes(pk[p])], { type: 'image/webp' });
    }).then(function (blob) {
      delete pending[p];
      return (urls[p] = URL.createObjectURL(blob));
    }, function (err) { delete pending[p]; throw err; });
    return pending[p];
  }
  function set(img, p) {
    if (urls[p]) { img.__pk = null; return desc.set.call(img, urls[p]); }
    img.__pk = p;
    url(p).then(function (u) { if (img.__pk === p) desc.set.call(img, u); },
      function () { if (img.__pk === p) desc.set.call(img, 'data:,'); }); // → evenimentul error, ca la un fișier lipsă
  }
  Object.defineProperty(HTMLImageElement.prototype, 'src', {
    configurable: true, enumerable: desc.enumerable,
    get: function () { return desc.get.call(this); },
    set: function (v) {
      var p = String(v);
      if (IDX[p]) return set(this, p);
      this.__pk = null;
      desc.set.call(this, v);
    },
  });
  function adopt(img) { var p = img.getAttribute('data-pk'); img.removeAttribute('data-pk'); set(img, p); }
  function scan(n) {
    if (n.nodeType !== 1) return;
    if (n.tagName === 'IMG' && n.hasAttribute('data-pk')) return adopt(n);
    if (n.querySelectorAll) Array.prototype.forEach.call(n.querySelectorAll('img[data-pk]'), adopt);
  }
  new MutationObserver(function (ms) {
    ms.forEach(function (m) { Array.prototype.forEach.call(m.addedNodes, scan); });
  }).observe(document.documentElement, { childList: true, subtree: true });

  window.PK = { url: url, sync: function (p) { return urls[p] || null; } };
})();
