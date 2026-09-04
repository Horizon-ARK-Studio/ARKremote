(function () {
  "use strict";

  var bridge = window.AndroidBridge || null; // absent when opened in a plain browser preview
  var statusEl = document.getElementById("status");
  var LONG_PRESS_MS = 500;
  var lastKnownState = bridge ? "disconnected" : "preview mode (no AndroidBridge)";

  function render(text, ok) {
    statusEl.textContent = text;
    statusEl.className = "status" + (ok ? " connected" : "");
  }

  function renderConnState(state) {
    lastKnownState = state;
    var label = {
      registered: "ready — connecting to paired device…",
      bluetooth_off: "turn on Bluetooth",
      permission_denied: "Bluetooth permission needed",
      disconnected: "no HID connection",
    }[state] || state; // "connected:<name>" falls through as-is

    if (label.indexOf("connected:") === 0) {
      render("connected to " + label.slice("connected:".length), true);
    } else {
      render(label, false);
    }
  }

  // Called from native via evaluateJavascript().
  window.onHidState = renderConnState;
  window.onRemapped = function (buttonId, label) {
    render(buttonId + " \u2192 " + label, false);
    setTimeout(function () { renderConnState(lastKnownState); }, 1600);
  };

  function vibrateAndSend(el) {
    var id = el.getAttribute("data-hid");
    el.classList.add("active");
    setTimeout(function () { el.classList.remove("active"); }, 120);

    if (!bridge) {
      console.log("[preview] press", id);
      return;
    }
    bridge.vibrate(25);
    bridge.sendKey(id);
  }

  function openMapper(el) {
    var id = el.getAttribute("data-hid");
    if (!bridge) {
      console.log("[preview] long-press (would open mapper for)", id);
      return;
    }
    bridge.vibrate(15);
    bridge.openMapper(id);
  }

  document.querySelectorAll(".btn[data-hid]").forEach(function (el) {
    var timer = null;
    var longFired = false;

    el.addEventListener("pointerdown", function (e) {
      e.preventDefault();
      longFired = false;
      timer = setTimeout(function () {
        longFired = true;
        openMapper(el);
      }, LONG_PRESS_MS);
    });

    el.addEventListener("pointerup", function () {
      clearTimeout(timer);
      if (!longFired) vibrateAndSend(el);
    });

    el.addEventListener("pointerleave", function () {
      clearTimeout(timer);
    });
  });

  renderConnState(lastKnownState);
})();
