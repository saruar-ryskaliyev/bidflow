(() => {
  "use strict";

  const SPEED_SLICES = Object.freeze({
    1: 1_000_000,
    5: 5_000_000,
    20: 20_000_000,
    100: 100_000_000,
  });
  const SVG_NS = "http://www.w3.org/2000/svg";
  const MAX_TIMELINE_EVENTS = 200;
  const PLAY_POLL_MILLIS = 250;
  const PAUSED_POLL_MILLIS = 1_000;
  const IMPORTANT_KINDS = new Set(["CONTROL", "PARTITION", "HEAL", "DROP", "BLOCK"]);

  const refs = {
    connection: document.querySelector("#connection-status"),
    simTime: document.querySelector("#sim-time"),
    configForm: document.querySelector("#config-form"),
    seed: document.querySelector("#config-seed"),
    shards: document.querySelector("#config-shards"),
    budget: document.querySelector("#config-budget"),
    network: document.querySelector("#config-network"),
    skew: document.querySelector("#config-skew"),
    traffic: document.querySelector("#config-traffic"),
    playToggle: document.querySelector("#play-toggle"),
    speed: document.querySelector("#speed-select"),
    step: document.querySelector("#step-button"),
    advanceAmount: document.querySelector("#advance-select"),
    advance: document.querySelector("#advance-button"),
    replay: document.querySelector("#replay-button"),
    topologyScroll: document.querySelector("#topology-scroll"),
    topology: document.querySelector("#topology-svg"),
    shardBody: document.querySelector("#shard-table-body"),
    searchForm: document.querySelector("#search-form"),
    searchShard: document.querySelector("#search-shard"),
    eventFilter: document.querySelector("#event-filter"),
    eventList: document.querySelector("#event-list"),
    timelineNote: document.querySelector("#timeline-note"),
    overspendRow: document.querySelector("#overspend-row"),
    toast: document.querySelector("#toast"),
  };

  const moneyFormatter = new Intl.NumberFormat(undefined, {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const integerFormatter = new Intl.NumberFormat();

  let currentState = null;
  let eventCursor = 0;
  let eventLog = [];
  let pollInFlight = false;
  let refreshRequested = false;
  let pollTimer = 0;
  let toastTimer = 0;
  let configDirty = false;
  let topologySignature = "";
  let messageLayer = null;
  let nodePositions = new Map();

  bindControls();
  schedulePoll(0);

  function bindControls() {
    refs.configForm.addEventListener("submit", (event) => {
      event.preventDefault();
      if (!refs.configForm.reportValidity()) {
        return;
      }
      const params = new URLSearchParams({
        seed: refs.seed.value,
        shards: refs.shards.value,
        budget: refs.budget.value,
        network: refs.network.value,
        skew: refs.skew.value,
        traffic: refs.traffic.checked ? "1" : "0",
      });
      void runCommand(`/api/reset?${params}`, {
        resetEvents: true,
        successMessage: "Reset",
      }).then((ok) => {
        if (ok) {
          configDirty = false;
        }
      });
    });

    for (const input of [refs.seed, refs.shards, refs.budget, refs.network, refs.skew]) {
      input.addEventListener("input", () => {
        configDirty = true;
      });
    }

    refs.traffic.addEventListener("change", () => {
      const requested = refs.traffic.checked;
      void runCommand(`/api/traffic?enabled=${requested ? "1" : "0"}`).then((ok) => {
        if (!ok) {
          refs.traffic.checked = !requested;
        }
      });
    });

    refs.playToggle.addEventListener("click", () => {
      if (currentState?.playing) {
        void runCommand("/api/pause");
      } else {
        void runCommand(`/api/play?speed=${encodeURIComponent(refs.speed.value)}`);
      }
    });

    refs.speed.addEventListener("change", () => {
      const route = currentState?.playing ? "play" : "pause";
      void runCommand(`/api/${route}?speed=${encodeURIComponent(refs.speed.value)}`);
    });

    refs.step.addEventListener("click", () => {
      void runCommand("/api/step");
    });

    refs.advance.addEventListener("click", () => {
      void runCommand(`/api/advance?nanos=${encodeURIComponent(refs.advanceAmount.value)}`);
    });

    refs.replay.addEventListener("click", () => {
      void runCommand("/api/replay", {
        resetEvents: true,
        successMessage: "Replayed",
      });
    });

    refs.searchForm.addEventListener("submit", (event) => {
      event.preventDefault();
      void runCommand(`/api/search?shard=${encodeURIComponent(refs.searchShard.value)}`);
    });

    refs.eventFilter.addEventListener("change", renderTimeline);

    refs.topology.addEventListener("click", (event) => {
      const node = event.target.closest("[data-shard]");
      if (!node) {
        return;
      }
      const shard = Number(node.getAttribute("data-shard"));
      const shardState = currentState?.shards?.[shard];
      if (!shardState) {
        return;
      }
      if (event.shiftKey) {
        const action = isAuthorityPartitioned(currentState, shard) ? "heal" : "partition";
        void runShardAction(shard, action);
      } else if (shardState.alive) {
        void runShardAction(shard, "crash");
      } else {
        void runShardAction(shard, "restart");
      }
    });

    window.addEventListener("beforeunload", () => {
      window.clearTimeout(pollTimer);
    });
  }

  async function poll() {
    if (pollInFlight) {
      refreshRequested = true;
      return;
    }
    pollInFlight = true;
    refreshRequested = false;

    try {
      if (currentState?.playing) {
        const slice = SPEED_SLICES[currentState.speed] ?? SPEED_SLICES[1];
        await requestJson(`/api/advance?nanos=${slice}`, { method: "POST" });
      }

      const state = await requestJson("/api/state");
      if (state.nextEventSeq <= eventCursor) {
        resetLocalEvents("");
      }
      const page = await requestJson(
        `/api/events?after=${eventCursor}&limit=${MAX_TIMELINE_EVENTS}`,
      );

      renderState(state);
      ingestEvents(page);
      setConnection(true);
    } catch (error) {
      setConnection(false);
      showToast(error.message, true);
    } finally {
      pollInFlight = false;
      const delay = refreshRequested
        ? 0
        : currentState?.playing
          ? PLAY_POLL_MILLIS
          : PAUSED_POLL_MILLIS;
      schedulePoll(delay);
    }
  }

  function schedulePoll(delay) {
    window.clearTimeout(pollTimer);
    pollTimer = window.setTimeout(() => {
      void poll();
    }, delay);
  }

  function requestRefresh() {
    if (pollInFlight) {
      refreshRequested = true;
    } else {
      schedulePoll(0);
    }
  }

  async function runCommand(path, options = {}) {
    try {
      await requestJson(path, { method: "POST" });
      if (options.resetEvents) {
        resetLocalEvents("");
      }
      if (options.successMessage) {
        showToast(options.successMessage, false);
      }
      requestRefresh();
      return true;
    } catch (error) {
      showToast(error.message, true);
      return false;
    }
  }

  async function requestJson(path, options = {}) {
    const response = await fetch(path, {
      cache: "no-store",
      ...options,
    });
    const text = await response.text();
    let body = {};
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        throw new Error(`Invalid JSON (${response.status})`);
      }
    }
    if (!response.ok) {
      throw new Error(body.error || `Request failed (${response.status})`);
    }
    return body;
  }

  function renderState(state) {
    currentState = state;
    refs.simTime.textContent = formatNanos(state.nowNanos);
    refs.playToggle.textContent = state.playing ? "Pause" : "Play";
    refs.playToggle.setAttribute("aria-pressed", String(state.playing));
    refs.speed.value = String(state.speed);

    renderMetrics(state);
    syncConfiguration(state);
    updateShardSelect(state.shards);
    renderShardTable(state);
    renderTopology(state);
  }

  function renderMetrics(state) {
    const money = state.authority;
    const values = {
      budget: formatMicros(money.budget),
      actualSpend: formatMicros(money.actualSpend),
      spendableRemainder: formatMicros(money.spendableRemainder),
      overspend: formatMicros(money.overspend),
    };
    for (const element of document.querySelectorAll("[data-metric]")) {
      const key = element.getAttribute("data-metric");
      element.textContent = values[key] ?? "—";
      if (key in money) {
        element.title = `${integerFormatter.format(money[key])} micros`;
      }
    }
    refs.overspendRow.classList.toggle("hot", money.overspend > 0);
    refs.overspendRow.classList.toggle("quiet", money.overspend === 0);
  }

  function syncConfiguration(state) {
    if (configDirty) {
      return;
    }
    refs.seed.value = String(state.seed);
    refs.shards.value = String(state.shards.length);
    refs.budget.value = String(state.authority.budget);
    refs.network.value = state.networkPreset;
    refs.skew.value = String((state.shards[0]?.clockOffsetNanos ?? 0) / 1_000_000);
    refs.traffic.checked = state.autoTraffic;
  }

  function updateShardSelect(shards) {
    const selected = refs.searchShard.value;
    const expected = shards.map((shard) => String(shard.id));
    const actual = Array.from(refs.searchShard.options, (option) => option.value);
    if (expected.join(",") === actual.join(",")) {
      return;
    }

    const fragment = document.createDocumentFragment();
    for (const shard of shards) {
      const option = document.createElement("option");
      option.value = String(shard.id);
      option.textContent = String(shard.id);
      fragment.append(option);
    }
    refs.searchShard.replaceChildren(fragment);
    if (expected.includes(selected)) {
      refs.searchShard.value = selected;
    }
  }

  function renderShardTable(state) {
    const fragment = document.createDocumentFragment();
    for (const shard of state.shards) {
      const row = document.createElement("tr");
      appendTextCell(row, String(shard.id));
      appendTextCell(row, formatMicros(shard.remainingMicros));

      const statusCell = document.createElement("td");
      const status = document.createElement("span");
      status.className = shard.alive ? "status-live" : "status-down";
      if (!shard.alive) {
        status.textContent = "crashed";
      } else if (isAuthorityPartitioned(state, shard.id)) {
        status.textContent = "partitioned";
      } else {
        status.textContent = "up";
      }
      statusCell.append(status);
      row.append(statusCell);
      fragment.append(row);
    }
    refs.shardBody.replaceChildren(fragment);
  }

  function appendTextCell(row, value) {
    const cell = document.createElement("td");
    cell.textContent = value;
    row.append(cell);
  }

  async function runShardAction(shard, action) {
    const node = shard + 1;
    switch (action) {
      case "crash":
        return runCommand(`/api/crash?shard=${shard}`);
      case "restart":
        return runCommand(`/api/restart?shard=${shard}`);
      case "partition":
        return runCommand(`/api/partition?shard=${shard}`);
      case "heal":
        return runCommand(`/api/heal?a=0&b=${node}`);
      default:
        showToast("Unknown action", true);
        return false;
    }
  }

  function renderTopology(state) {
    const signature = JSON.stringify({
      shards: state.shards.map((shard) => [shard.id, shard.alive, shard.incarnation]),
      blocked: state.blockedLinks.map((link) => [link.from, link.to]),
      headroom: state.authority.headroom,
      wallets: state.shards.map((shard) => shard.remainingMicros),
    });
    if (signature === topologySignature) {
      return;
    }
    topologySignature = signature;

    const width = Math.max(720, state.shards.length * 110 + 80);
    const height = 320;
    const authority = { x: width / 2, y: 56 };
    nodePositions = new Map([[0, authority]]);

    state.shards.forEach((shard, index) => {
      const ratio = state.shards.length === 1 ? 0.5 : index / (state.shards.length - 1);
      nodePositions.set(shard.id + 1, {
        x: 60 + ratio * (width - 120),
        y: 210,
      });
    });

    refs.topology.setAttribute("viewBox", `0 0 ${width} ${height}`);
    refs.topology.style.width = `${width}px`;
    const links = svgElement("g", { class: "link-layer" });
    const nodes = svgElement("g", { class: "node-layer" });
    messageLayer = svgElement("g", { class: "message-layer" });

    for (const shard of state.shards) {
      const position = nodePositions.get(shard.id + 1);
      links.append(
        svgElement("line", {
          x1: authority.x,
          y1: authority.y,
          x2: position.x,
          y2: position.y,
          class: `topology-link${isAuthorityPartitioned(state, shard.id) ? " partitioned" : ""}`,
        }),
      );
    }

    nodes.append(createAuthorityNode(authority.x, authority.y, state));
    for (const shard of state.shards) {
      const position = nodePositions.get(shard.id + 1);
      nodes.append(createShardNode(position.x, position.y, shard));
    }
    refs.topology.replaceChildren(links, nodes, messageLayer);

    if (width > refs.topologyScroll.clientWidth && state.shards.length > 5) {
      refs.topologyScroll.scrollLeft = Math.max(
        0,
        (width - refs.topologyScroll.clientWidth) / 2,
      );
    }
  }

  function createAuthorityNode(x, y, state) {
    const group = svgElement("g", {
      class: "authority-node",
      transform: `translate(${x} ${y})`,
    });
    group.append(
      svgElement("circle", { class: "node-core", r: 28 }),
      svgText(0, -2, "Authority", "node-title"),
      svgText(0, 13, formatMicros(state.authority.headroom), "node-subtitle"),
    );
    return group;
  }

  function createShardNode(x, y, shard) {
    const group = svgElement("g", {
      class: `shard-node${shard.alive ? "" : " crashed"}`,
      transform: `translate(${x} ${y})`,
      "data-shard": shard.id,
    });
    group.append(
      svgElement("circle", { class: "node-core", r: 24 }),
      svgText(0, -2, String(shard.id), "node-title"),
      svgText(0, 12, formatMicros(shard.remainingMicros), "node-subtitle"),
    );
    return group;
  }

  function svgText(x, y, value, className) {
    const text = svgElement("text", { x, y, class: className });
    text.textContent = value;
    return text;
  }

  function svgElement(name, attributes) {
    const element = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attributes)) {
      element.setAttribute(key, String(value));
    }
    return element;
  }

  function isAuthorityPartitioned(state, shard) {
    const node = shard + 1;
    return state.blockedLinks.some(
      (link) =>
        (link.from === 0 && link.to === node) ||
        (link.from === node && link.to === 0),
    );
  }

  function ingestEvents(page) {
    if (page.truncated) {
      eventLog = [];
      eventCursor = 0;
      refs.timelineNote.textContent = "Event history trimmed.";
    }

    const fresh = page.events.filter((event) => event.seq > eventCursor);
    for (const event of fresh) {
      eventLog.push(event);
      eventCursor = Math.max(eventCursor, event.seq);
    }
    if (eventLog.length > MAX_TIMELINE_EVENTS) {
      eventLog = eventLog.slice(-MAX_TIMELINE_EVENTS);
    }

    renderTimeline();
    for (const event of fresh.slice(-8)) {
      if (event.kind === "SEND" || event.kind === "DELIVER") {
        animateMessage(event);
      }
    }
  }

  function renderTimeline() {
    const filter = refs.eventFilter.value;
    const visible = eventLog
      .filter((event) => {
        if (filter === "ALL") {
          return true;
        }
        if (filter === "IMPORTANT") {
          return IMPORTANT_KINDS.has(event.kind);
        }
        return event.kind === filter;
      })
      .slice()
      .reverse()
      .slice(0, 80);

    const fragment = document.createDocumentFragment();
    for (const event of visible) {
      const item = document.createElement("li");
      const when = document.createElement("span");
      when.className = "when";
      when.textContent = formatNanos(event.timeNanos);

      const kind = document.createElement("span");
      kind.className = "kind";
      kind.textContent = event.kind;

      const text = document.createElement("span");
      text.className = "text";
      const detail = event.detail || event.label || `${nodeLabel(event.from)} → ${nodeLabel(event.to)}`;
      text.textContent = event.duplicate ? `${detail} (dup)` : detail;
      text.title = text.textContent;

      item.append(when, kind, text);
      fragment.append(item);
    }
    refs.eventList.replaceChildren(fragment);
  }

  function animateMessage(event) {
    if (!messageLayer || !messageLayer.isConnected || event.from < 0 || event.to < 0) {
      return;
    }
    const from = nodePositions.get(event.from);
    const to = nodePositions.get(event.to);
    if (!from || !to) {
      return;
    }

    const pulse = svgElement("circle", {
      class: "message-pulse",
      cx: from.x,
      cy: from.y,
      r: 3.5,
      fill: event.kind === "DELIVER" ? "#1f7a4c" : "#1f4b99",
    });
    pulse.append(
      svgElement("animate", {
        attributeName: "cx",
        from: from.x,
        to: to.x,
        dur: "500ms",
        fill: "freeze",
      }),
      svgElement("animate", {
        attributeName: "cy",
        from: from.y,
        to: to.y,
        dur: "500ms",
        fill: "freeze",
      }),
      svgElement("animate", {
        attributeName: "opacity",
        values: "0;1;0",
        dur: "500ms",
        fill: "freeze",
      }),
    );
    messageLayer.append(pulse);
    window.setTimeout(() => pulse.remove(), 560);
  }

  function resetLocalEvents(note) {
    eventCursor = 0;
    eventLog = [];
    refs.timelineNote.textContent = note;
    renderTimeline();
  }

  function setConnection(online) {
    refs.connection.classList.toggle("online", online);
    refs.connection.classList.toggle("offline", !online);
    refs.connection.title = online ? "Connected" : "Disconnected";
  }

  function showToast(message, error) {
    window.clearTimeout(toastTimer);
    refs.toast.textContent = message;
    refs.toast.classList.toggle("error", error);
    refs.toast.classList.add("visible");
    toastTimer = window.setTimeout(() => {
      refs.toast.classList.remove("visible");
    }, error ? 5_000 : 1_800);
  }

  function formatMicros(value) {
    return moneyFormatter.format(Number(value) / 1_000_000);
  }

  function formatNanos(value) {
    const nanos = Number(value);
    if (Math.abs(nanos) >= 1_000_000_000) {
      return `${(nanos / 1_000_000_000).toFixed(2)} s`;
    }
    return `${(nanos / 1_000_000).toFixed(1)} ms`;
  }

  function nodeLabel(node) {
    if (node === 0) {
      return "auth";
    }
    if (node > 0) {
      return `s${node - 1}`;
    }
    return "—";
  }
})();
