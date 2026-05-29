(function () {
  "use strict";

  var VERSION = "1.0.0";
  var REPO = "github.com/Pr0zak/zpix";

  var stage, msg, splash, clockEl, settingsEl;
  var photos = [], order = [], cursor = 0;
  var currentScene = null, running = false, timer = null, clockTimer = null;
  var dSingle, dMosaic, dFloat, bag, TS = 1;
  var pendingFolder = null;

  var DEFAULTS = {
    folder: "", dwell: 9, multi: 8, order: "random", fit: "contain", speed: "normal", clock: false,
    tx: { kenburns: true, fade: true, slide: true, mosaic: true, float: true, origami: true }
  };
  var WEIGHT = { kenburns: 3, fade: 2, slide: 1, mosaic: 2, float: 2, origami: 1 };
  var SPEEDS = { slow: 1.8, normal: 1.0, fast: 0.5 };
  var settings = null;

  // ---------- settings persistence ----------

  function loadSettings() {
    var s = JSON.parse(JSON.stringify(DEFAULTS));
    try {
      var raw = localStorage.getItem("zpix.settings");
      if (raw) {
        var o = JSON.parse(raw);
        for (var k in o) {
          if (k === "tx" && o.tx) { for (var t in o.tx) s.tx[t] = !!o.tx[t]; }
          else s[k] = o[k];
        }
      }
    } catch (e) { }
    return s;
  }
  function saveSettings() {
    try { localStorage.setItem("zpix.settings", JSON.stringify(settings)); } catch (e) { }
  }

  // ---------- helpers ----------

  function shuffle(a) {
    for (var i = a.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = a[i]; a[i] = a[j]; a[j] = t;
    }
    return a;
  }
  function rand(min, max) { return min + Math.random() * (max - min); }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function rebuildOrder() {
    order = [];
    for (var i = 0; i < photos.length; i++) order.push(i);
    if (settings.order === "random") shuffle(order); // name/date come pre-sorted from native
    cursor = 0;
  }
  function nextUrl() {
    if (cursor >= order.length) rebuildOrder();
    return photos[order[cursor++]];
  }
  function nextUrls(n) {
    var out = [], guard = 0;
    while (out.length < n && guard < n * 6) {
      var u = nextUrl();
      if (out.indexOf(u) === -1) out.push(u);
      guard++;
    }
    while (out.length < n) out.push(nextUrl());
    return out;
  }

  function preloadFull(url) {
    return new Promise(function (resolve) {
      var img = new Image();
      var done = false;
      function finish(ok) {
        if (done) return;
        done = true;
        var ratio = (img.naturalWidth && img.naturalHeight)
          ? img.naturalWidth / img.naturalHeight : 1.4;
        resolve({ url: url, ok: ok, ratio: ratio });
      }
      img.onload = function () { finish(true); };
      img.onerror = function () { finish(false); };
      img.src = url;
      setTimeout(function () { finish(img.complete && img.naturalWidth > 0); }, 7000);
    });
  }

  function newScene() { var s = document.createElement("div"); s.className = "scene"; return s; }
  function mountAbove(scene) { stage.appendChild(scene); }
  function retire(scene) {
    if (!scene) return;
    var imgs = scene.getElementsByTagName("img");
    for (var i = 0; i < imgs.length; i++) imgs[i].src = "";
    if (scene.parentNode) scene.parentNode.removeChild(scene);
  }
  function addBg(scene, url) {
    var bg = document.createElement("div");
    bg.className = "bg";
    bg.style.backgroundImage = "url('" + url + "')";
    scene.appendChild(bg);
    return bg;
  }

  // ---------- single-photo transitions ----------

  function tFade(url) {
    var s = newScene(); addBg(s, url);
    var img = document.createElement("img"); img.className = "photo"; img.src = url;
    s.appendChild(img); s.classList.add("fade-enter"); mountAbove(s);
    void s.offsetWidth; s.classList.remove("fade-enter"); s.classList.add("fade-in");
    return s;
  }
  function tKenBurns(url) {
    var s = newScene();
    var img = document.createElement("img"); img.className = "kb"; img.src = url;
    s.appendChild(img); s.classList.add("fade-enter"); mountAbove(s);
    var z0 = 1.06, z1 = 1.20, dx = rand(-3, 3), dy = rand(-3, 3);
    img.style.transform = "scale(" + z0 + ") translate(0%,0%)";
    void s.offsetWidth; s.classList.remove("fade-enter"); s.classList.add("fade-in");
    img.style.transition = "transform " + (dSingle + 1600) + "ms linear";
    img.style.transform = "scale(" + z1 + ") translate(" + dx + "%," + dy + "%)";
    return s;
  }
  function tSlide(url) {
    var s = newScene(); addBg(s, url);
    var img = document.createElement("img"); img.className = "photo"; img.src = url;
    s.appendChild(img); s.classList.add("slide-enter"); mountAbove(s);
    void s.offsetWidth; s.classList.remove("slide-enter"); s.classList.add("slide-in");
    if (currentScene) currentScene.classList.add("slide-out-left");
    return s;
  }
  function tOrigami(url, w) {
    var s = newScene(); addBg(s, url);
    var wrap = document.createElement("div"); wrap.className = "origami";
    var N = 8, colW = w / N, panels = [];
    for (var i = 0; i < N; i++) {
      var p = document.createElement("div"); p.className = "opanel";
      p.style.left = (i * colW) + "px"; p.style.width = colW + "px";
      p.style.transformOrigin = (i % 2 === 0) ? "left center" : "right center";
      p.style.transform = "rotateY(" + ((i % 2 === 0) ? 92 : -92) + "deg)";
      var img = document.createElement("img"); img.src = url;
      img.style.width = w + "px"; img.style.left = (-i * colW) + "px";
      p.appendChild(img); wrap.appendChild(p); panels.push(p);
    }
    s.appendChild(wrap); mountAbove(s); void s.offsetWidth;
    panels.forEach(function (p, i) { setTimeout(function () { p.classList.add("unfold"); }, 110 * TS * i); });
    return s;
  }

  // ---------- scattered collage ----------

  function tMosaic(items) {
    var s = newScene(); addBg(s, items[0].url);
    var vw = window.innerWidth, vh = window.innerHeight;
    var pad = Math.round(vh * 0.035), gap = Math.round(vh * 0.018);
    var W = vw - pad * 2, avail = vh - pad * 2;
    var avgR = 1.3;
    var targetH = Math.sqrt((W * avail) / (avgR * items.length));
    targetH = clamp(targetH, vh * 0.17, vh * 0.40);

    var rows = [], cur = [], sumR = 0;
    for (var i = 0; i < items.length; i++) {
      var r = items[i].ratio || 1.4;
      cur.push({ url: items[i].url, ratio: r }); sumR += r;
      if (targetH * sumR + gap * (cur.length - 1) >= W) {
        var h = (W - gap * (cur.length - 1)) / sumR;
        rows.push({ items: cur, h: h }); cur = []; sumR = 0;
      }
    }
    if (cur.length) rows.push({ items: cur, h: targetH });

    var totalH = gap * (rows.length - 1);
    rows.forEach(function (row) { totalH += row.h; });
    if (totalH > avail) { var k = avail / totalH; rows.forEach(function (row) { row.h *= k; }); }

    var grid = document.createElement("div"); grid.className = "mosaic"; grid.style.gap = gap + "px";
    var tiles = [];
    rows.forEach(function (row) {
      var rowEl = document.createElement("div"); rowEl.className = "mrow"; rowEl.style.gap = gap + "px";
      rowEl.style.marginTop = Math.round(rand(-gap * 0.6, -gap * 0.1)) + "px";
      row.items.forEach(function (it) {
        var tile = document.createElement("div"); tile.className = "tile";
        tile.style.width = Math.round(row.h * it.ratio) + "px";
        tile.style.height = Math.round(row.h) + "px";
        tile.style.zIndex = String(Math.floor(rand(1, 30)));
        var img = document.createElement("img"); img.src = it.url;
        tile.appendChild(img); rowEl.appendChild(tile); tiles.push(tile);
      });
      grid.appendChild(rowEl);
    });
    s.appendChild(grid); mountAbove(s); void s.offsetWidth;
    tiles.forEach(function (t, i) {
      var rot = rand(-7, 7), jx = rand(-gap, gap), jy = rand(-gap, gap);
      t.animate(
        [{ opacity: 0, transform: "translate(" + jx + "px," + (jy - 26) + "px) rotate(" + rot + "deg) scale(.82)" },
         { opacity: 1, transform: "translate(" + jx + "px," + jy + "px) rotate(" + rot + "deg) scale(1)" }],
        { duration: 700 * TS, delay: (i * 85 * TS) + 40, easing: "cubic-bezier(.2,.7,.2,1)", fill: "both" }
      );
    });
    return s;
  }

  // ---------- floating photos ----------

  function tFloat(items) {
    var s = newScene();
    var vw = window.innerWidth, vh = window.innerHeight;
    items.forEach(function (it, idx) {
      var h = vh * rand(0.30, 0.52), w = h * (it.ratio || 1.4);
      var card = document.createElement("div"); card.className = "floater";
      card.style.width = Math.round(w) + "px"; card.style.height = Math.round(h) + "px";
      card.style.zIndex = String(Math.floor(rand(1, 30)));
      var img = document.createElement("img"); img.src = it.url;
      card.appendChild(img); s.appendChild(card);
      var sx = rand(-w * 0.2, vw - w * 0.8), sy = rand(-h * 0.2, vh - h * 0.8);
      var ex = sx + rand(-0.22, 0.22) * vw, ey = sy + rand(-0.18, 0.18) * vh;
      var rot0 = rand(-4, 4), rot1 = rot0 + rand(-3, 3);
      var z0 = rand(0.95, 1.05), z1 = z0 + rand(0.08, 0.28);
      var dur = rand(15000, 23000);
      card.animate(
        [{ opacity: 0, transform: "translate(" + sx + "px," + sy + "px) rotate(" + rot0 + "deg) scale(" + z0 + ")" },
         { opacity: 1, offset: 0.12 }, { opacity: 1, offset: 0.85 },
         { opacity: 0, transform: "translate(" + ex + "px," + ey + "px) rotate(" + rot1 + "deg) scale(" + z1 + ")" }],
        { duration: dur, delay: idx * 1500, easing: "linear", fill: "both" }
      );
    });
    mountAbove(s);
    return s;
  }

  // ---------- scheduler ----------

  function buildBag() {
    var b = [];
    for (var t in settings.tx) {
      if (settings.tx[t]) { var w = WEIGHT[t] || 1; for (var i = 0; i < w; i++) b.push(t); }
    }
    if (!b.length) b.push("fade");
    return b;
  }
  function chooseType() { return bag[Math.floor(Math.random() * bag.length)]; }

  function step() {
    if (!running) return;
    var type = chooseType();
    var w = window.innerWidth;

    if (type === "mosaic" || type === "float") {
      var count = (type === "float")
        ? clamp(Math.round(settings.multi * 0.75), 3, 12)
        : clamp(settings.multi, 2, 20);
      var urls = nextUrls(count);
      Promise.all(urls.map(preloadFull)).then(function (items) {
        if (!running) return;
        items = items.filter(function (it) { return it.ok; });
        if (items.length < 3) { timer = setTimeout(step, 50); return; }
        var prev = currentScene;
        var sc = (type === "float") ? tFloat(items) : tMosaic(items);
        currentScene = sc;
        setTimeout(function () { retire(prev); }, 2000);
        timer = setTimeout(step, (type === "float") ? dFloat : dMosaic);
      });
      return;
    }

    var url = nextUrl();
    preloadFull(url).then(function (it) {
      if (!running) return;
      if (!it.ok) { timer = setTimeout(step, 50); return; }
      var prev = currentScene, sc;
      if (type === "kenburns") sc = tKenBurns(url);
      else if (type === "slide") sc = tSlide(url);
      else if (type === "origami") sc = tOrigami(url, w);
      else sc = tFade(url);
      currentScene = sc;
      setTimeout(function () { retire(prev); }, 1800);
      timer = setTimeout(step, dSingle);
    });
  }

  // ---------- show control ----------

  function applyTimings() {
    dSingle = settings.dwell * 1000;
    dMosaic = Math.round(settings.dwell * 1000 * 1.5);
    dFloat = Math.round(settings.dwell * 1000 * 2.0);
  }
  function applySpeed() {
    var m = SPEEDS[settings.speed] || 1.0;
    TS = m;
    var root = document.documentElement;
    root.style.setProperty("--t-fade", (1.6 * m) + "s");
    root.style.setProperty("--t-slide", (1.4 * m) + "s");
    root.style.setProperty("--t-fold", (1.5 * m) + "s");
  }
  function applyFit() {
    document.documentElement.style.setProperty("--photo-fit", settings.fit || "contain");
  }
  function loadPhotos() {
    var folder = settings.folder;
    var nat = (settings.order === "date") ? "date" : "name";
    try {
      if (!folder) {
        // first run: pick the folder with the most photos
        if (window.Android && window.Android.getFolders) {
          var folders = JSON.parse(window.Android.getFolders());
          if (folders.length) folder = folders[0].path;
        }
        if (!folder && window.Android && window.Android.defaultFolder) folder = window.Android.defaultFolder();
      }
      var json = (window.Android && window.Android.getPhotos) ? window.Android.getPhotos(folder, nat) : "[]";
      photos = JSON.parse(json);
    } catch (e) { photos = []; }
  }
  function stopShow() {
    running = false;
    if (timer) { clearTimeout(timer); timer = null; }
    while (stage.firstChild) stage.removeChild(stage.firstChild);
    currentScene = null;
  }
  function startShow() {
    stopShow();
    loadPhotos();
    applyTimings();
    applySpeed();
    applyFit();
    bag = buildBag();
    applyClock();
    if (!photos.length) {
      msg.textContent = "No photos found in " + (settings.folder || "default folder");
      msg.style.display = "block";
      return;
    }
    msg.style.display = "none";
    rebuildOrder();
    running = true;
    step();
  }

  // ---------- clock ----------

  function tickClock() {
    var d = new Date();
    var h = d.getHours(), m = d.getMinutes();
    var ap = h >= 12 ? "PM" : "AM";
    var hh = h % 12; if (hh === 0) hh = 12;
    clockEl.textContent = hh + ":" + (m < 10 ? "0" + m : m) + " " + ap;
  }
  function applyClock() {
    if (settings.clock) {
      clockEl.classList.remove("hidden");
      tickClock();
      if (!clockTimer) clockTimer = setInterval(tickClock, 10000);
    } else {
      clockEl.classList.add("hidden");
      if (clockTimer) { clearInterval(clockTimer); clockTimer = null; }
    }
  }

  // ---------- settings UI ----------

  function buildFolderList() {
    var box = document.getElementById("folderList");
    box.innerHTML = "";
    var folders = [];
    try {
      var j = (window.Android && window.Android.getFolders) ? window.Android.getFolders() : "[]";
      folders = JSON.parse(j);
    } catch (e) { folders = []; }

    var active = pendingFolder;
    if (!active) {
      active = settings.folder;
      if (!active && window.Android && window.Android.defaultFolder) active = window.Android.defaultFolder();
    }

    folders.forEach(function (f) {
      var row = document.createElement("div");
      row.className = "folder" + (f.path === active ? " sel" : "");
      var name = document.createElement("span"); name.textContent = f.name;
      var cnt = document.createElement("span"); cnt.className = "cnt"; cnt.textContent = f.count + " photos";
      row.appendChild(name); row.appendChild(cnt);
      row.addEventListener("click", function () {
        pendingFolder = f.path;
        var all = box.getElementsByClassName("folder");
        for (var i = 0; i < all.length; i++) all[i].classList.remove("sel");
        row.classList.add("sel");
      });
      box.appendChild(row);
    });
    if (!folders.length) {
      var em = document.createElement("div"); em.className = "about-row";
      em.textContent = "No photo folders found under /sdcard.";
      box.appendChild(em);
    }
  }

  function openSettings() {
    pendingFolder = null;
    document.getElementById("dwell").value = settings.dwell;
    document.getElementById("dwellVal").textContent = settings.dwell;
    document.getElementById("multi").value = settings.multi;
    document.getElementById("multiVal").textContent = settings.multi;
    selectPill("grpOrder", settings.order);
    selectPill("grpFit", settings.fit);
    selectPill("grpSpeed", settings.speed);
    document.getElementById("clock").checked = !!settings.clock;
    var boxes = document.querySelectorAll("[data-tx]");
    for (var i = 0; i < boxes.length; i++) {
      boxes[i].checked = !!settings.tx[boxes[i].getAttribute("data-tx")];
    }
    document.getElementById("aboutVer").textContent = "v" + VERSION;
    document.getElementById("aboutCount").textContent = photos.length + " photos loaded";
    document.getElementById("aboutRepo").textContent = REPO;
    var ub = document.getElementById("btnUpdate");
    ub.textContent = "Check for updates";
    ub.onclick = checkUpdate;
    document.getElementById("updStatus").textContent = "";
    buildFolderList();
    settingsEl.classList.remove("hidden");
  }
  function closeSettings() { settingsEl.classList.add("hidden"); }

  // ---------- self-update ----------

  function cmpVer(a, b) {
    var pa = String(a).split("."), pb = String(b).split(".");
    for (var i = 0; i < 3; i++) {
      var x = parseInt(pa[i], 10) || 0, y = parseInt(pb[i], 10) || 0;
      if (x !== y) return x - y;
    }
    return 0;
  }
  function setUpd(s) {
    var st = document.getElementById("updStatus");
    if (st) st.textContent = s;
    if (window.console && console.log) console.log("ZPIXUPD: " + s);
  }
  function checkUpdate() {
    var btn = document.getElementById("btnUpdate");
    setUpd("Checking…");
    fetch("https://api.github.com/repos/Pr0zak/zpix/releases/latest",
          { headers: { "Accept": "application/vnd.github+json" } })
      .then(function (r) { return r.json(); })
      .then(function (rel) {
        var tag = (rel && rel.tag_name ? rel.tag_name : "").replace(/^v/, "");
        if (!tag) { setUpd("No releases yet"); return; }
        if (cmpVer(tag, VERSION) > 0) {
          var apk = (rel.assets || []).filter(function (a) { return /\.apk$/i.test(a.name); })[0];
          if (apk) {
            setUpd("Update available");
            btn.textContent = "Install v" + tag;
            btn.onclick = function () {
              setUpd("Downloading v" + tag + "…");
              if (window.Android && window.Android.installUpdate) window.Android.installUpdate(apk.browser_download_url);
            };
          } else {
            setUpd("v" + tag + " available (no APK)");
          }
        } else {
          setUpd("Up to date (v" + VERSION + ")");
        }
      })
      .catch(function () { setUpd("Check failed (no network?)"); });
  }
  window.zpixUpdateFailed = function () {
    var st = document.getElementById("updStatus");
    if (st) st.textContent = "Download failed";
  };

  function selectPill(groupId, val) {
    var g = document.getElementById(groupId);
    if (!g) return;
    var bs = g.getElementsByClassName("pill");
    for (var i = 0; i < bs.length; i++) {
      bs[i].classList.toggle("sel", bs[i].getAttribute("data-val") === val);
    }
  }
  function getPill(groupId) {
    var g = document.getElementById(groupId);
    if (!g) return null;
    var sel = g.getElementsByClassName("sel")[0];
    return sel ? sel.getAttribute("data-val") : null;
  }

  function saveAndRestart() {
    settings.dwell = parseInt(document.getElementById("dwell").value, 10) || 9;
    settings.multi = parseInt(document.getElementById("multi").value, 10) || 8;
    settings.order = getPill("grpOrder") || settings.order;
    settings.fit = getPill("grpFit") || settings.fit;
    settings.speed = getPill("grpSpeed") || settings.speed;
    settings.clock = document.getElementById("clock").checked;
    var boxes = document.querySelectorAll("[data-tx]");
    for (var i = 0; i < boxes.length; i++) {
      settings.tx[boxes[i].getAttribute("data-tx")] = boxes[i].checked;
    }
    if (pendingFolder) settings.folder = pendingFolder;
    saveSettings();
    closeSettings();
    startShow();
  }

  function wireUi() {
    document.getElementById("dwell").addEventListener("input", function () {
      document.getElementById("dwellVal").textContent = this.value;
    });
    document.getElementById("multi").addEventListener("input", function () {
      document.getElementById("multiVal").textContent = this.value;
    });
    ["grpOrder", "grpFit", "grpSpeed"].forEach(function (gid) {
      var g = document.getElementById(gid);
      if (!g) return;
      g.addEventListener("click", function (e) {
        var btn = e.target.closest ? e.target.closest(".pill") : null;
        if (!btn || !g.contains(btn)) return;
        var bs = g.getElementsByClassName("pill");
        for (var i = 0; i < bs.length; i++) bs[i].classList.remove("sel");
        btn.classList.add("sel");
      });
    });
    document.getElementById("btnUpdate").onclick = checkUpdate;
    document.getElementById("btnSave").addEventListener("click", saveAndRestart);
    document.getElementById("btnClose").addEventListener("click", closeSettings);
    settingsEl.addEventListener("click", function (e) {
      if (e.target === settingsEl) closeSettings();
    });
    document.addEventListener("click", function (e) {
      if (!settingsEl.classList.contains("hidden")) return;
      if (splash && !splash.classList.contains("hide")) return;
      if (e.target.closest && e.target.closest("#settings")) return;
      openSettings();
    });
  }

  // ---------- boot ----------

  function boot() {
    stage = document.getElementById("stage");
    msg = document.getElementById("msg");
    splash = document.getElementById("splash");
    clockEl = document.getElementById("clock");
    settingsEl = document.getElementById("settings");

    settings = loadSettings();
    wireUi();
    startShow();

    setTimeout(function () { if (splash) splash.classList.add("hide"); }, 2600);
  }

  if (document.readyState === "complete" || document.readyState === "interactive") {
    setTimeout(boot, 0);
  } else {
    document.addEventListener("DOMContentLoaded", boot);
  }
})();
