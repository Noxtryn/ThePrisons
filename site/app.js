// Cosmic Evolution landing page: language switch, release data and privacy-respecting optional analytics.
(function () {
  "use strict";
  var REPO = "Noxtryn/ThePrisons";
  var lang = "en";
  try { lang = localStorage.getItem("lang") || (/^de/i.test(navigator.language || "") ? "de" : "en"); } catch (e) {}

  var canvas = document.getElementById("stars");
  var ctx = canvas && canvas.getContext ? canvas.getContext("2d") : null;
  var stars = [];
  var reducedMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  function resize() {
    if (!canvas || !ctx) return;
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
    stars = [];
    var count = Math.min(120, Math.round(canvas.width * canvas.height / 16000));
    for (var i = 0; i < count; i++) stars.push({ x: Math.random() * canvas.width, y: Math.random() * canvas.height, r: Math.random() < .8 ? 1 : 2, p: Math.random() * 6.28 });
  }
  function draw(t) {
    if (!ctx) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (var i = 0; i < stars.length; i++) {
      var s = stars[i];
      ctx.fillStyle = "rgba(190,235,232," + (.16 + .35 * (.5 + .5 * Math.sin(t / 1100 + s.p))).toFixed(2) + ")";
      ctx.fillRect(s.x, s.y, s.r, s.r);
    }
    if (!reducedMotion) requestAnimationFrame(draw);
  }
  window.addEventListener("resize", resize);
  resize();
  if (ctx) requestAnimationFrame(draw);

  var release = null;
  function updateVersion() {
    if (!release) return;
    var status = document.getElementById("release-status");
    var version = document.getElementById("version");
    var codename = release.name && release.name.indexOf("·") >= 0 ? " · " + release.name.split("·")[1].trim() : "";
    var beta = !!release.prerelease;
    status.textContent = beta ? (lang === "de" ? "ÖFFENTLICHE BETA" : "PUBLIC BETA") : (lang === "de" ? "STABILE VERSION" : "STABLE RELEASE");
    status.classList.toggle("beta", beta);
    version.textContent = release.tag_name + codename;
  }

  function setLang(value) {
    lang = value;
    document.documentElement.lang = value;
    try { localStorage.setItem("lang", value); } catch (e) {}
    document.getElementById("lang").textContent = value === "de" ? "DE / EN" : "EN / DE";
    updateVersion();
    renderNotes();
  }
  document.getElementById("lang").addEventListener("click", function () { setLang(lang === "de" ? "en" : "de"); });

  function esc(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }
  function renderInline(value) {
    return esc(value).replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>").replace(/`(.+?)`/g, "<code>$1</code>");
  }
  function renderMarkdown(markdown) {
    var html = [];
    var inList = false;
    String(markdown || "").split(/\r?\n/).forEach(function (line) {
      var heading = line.match(/^#{2,4}\s+(.*)$/);
      var item = line.match(/^\s*[-*]\s+(.*)$/);
      if (item) {
        if (!inList) { html.push("<ul>"); inList = true; }
        html.push("<li>" + renderInline(item[1]) + "</li>");
      } else {
        if (inList) { html.push("</ul>"); inList = false; }
        if (heading) html.push("<h4>" + renderInline(heading[1]) + "</h4>");
        else if (line.trim() && !/^>\s*/.test(line)) html.push("<p>" + renderInline(line) + "</p>");
      }
    });
    if (inList) html.push("</ul>");
    return html.join("\n");
  }
  function renderNotes() {
    if (!release) return;
    var box = document.getElementById("release-notes");
    box.innerHTML = "<h3>" + esc(release.name || release.tag_name) + "</h3>" + renderMarkdown(lang === "de" ? release.de : release.en);
  }

  fetch("https://api.github.com/repos/" + REPO + "/releases?per_page=10", { headers: { Accept: "application/vnd.github+json" } })
    .then(function (response) { return response.ok ? response.json() : Promise.reject(new Error("release API unavailable")); })
    .then(function (releases) {
      var item = Array.isArray(releases) ? releases.filter(function (candidate) { return !candidate.draft; })[0] : null;
      if (!item) throw new Error("no published release");
      var body = String(item.body || "").split(/\r?\n---\r?\n/);
      release = { tag_name: item.tag_name, name: item.name || item.tag_name, prerelease: item.prerelease, url: item.html_url, en: body[0], de: body[1] || body[0] };
      var jar = (item.assets || []).filter(function (asset) { return /\.jar$/i.test(asset.name); })[0];
      var target = jar ? jar.browser_download_url : item.html_url;
      document.getElementById("download").href = target;
      document.getElementById("download").title = jar ? jar.name : (lang === "de" ? "Release-Seite öffnen" : "Open release page");
      document.getElementById("download").querySelector(".en").textContent = jar ? "Download beta JAR" : "Open release page";
      document.getElementById("download").querySelector(".de").textContent = jar ? "Beta-JAR herunterladen" : "Release-Seite öffnen";
      updateVersion();
      renderNotes();
    })
    .catch(function () {
      document.getElementById("version").textContent = lang === "de" ? "Noch kein Release verfügbar" : "No published release yet";
      document.getElementById("release-notes").innerHTML = '<p class="note">' + (lang === "de" ? "Release-Notizen erscheinen nach Veröffentlichung." : "Release notes will appear after publication.") + ' <a href="https://github.com/' + REPO + '/releases">' + (lang === "de" ? "GitHub-Releases ansehen ↗" : "View GitHub releases ↗") + "</a></p>";
    });

  // Public config contains no tokens. Analytics are optional, disabled by default, and honour Do Not Track.
  function track(name) {
    try { if (window.goatcounter && window.goatcounter.count) window.goatcounter.count({ path: name, title: name, event: true }); } catch (e) {}
  }
  document.addEventListener("click", function (event) {
    var link = event.target.closest ? event.target.closest("[data-track]") : null;
    if (link) track(link.getAttribute("data-track"));
  });
  fetch("config.json").then(function (response) { return response.ok ? response.json() : {}; }).then(function (config) {
    if (config.discord) {
      var discord = document.getElementById("discord");
      discord.href = config.discord;
      discord.hidden = false;
    }
    var counter = config.analytics && config.analytics.goatcounter;
    var dnt = navigator.doNotTrack === "1" || window.doNotTrack === "1";
    if (counter && !dnt) {
      var script = document.createElement("script");
      script.async = true;
      script.src = "https://gc.zgo.at/count.js";
      script.setAttribute("data-goatcounter", "https://" + counter + ".goatcounter.com/count");
      document.head.appendChild(script);
    }
  }).catch(function () {});
  setLang(lang);
})();
