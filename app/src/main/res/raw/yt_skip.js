// Lite Browser - YouTube: sare peste reclame
// Cand YouTube porneste o reclama, playerul primeste clasa "ad-showing".
// Atunci: dam mute, sarim la finalul reclamei si apasam "Skip" daca apare.
(function () {
  'use strict';

  var SKIP = [
    '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern', '.ytp-skip-ad-button',
    '.ytp-ad-skip-button-slot button', 'button.ytp-ad-skip-button-container',
    '.ytp-ad-overlay-close-button', '.ytm-skip-ad-button', '[id^="skip-button"] button'
  ];

  // reclamele din pagina (bannere, recomandari sponsorizate) se ascund
  var css = [
    'ytd-ad-slot-renderer', 'ytm-ad-slot-renderer', 'ad-slot-renderer',
    'ytd-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-web-renderer',
    'ytd-promoted-video-renderer', 'ytm-promoted-video-renderer',
    'ytd-display-ad-renderer', 'ytm-companion-ad-renderer', 'ytd-companion-slot-renderer',
    'ytd-in-feed-ad-layout-renderer', 'ytd-banner-promo-renderer', 'ytm-banner-promo-renderer',
    '#player-ads', '#masthead-ad', '.ytp-ad-overlay-container', '.ytp-ad-image-overlay',
    'ytd-merch-shelf-renderer', 'ytm-merch-shelf-renderer', 'ytd-statement-banner-renderer',
    'ytm-statement-banner-renderer'
  ].join(',') + '{display:none!important}'
    // regula separata: daca browserul nu stie :has, nu strica restul
    + 'ytm-item-section-renderer:has(ytm-ad-slot-renderer){display:none!important}';
  var st = document.createElement('style');
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);

  var wasMuted = null;

  function player() {
    return document.querySelector('.html5-video-player') || document.getElementById('movie_player');
  }

  function check() {
    var p = player();
    var v = p ? p.querySelector('video') : document.querySelector('video');
    if (!p || !v) return;

    if (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')) {
      if (wasMuted === null) wasMuted = v.muted;
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0) {
        if (v.currentTime < v.duration - 0.1) v.currentTime = v.duration - 0.05;
      } else {
        v.playbackRate = 16;
      }
      for (var i = 0; i < SKIP.length; i++) {
        var b = document.querySelector(SKIP[i]);
        if (b) { try { b.click(); } catch (e) {} }
      }
    } else if (wasMuted !== null) {
      // reclama s-a terminat: revenim la sunet si viteza normala
      v.muted = wasMuted;
      if (v.playbackRate === 16) v.playbackRate = 1;
      wasMuted = null;
    }

    // fereastra "ad blocker detectat"
    var warn = null;
    try {
      warn = document.querySelector('tp-yt-paper-dialog:has(ytd-enforcement-message-view-model)');
    } catch (e) {}
    if (!warn) warn = document.querySelector('ytd-enforcement-message-view-model');
    if (warn) {
      warn.remove();
      var bd = document.querySelector('tp-yt-iron-overlay-backdrop');
      if (bd) bd.remove();
      if (v.paused) v.play();
    }
  }

  // reactioneaza imediat la schimbari + verificare de siguranta la 250 ms
  try {
    new MutationObserver(check).observe(document.documentElement, {
      subtree: true, attributes: true, attributeFilter: ['class'], childList: true
    });
  } catch (e) {}
  setInterval(check, 250);
  check();
})();
