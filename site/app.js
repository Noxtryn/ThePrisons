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

  // feature cards
  var features = [
    ["dashboard", "Dashboard", "Animated pages for design, controls, HUD, mining, bandits and tunnel vision."],
    ["ore-macro", "Ore Macro", "Own pathfinder, guarded-zone logic, breaks, failsafes and human view motion."],
    ["item-sorter", "Item Sorter", "Trips to the vaults for shards, contrabands, energy and money."],
    ["market", "Auction House & /ee", "Prices read in the background, own screens with categories and search."],
    ["shops", "Shop Overlays", "The /gz and /pb shops in the mod's design."],
    ["item-list", "Item List", "Search every known item with tiers, rarities and prices."],
    ["guard-zones", "Guard Zones", "Stay in the guarded area, look ahead, run to a guard when attacked."],
    ["hud", "HUD Widgets", "Session stats, pets, cooldowns, satchels, armour, notifications."],
    ["spear-helper", "Spear Helper", "Shooter crosshair, sight point, aim assist on L and recall timing."],
    ["tunnel-vision", "Tunnel Vision", "F5 + V: your player in 3D on a rainbow road over a backdrop of your choice."]
  ];
  var grid = document.getElementById("feature-grid");
  features.forEach(function (f) {
    var el = document.createElement("article");
    el.className = "feature";
    el.innerHTML = '<img src="media/cards/' + f[0] + '.png" alt="' + f[1] + ' (illustration)"><div><h3></h3><p></p></div>';
    el.querySelector("h3").textContent = f[1];
    el.querySelector("p").textContent = f[2];
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
  fetch("https://api.github.com/repos/" + REPO + "/releases/latest").then(function (r) { return r.ok ? r.json() : null; }).then(function (rel) {
    if (!rel) throw new Error("no release");
    document.getElementById("version").textContent = rel.tag_name + (rel.name && rel.name.indexOf("·") > -1 ? " · " + rel.name.split("·")[1].trim() : "");
    var jar = (rel.assets || []).filter(function (a) { return /\.jar$/.test(a.name); })[0];
    if (jar) { var d = document.getElementById("download"); d.href = jar.browser_download_url; d.textContent = "Download " + jar.name; }
    document.getElementById("release-notes").innerHTML = "<h3>" + esc(rel.name || rel.tag_name) + "</h3>" + render(rel.body || "");
  }).catch(function () {
    document.getElementById("release-notes").innerHTML = '<p class="note">The release notes are on <a href="https://github.com/' + REPO + '/releases">GitHub</a>.</p>';
  });
})();
