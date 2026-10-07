// Stars, feature cards, gallery (from media/manifest.json) and the latest release (GitHub API).
(function () {
  var REPO = "olb-freelocs/ThePrisons";

  // twinkling stars
  var canvas = document.getElementById("stars");
  var ctx = canvas.getContext("2d");
  var stars = [];
  function resize() {
    canvas.width = window.innerWidth; canvas.height = window.innerHeight;
    stars = [];
    for (var i = 0; i < Math.round(canvas.width * canvas.height / 9000); i++) {
      stars.push({ x: Math.random() * canvas.width, y: Math.random() * canvas.height, r: Math.random() < .85 ? 1 : 2, s: .5 + Math.random() * 1.5, p: Math.random() * 6.28 });
    }
  }
  function draw(t) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (var i = 0; i < stars.length; i++) {
      var s = stars[i], a = .25 + .6 * (.5 + .5 * Math.sin(t / 1000 * s.s + s.p));
      ctx.fillStyle = "rgba(255,255,255," + a.toFixed(2) + ")";
      ctx.fillRect(s.x, s.y, s.r, s.r);
    }
    if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) requestAnimationFrame(draw);
  }
  window.addEventListener("resize", resize); resize(); requestAnimationFrame(draw);

  // language: stored, else the browser's
  var lang = "en";
  try { lang = localStorage.getItem("lang") || (/^de/i.test(navigator.language || "") ? "de" : "en"); } catch (e) {}
  function setLang(l) {
    lang = l; document.documentElement.lang = l;
    try { localStorage.setItem("lang", l); } catch (e) {}
    document.getElementById("lang").textContent = l === "de" ? "DE | EN" : "EN | DE";
    renderNotes();
  }
  document.getElementById("lang").addEventListener("click", function () { setLang(lang === "de" ? "en" : "de"); });

  // feature cards (en, de)
  var features = [
    ["dashboard", "Dashboard", "Animated pages for design, controls, HUD, mining, bandits and tunnel vision.", "Animierte Seiten für Design, Steuerung, HUD, Mining, Banditen und Tunnel Vision."],
    ["ore-macro", "Ore Macro", "Own pathfinder, guarded-zone logic, breaks, failsafes, follower protection and human view motion.", "Eigener Pathfinder, Wächter-Zonen-Logik, Pausen, Sicherungen, Verfolger-Schutz und menschliche Blickbewegung."],
    ["item-sorter", "Item Sorter", "Trips to the vaults for shards, contrabands, energy and money.", "Fahrten zu den Lagern für Shards, Contraband, Energie und Geld."],
    ["market", "Auction House & /ee", "Prices read in the background, own screens; search reads every page of the auction house.", "Preise im Hintergrund gelesen, eigene Bildschirme; die Suche liest jede Seite des Auktionshauses."],
    ["shops", "Shop Overlays", "The /gz and /pb shops in the mod's design.", "Die Shops /gz und /pb im Design der Mod."],
    ["item-list", "Item List", "Search every known item with tiers, rarities and prices.", "Jedes bekannte Item suchen, mit Stufen, Seltenheiten und Preisen."],
    ["guard-zones", "Guard Zones", "Stay in the guarded area, look ahead, run to a guard when attacked.", "Im bewachten Bereich bleiben, vorausschauen, bei Angriff zu einem Wächter rennen."],
    ["hud", "HUD Widgets", "Session stats, pets, cooldowns, satchels, armour, notifications.", "Session-Statistik, Pets, Abklingzeiten, Satchels, Rüstung, Benachrichtigungen."],
    ["spear-helper", "Spear Helper", "Shooter crosshair, sight point, aim assist on L and recall timing.", "Schützen-Fadenkreuz, Zielpunkt, Zielhilfe auf L und Rückruf-Timing."],
    ["bandit-macro", "Bandit Macro (WIP, ~2 %)", "New and unfinished: hunts bandits with the spear on key J. Expect rough edges.", "Neu und unfertig: jagt Banditen mit dem Speer auf Taste J. Rechnet mit Ecken und Kanten."],
    ["tunnel-vision", "Tunnel Vision", "F5 + V: your player in 3D on a rainbow road over a backdrop of your choice.", "F5 + V: dein Spieler in 3D auf einer Regenbogenstraße vor einem Hintergrund deiner Wahl."]
  ];
  var grid = document.getElementById("feature-grid");
  features.forEach(function (f) {
    var el = document.createElement("article");
    el.className = "feature";
    el.innerHTML = '<img src="media/cards/' + f[0] + '.png" alt="' + f[1] + ' (illustration)"><div><h3></h3><p class="en"></p><p class="de"></p></div>';
    el.querySelector("h3").textContent = f[1];
    el.querySelector("p.en").textContent = f[2];
    el.querySelector("p.de").textContent = f[3];
    grid.appendChild(el);
  });

  // gallery: only real media the repository has (docs/media, listed by media/manifest.json at deploy time)
  fetch("media/manifest.json").then(function (r) { return r.ok ? r.json() : []; }).then(function (files) {
    var box = document.getElementById("gallery-grid");
    if (!files.length) return;
    document.getElementById("gallery-empty").remove();
    files.forEach(function (name) {
      var img = document.createElement("img");
      img.loading = "lazy"; img.src = "media/" + name; img.alt = name.replace(/\.[a-z]+$/, "").replace(/[-_]/g, " ");
      box.appendChild(img);
    });
  }).catch(function () {});

  // latest release: version, download link, notes
  function esc(s) { return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }
  function inline(s) { return esc(s).replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>").replace(/`(.+?)`/g, "<code>$1</code>"); }
  function render(md) {
    var out = [], inList = false;
    md.split(/\r?\n/).forEach(function (line) {
      var h = line.match(/^#{2,4}\s+(.*)$/), li = line.match(/^\s*[-*]\s+(.*)$/);
      if (li) { if (!inList) { out.push("<ul>"); inList = true; } out.push("<li>" + inline(li[1]) + "</li>"); return; }
      if (inList) { out.push("</ul>"); inList = false; }
      if (h) out.push("<h3>" + inline(h[1]) + "</h3>"); else if (line.trim()) out.push("<p>" + inline(line) + "</p>");
    });
    if (inList) out.push("</ul>");
    return out.join("\n");
  }
  var release = null;
  function renderNotes() {
    if (!release) return;
    document.getElementById("release-notes").innerHTML = "<h3>" + esc(release.title) + "</h3>" + render(lang === "de" ? release.de : release.en);
  }
  fetch("https://api.github.com/repos/" + REPO + "/releases/latest").then(function (r) { return r.ok ? r.json() : null; }).then(function (rel) {
    if (!rel) throw new Error("no release");
    document.getElementById("version").textContent = rel.tag_name + (rel.name && rel.name.indexOf("·") > -1 ? " · " + rel.name.split("·")[1].trim() : "");
    var jar = (rel.assets || []).filter(function (a) { return /\.jar$/.test(a.name); })[0];
    if (jar) { var d = document.getElementById("download"); d.href = jar.browser_download_url; d.innerHTML = '<span class="en">Download </span><span class="de">Herunterladen: </span>' + esc(jar.name); }
    var parts = (rel.body || "").split(/\r?\n---\r?\n/);
    release = { title: rel.name || rel.tag_name, en: parts[0], de: (parts[1] || parts[0]).replace(/^\s*##.*Deutsch.*$/m, "") };
    renderNotes();
  }).catch(function () {
    document.getElementById("release-notes").innerHTML = '<p class="note">The release notes are on <a href="https://github.com/' + REPO + '/releases">GitHub</a>.</p>';
  });
  setLang(lang);
})();
