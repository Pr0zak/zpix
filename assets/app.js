(function () {
  "use strict";

  var VERSION = "1.0.0";
  var REPO = "github.com/Pr0zak/zpix";

  var stage, msg, splash, clockEl, settingsEl;
  var photos = [], order = [], cursor = 0;
  var currentScene = null, running = false, timer = null, clockTimer = null;
  var dSingle, dMosaic, dFloat, dGrid, bag, TS = 1;
  var pendingFolders = [];
  var browserEl, browserPath = "/sdcard";

  var DEFAULTS = {
    folder: "", folders: [], dwell: 9, multi: 8, order: "random", fit: "contain", speed: "normal", size: "medium", clock: false, showDate: false,
    tx: { kenburns: true, fade: true, slide: true, mosaic: true, float: true, origami: true }
  };
  var WEIGHT = { kenburns: 3, fade: 2, slide: 1, mosaic: 2, float: 2, origami: 1 };
  var SPEEDS = { slow: 1.8, normal: 1.0, fast: 0.5 };
  var SIZES = { small: 0.34, medium: 0.46, large: 0.60 }; // floating card height as fraction of screen
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
    // migrate the old single-folder setting to the folders[] list
    if ((!s.folders || !s.folders.length) && s.folder) s.folders = [s.folder];
    if (!s.folders) s.folders = [];
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
    if (scene._oflip) { clearInterval(scene._oflip); scene._oflip = null; }
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
    s.appendChild(img);
    // slide in from a random direction (left/right/top/bottom)
    var dirs = [
      { axis: "X", from: "100%",  exit: "-100%" },
      { axis: "X", from: "-100%", exit: "100%"  },
      { axis: "Y", from: "100%",  exit: "-100%" },
      { axis: "Y", from: "-100%", exit: "100%"  }
    ];
    var d = dirs[Math.floor(Math.random() * dirs.length)];
    s.style.transform = "translate" + d.axis + "(" + d.from + ")";
    mountAbove(s); void s.offsetWidth;
    s.style.transition = "transform var(--t-slide) cubic-bezier(.7,0,.2,1)";
    s.style.transform = "translate" + d.axis + "(0)";
    if (currentScene) {
      currentScene.style.transition = "transform var(--t-slide) cubic-bezier(.7,0,.2,1)";
      currentScene.style.transform = "translate" + d.axis + "(" + d.exit + ")";
    }
    return s;
  }
  // Apple-style "Origami": a grid wall whose tiles periodically fold over to
  // reveal different photos, holding the grid a while before the scene changes.
  function foldTile(cell, img, newUrl) {
    var dur = 600 * TS;
    var a1 = cell.animate(
      [{ transform: "perspective(900px) rotateX(0deg)" },
       { transform: "perspective(900px) rotateX(-90deg)" }],
      { duration: dur, easing: "ease-in", fill: "forwards" });
    a1.onfinish = function () {
      img.src = newUrl;
      cell.animate(
        [{ transform: "perspective(900px) rotateX(90deg)" },
         { transform: "perspective(900px) rotateX(0deg)" }],
        { duration: dur, easing: "ease-out", fill: "forwards" });
    };
  }

  // Build a varied-size tile layout over a 6x4 CSS grid by greedy filling
  // empty cells with random sizes (1x1, 2x1, 1x2, 2x2) that fit.
  function buildVariedTiles(cols, rows) {
    var occ = []; for (var r = 0; r < rows; r++) occ.push(new Array(cols).fill(false));
    var out = [];
    function fits(c, r, w, h) {
      if (c + w > cols || r + h > rows) return false;
      for (var dr = 0; dr < h; dr++) for (var dc = 0; dc < w; dc++) if (occ[r + dr][c + dc]) return false;
      return true;
    }
    function place(c, r, w, h) {
      for (var dr = 0; dr < h; dr++) for (var dc = 0; dc < w; dc++) occ[r + dr][c + dc] = true;
      out.push({ col: c + 1, row: r + 1, cs: w, rs: h });
    }
    for (var r = 0; r < rows; r++) {
      for (var c = 0; c < cols; c++) {
        if (occ[r][c]) continue;
        var sizes = shuffle([[2, 2], [2, 1], [1, 2], [2, 1], [1, 1], [1, 1], [1, 1], [1, 1]]);
        var placed = false;
        for (var i = 0; i < sizes.length; i++) {
          var w = sizes[i][0], h = sizes[i][1];
          if (fits(c, r, w, h)) { place(c, r, w, h); placed = true; break; }
        }
        if (!placed) place(c, r, 1, 1);
      }
    }
    return out;
  }

  function tOrigami(items) {
    var s = newScene();
    var cols = 6, rows = 4;
    var layout = buildVariedTiles(cols, rows);
    var grid = document.createElement("div"); grid.className = "ogrid";
    grid.style.gridTemplateColumns = "repeat(" + cols + ",1fr)";
    grid.style.gridTemplateRows = "repeat(" + rows + ",1fr)";

    var tiles = [], pi = 0;
    layout.forEach(function (t) {
      var cell = document.createElement("div"); cell.className = "ocell";
      cell.style.gridColumn = t.col + " / span " + t.cs;
      cell.style.gridRow = t.row + " / span " + t.rs;
      var img = document.createElement("img"); img.src = items[pi % items.length].url; pi++;
      cell.appendChild(img); grid.appendChild(cell);
      tiles.push({ el: cell, img: img });
    });
    s.appendChild(grid);
    s.classList.add("fade-enter"); mountAbove(s); void s.offsetWidth;
    s.classList.remove("fade-enter"); s.classList.add("fade-in");

    // calm pacing — fold one random tile every few seconds, not constantly
    s._oflip = setInterval(function () {
      if (!running || !s.parentNode) return;
      var t = tiles[Math.floor(Math.random() * tiles.length)];
      var nu = nextUrl();
      preloadFull(nu).then(function (it) {
        if (it.ok && s.parentNode) foldTile(t.el, t.img, nu);
      });
    }, Math.round(4500 * TS));
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
    targetH = clamp(targetH, vh * 0.26, vh * 0.55);

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
    s.appendChild(grid);
    s.classList.add("fade-enter"); mountAbove(s); void s.offsetWidth;
    s.classList.remove("fade-enter"); s.classList.add("fade-in");
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
    var n = items.length;
    // lay out shuffled grid cells so photos spread across the screen
    var cols = Math.max(2, Math.round(Math.sqrt(n * vw / vh)));
    var rows = Math.ceil(n / cols);
    var cellW = vw / cols, cellH = vh / rows;
    var cells = [];
    for (var r = 0; r < rows; r++) for (var c = 0; c < cols; c++) cells.push([c, r]);
    shuffle(cells);

    items.forEach(function (it, idx) {
      var cell = cells[idx % cells.length];
      var cx = (cell[0] + 0.5) * cellW, cy = (cell[1] + 0.5) * cellH;
      var ratio = it.ratio || 1.4;
      // size relative to the screen (per the Photo size setting); cells only set
      // position, so photos stay spread out but can be large and overlap a little
      var base = SIZES[settings.size] || 0.46;
      var h = vh * base * rand(0.88, 1.14), w = h * ratio;
      var maxW = vw * 0.66;
      if (w > maxW) { w = maxW; h = w / ratio; }

      var card = document.createElement("div"); card.className = "floater";
      card.style.width = Math.round(w) + "px"; card.style.height = Math.round(h) + "px";
      card.style.zIndex = String(Math.floor(rand(1, 30)));
      var img = document.createElement("img"); img.src = it.url;
      card.appendChild(img); s.appendChild(card);

      var jx = rand(-0.16, 0.16) * cellW, jy = rand(-0.16, 0.16) * cellH;
      var sx = cx - w / 2 + jx, sy = cy - h / 2 + jy;
      var dx = rand(-0.08, 0.08) * vw, dy = rand(-0.07, 0.07) * vh;
      var rot0 = rand(-3, 3), rot1 = rot0 + rand(-2, 2);
      var z0 = rand(0.96, 1.04), z1 = z0 + rand(0.05, 0.18);
      var dur = rand(16000, 24000);
      card.animate(
        [{ opacity: 0, transform: "translate(" + sx + "px," + sy + "px) rotate(" + rot0 + "deg) scale(" + z0 + ")" },
         { opacity: 1, offset: 0.14 }, { opacity: 1, offset: 0.85 },
         { opacity: 0, transform: "translate(" + (sx + dx) + "px," + (sy + dy) + "px) rotate(" + rot1 + "deg) scale(" + z1 + ")" }],
        { duration: dur, delay: idx * 1100, easing: "linear", fill: "both" }
      );
    });
    s.classList.add("fade-enter"); mountAbove(s); void s.offsetWidth;
    s.classList.remove("fade-enter"); s.classList.add("fade-in");
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

    if (type === "mosaic" || type === "float" || type === "origami") {
      var count = (type === "float") ? clamp(Math.round(settings.multi * 0.75), 3, 12)
                : (type === "origami") ? 12
                : clamp(settings.multi, 2, 20);
      var urls = nextUrls(count);
      Promise.all(urls.map(preloadFull)).then(function (items) {
        if (!running) return;
        items = items.filter(function (it) { return it.ok; });
        if (items.length < 3) { timer = setTimeout(step, 50); return; }
        var prev = currentScene;
        var sc = (type === "float") ? tFloat(items)
               : (type === "origami") ? tOrigami(items)
               : tMosaic(items);
        currentScene = sc;
        setTimeout(function () { retire(prev); }, 2000);
        timer = setTimeout(step, (type === "float") ? dFloat : (type === "origami") ? dGrid : dMosaic);
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
    dGrid = Math.round(settings.dwell * 1000 * 2.4); // origami wall stays a while
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
    var nat = (settings.order === "date") ? "date" : "name";
    var folders = (settings.folders && settings.folders.length) ? settings.folders.slice() : [];
    if (!folders.length) {
      // first run: pick the folder with the most photos, and remember it
      var pick = "";
      try {
        if (window.Android && window.Android.getFolders) {
          var fs = JSON.parse(window.Android.getFolders());
          if (fs.length) pick = fs[0].path;
        }
      } catch (e) { }
      if (!pick && window.Android && window.Android.defaultFolder) pick = window.Android.defaultFolder();
      if (pick) { folders = [pick]; settings.folders = folders; saveSettings(); }
    }
    photos = [];
    for (var i = 0; i < folders.length; i++) {
      try {
        var arr = JSON.parse(window.Android.getPhotos(folders[i], nat));
        if (arr && arr.length) photos = photos.concat(arr);
      } catch (e) { }
    }
  }
  function stopShow() {
    running = false;
    if (timer) { clearTimeout(timer); timer = null; }
    var scenes = stage.getElementsByClassName("scene");
    for (var i = 0; i < scenes.length; i++) {
      if (scenes[i]._oflip) { clearInterval(scenes[i]._oflip); scenes[i]._oflip = null; }
    }
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

  var DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  var MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  function tickClock() {
    var d = new Date();
    var time = "";
    if (settings.clock) {
      var h = d.getHours(), m = d.getMinutes();
      var ap = h >= 12 ? "PM" : "AM";
      var hh = h % 12; if (hh === 0) hh = 12;
      time = hh + ":" + (m < 10 ? "0" + m : m) + " " + ap;
    }
    var date = "";
    if (settings.showDate) {
      date = DAYS[d.getDay()] + ", " + MONTHS[d.getMonth()] + " " + d.getDate();
    }
    clockEl.textContent = (date && time) ? (date + " · " + time) : (date || time);
  }
  function applyClock() {
    if (settings.clock || settings.showDate) {
      clockEl.classList.remove("hidden");
      tickClock();
      if (!clockTimer) clockTimer = setInterval(tickClock, 10000);
    } else {
      clockEl.classList.add("hidden");
      if (clockTimer) { clearInterval(clockTimer); clockTimer = null; }
    }
  }

  // ---------- settings UI ----------

  function relPath(p) {
    if (!p) return "";
    var r = p.replace("/storage/emulated/0", "").replace("/sdcard", "");
    return r.replace(/^\/+/, "") || "(internal storage)";
  }

  // selected folders, each removable
  function renderFolders() {
    var box = document.getElementById("folderList");
    box.innerHTML = "";
    if (!pendingFolders.length) {
      var em = document.createElement("div"); em.className = "about-row";
      em.textContent = "No folders selected — add one below.";
      box.appendChild(em);
      return;
    }
    pendingFolders.forEach(function (p, idx) {
      var row = document.createElement("div"); row.className = "folder";
      var name = document.createElement("span"); name.textContent = relPath(p);
      var cnt = document.createElement("span"); cnt.className = "cnt";
      try { cnt.textContent = (JSON.parse(window.Android.listDir(p)).images || 0) + " photos"; } catch (e) { cnt.textContent = ""; }
      var rm = document.createElement("button"); rm.className = "btn ghost rm"; rm.textContent = "Remove";
      rm.addEventListener("click", function () { pendingFolders.splice(idx, 1); renderFolders(); });
      row.appendChild(name); row.appendChild(cnt); row.appendChild(rm);
      box.appendChild(row);
    });
  }

  // filesystem browser to add any folder
  function openBrowser() { browserPath = "/sdcard"; browserEl.classList.remove("hidden"); renderBrowser(); }
  function closeBrowser() { browserEl.classList.add("hidden"); }
  function renderBrowser() {
    var data;
    try { data = JSON.parse(window.Android.listDir(browserPath)); }
    catch (e) { data = { path: browserPath, dirs: [], parent: "", images: 0 }; }
    browserPath = data.path || browserPath;
    document.getElementById("browserPath").textContent = "/" + relPath(browserPath);
    document.getElementById("browserImages").textContent = (data.images || 0) + " photos here";
    var up = document.getElementById("browserUp");
    up.style.visibility = data.parent ? "visible" : "hidden";
    up.onclick = function () { if (data.parent) { browserPath = data.parent; renderBrowser(); } };
    var box = document.getElementById("browserList"); box.innerHTML = "";
    (data.dirs || []).forEach(function (d) {
      var row = document.createElement("div"); row.className = "folder nav";
      var name = document.createElement("span"); name.textContent = d.name + "  ›";
      var cnt = document.createElement("span"); cnt.className = "cnt"; cnt.textContent = d.images + " photos";
      row.appendChild(name); row.appendChild(cnt);
      row.addEventListener("click", function () { browserPath = d.path; renderBrowser(); });
      box.appendChild(row);
    });
    if (!(data.dirs || []).length) {
      var em = document.createElement("div"); em.className = "about-row"; em.textContent = "No subfolders here.";
      box.appendChild(em);
    }
  }
  function browserAddCurrent() {
    if (pendingFolders.indexOf(browserPath) === -1) pendingFolders.push(browserPath);
    closeBrowser();
    renderFolders();
  }

  function openSettings() {
    pendingFolders = (settings.folders || []).slice();
    document.getElementById("dwell").value = settings.dwell;
    document.getElementById("dwellVal").textContent = settings.dwell;
    document.getElementById("multi").value = settings.multi;
    document.getElementById("multiVal").textContent = settings.multi;
    selectPill("grpOrder", settings.order);
    selectPill("grpFit", settings.fit);
    selectPill("grpSpeed", settings.speed);
    selectPill("grpSize", settings.size);
    document.getElementById("clock").checked = !!settings.clock;
    document.getElementById("showDate").checked = !!settings.showDate;
    var boxes = document.querySelectorAll("[data-tx]");
    for (var i = 0; i < boxes.length; i++) {
      boxes[i].checked = !!settings.tx[boxes[i].getAttribute("data-tx")];
    }
    document.getElementById("aboutVer").textContent = "v" + VERSION;
    document.getElementById("aboutCount").textContent = photos.length + " photos loaded";
    document.getElementById("aboutRepo").innerHTML =
      '<a class="repo-link" href="https://' + REPO + '">' + REPO + '</a>';
    var ub = document.getElementById("btnUpdate");
    ub.textContent = "Check for updates";
    ub.onclick = checkUpdate;
    document.getElementById("updStatus").textContent = "";
    renderFolders();
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
  function downloadAndInstall(url, tag) {
    setUpd("Downloading v" + tag + "…");
    if (window.Android && window.Android.downloadAndInstall) window.Android.downloadAndInstall(url);
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
            btn.onclick = function () { downloadAndInstall(apk.browser_download_url, tag); };
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
    settings.size = getPill("grpSize") || settings.size;
    settings.clock = document.getElementById("clock").checked;
    settings.showDate = document.getElementById("showDate").checked;
    var boxes = document.querySelectorAll("[data-tx]");
    for (var i = 0; i < boxes.length; i++) {
      settings.tx[boxes[i].getAttribute("data-tx")] = boxes[i].checked;
    }
    settings.folders = pendingFolders.slice();
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
    ["grpOrder", "grpFit", "grpSpeed", "grpSize"].forEach(function (gid) {
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
    document.getElementById("btnAddFolder").addEventListener("click", openBrowser);
    document.getElementById("browserCancel").addEventListener("click", closeBrowser);
    document.getElementById("browserAdd").addEventListener("click", browserAddCurrent);
    browserEl.addEventListener("click", function (e) { if (e.target === browserEl) closeBrowser(); });
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
    browserEl = document.getElementById("browser");

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
